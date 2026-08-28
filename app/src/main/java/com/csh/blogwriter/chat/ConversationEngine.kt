package com.csh.blogwriter.chat

import android.util.Log

import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.data.repo.MessageKind
import com.csh.blogwriter.data.repo.MessageRole
import com.csh.blogwriter.domain.model.PostContentJson
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.llm.GContent
import com.csh.blogwriter.llm.GFunctionCall
import com.csh.blogwriter.llm.GFunctionCallingConfig
import com.csh.blogwriter.llm.GFunctionResponse
import com.csh.blogwriter.llm.GGenerationConfig
import com.csh.blogwriter.llm.GInlineData
import com.csh.blogwriter.llm.GPart
import com.csh.blogwriter.llm.GRequest
import com.csh.blogwriter.llm.GSystemInstruction
import com.csh.blogwriter.llm.GTool
import com.csh.blogwriter.llm.GToolConfig
import com.csh.blogwriter.llm.GeminiClient
import com.csh.blogwriter.llm.GeminiException
import com.csh.blogwriter.llm.KeyRotator
import com.csh.blogwriter.llm.ModelPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 한 턴 = 시스템 프롬프트 조립 → (키, 모델) 선택 → streamGenerateContent(SSE) → 도구 루프 → JSON 파싱 → post 보정.
 * 키 로테이션·모델 다운그레이드는 KeyRotator, 도구 실행은 ToolExecutor 에 위임. UI 를 모른다.
 */
class ConversationEngine(
    private val client: GeminiClient,
    private val keyStore: ApiKeyStore,
    private val rotatorFactory: (keyIds: List<String>, models: List<String>) -> KeyRotator,
    private val policyProvider: suspend () -> ModelPolicy,
    private val promptBuilder: PromptBuilder,
    private val memory: MemoryRepository,
    private val toolsFactory: () -> ToolExecutor,
    @Suppress("UNUSED_PARAMETER") clock: () -> Long = System::currentTimeMillis,
) : TurnRunner {
    companion object {
        const val MAX_TOOL_ROUNDS = 6
        private const val JSON_ONLY_HINT = "\n\n반드시 JSON 객체 하나만 출력하세요. 코드 펜스나 설명을 붙이지 마세요."
        private const val TRUNCATED = "응답이 너무 길어 잘렸어요"
    }

    /** 싱글턴이라 여러 화면에서 동시에 들어올 수 있다 — 로테이터 재사용/재생성은 잠금 안에서. */
    private val rotatorLock = Mutex()
    private var rotator: KeyRotator? = null
    private var rotatorKeys: List<String> = emptyList()
    private var rotatorModels: List<String> = emptyList()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun runTurn(ctx: ChatContext, listener: TurnListener): TurnResult {
        val keys = keyStore.keysOnce().filter { it.usable }
        if (keys.isEmpty()) return TurnResult.Failure(TurnResult.Reason.NO_KEY, detail = "쓸 수 있는 API 키가 없어요")
        val policy = policyProvider()
        val ids = keys.map { it.id }
        val rot = rotatorFor(ids, policy.models)

        val memItems = memory.activeItems()
        val system = promptBuilder.system(memItems, ctx.style, policy.targetLength, ctx.draftTurn)
        val contents = buildContents(ctx)
        val attachedRefs = ctx.attachments.map { it.ref }
        val tools = toolsFactory()

        var useSchema = true
        // 이번 "턴" 전체에 한 번만 주는 무료 재시도. pick 마다 주면 시도 상한이 흐려진다.
        var transientRetried = false
        // 이번 턴에 하드 4xx(404 모델 없음 등)를 낸 모델 — 다음 pick 부터 건너뛴다.
        val deadModels = mutableSetOf<String>()
        var attempts = 0
        var lastDetail = ""
        var lastKind: GeminiException.Kind? = null
        val maxAttempts = ids.size * policy.models.size + 2
        while (attempts++ < maxAttempts) {
            val picked = rot.next() ?: return if (lastKind == GeminiException.Kind.SERVER) {
                TurnResult.Failure(TurnResult.Reason.SERVER, rot.nextAvailableAt(), lastDetail)
            } else {
                TurnResult.Failure(TurnResult.Reason.RATE_LIMITED, rot.nextAvailableAt(), "모든 키가 쉬는 중이에요")
            }
            val model = if (picked.model in deadModels) {
                policy.models.firstOrNull { it !in deadModels }
                    ?: return TurnResult.Failure(
                        if (lastKind == GeminiException.Kind.SERVER) TurnResult.Reason.SERVER else TurnResult.Reason.OTHER,
                        detail = lastDetail.ifEmpty { "쓸 수 있는 모델이 없어요" },
                    )
            } else picked.model
            val pick = picked.copy(model = model)
            val secret = keys.firstOrNull { it.id == pick.keyId }?.secret ?: continue
            try {
                Log.d(TAG, "attempt $attempts/$maxAttempts model=${pick.model} key=…${pick.keyId.takeLast(4)} schema=$useSchema")
                val result = runWithTools(secret, pick.model, system, contents, attachedRefs, policy, useSchema, tools, listener)
                Log.d(TAG, "attempt $attempts ok model=${pick.model}")
                rot.report(pick, KeyRotator.Outcome.SUCCESS)
                memory.touch(memItems.map { it.id })
                return result
            } catch (e: GeminiException) {
                Log.w(TAG, "attempt $attempts failed model=${pick.model} kind=${e.kind} code=${e.code} msg=${e.message}")
                lastDetail = e.message.orEmpty()
                lastKind = e.kind
                when (e.kind) {
                    GeminiException.Kind.RATE_LIMITED -> { rot.report(pick, KeyRotator.Outcome.RATE_LIMITED); keyStore.markLimited(pick.keyId) }
                    GeminiException.Kind.INVALID_KEY -> { rot.report(pick, KeyRotator.Outcome.INVALID_KEY); keyStore.markInvalid(pick.keyId) }
                    GeminiException.Kind.BAD_REQUEST -> when {
                        // 400 이고 스키마를 보냈다면 스키마를 안 받아 주는 모델 — 스키마 없이 같은 pick 으로 한 번 더(시도 횟수 미차감).
                        e.code == 400 && useSchema -> { useSchema = false; attempts-- }
                        e.code == 400 -> return TurnResult.Failure(TurnResult.Reason.OTHER, detail = lastDetail)
                        // 그 밖의 4xx(404 모델 없음, 413 등)는 이 모델을 접고 다음 pick/모델로.
                        else -> { deadModels += pick.model; rot.report(pick, KeyRotator.Outcome.TRANSIENT) }
                    }
                    GeminiException.Kind.NETWORK ->
                        if (!transientRetried) { transientRetried = true; attempts-- }
                        else rot.report(pick, KeyRotator.Outcome.TRANSIENT)
                    // 5xx(503 과부하 등)는 키가 아니라 모델 쪽 문제 — 같은 pick 으로 한 번만 더 해 보고,
                    // 그래도 안 되면 이 모델을 이번 턴에서 접고 대체 모델로 내려간다(키만 바꿔 가며 같은 모델을 두드리지 않는다).
                    // 503(과부하)은 몇 분씩 이어지고, 거절된 요청도 일일 한도(RPD)에서 차감된다 — 재시도 없이 바로 모델을 접는다.
                    // 그 밖의 5xx 는 일시적일 수 있으니 같은 pick 으로 한 번만 더.
                    GeminiException.Kind.SERVER ->
                        if (e.code != 503 && !transientRetried) { transientRetried = true; attempts-- }
                        else { deadModels += pick.model; rot.report(pick, KeyRotator.Outcome.SERVER_ERROR) }
                }
            } catch (e: BadResponse) {
                Log.w(TAG, "attempt $attempts bad response: ${e.message}")
                return TurnResult.Failure(TurnResult.Reason.BAD_RESPONSE, detail = e.message.orEmpty())
            }
        }
        return when (lastKind) {
            GeminiException.Kind.RATE_LIMITED -> TurnResult.Failure(TurnResult.Reason.RATE_LIMITED, rot.nextAvailableAt(), lastDetail)
            GeminiException.Kind.INVALID_KEY -> TurnResult.Failure(TurnResult.Reason.NO_KEY, detail = lastDetail)
            GeminiException.Kind.BAD_REQUEST -> TurnResult.Failure(TurnResult.Reason.OTHER, detail = lastDetail)
            GeminiException.Kind.SERVER -> TurnResult.Failure(TurnResult.Reason.SERVER, detail = lastDetail)
            else -> TurnResult.Failure(TurnResult.Reason.NETWORK, detail = lastDetail.ifEmpty { "재시도 한도 초과" })
        }
    }

    private suspend fun rotatorFor(ids: List<String>, models: List<String>): KeyRotator = rotatorLock.withLock {
        val current = rotator
        if (current != null && rotatorKeys == ids && rotatorModels == models) current
        else rotatorFactory(ids, models).also { rotator = it; rotatorKeys = ids; rotatorModels = models }
    }

    private class BadResponse(msg: String) : Exception(msg)

    private suspend fun runWithTools(
        secret: String,
        model: String,
        system: String,
        base: List<GContent>,
        attachedRefs: List<String>,
        policy: ModelPolicy,
        useSchema: Boolean,
        tools: ToolExecutor,
        listener: TurnListener,
    ): TurnResult.Success {
        val contents = base.toMutableList()
        var temperature = policy.temperature
        var jsonRetry = false
        var toolRounds = 0
        // 매 반복은 성공 반환·예외·(도구 라운드 | JSON 재시도) 중 하나 — 둘 다 상한이 있어 루프는 끝난다.
        while (true) {
            val req = GRequest(
                contents = contents,
                systemInstruction = GSystemInstruction(listOf(GPart(text = if (useSchema) system else system + JSON_ONLY_HINT))),
                tools = listOf(GTool(TurnSchemas.functionDeclarations())),
                toolConfig = GToolConfig(GFunctionCallingConfig("AUTO")),
                generationConfig = GGenerationConfig(
                    temperature = temperature, maxOutputTokens = 8192,
                    responseMimeType = if (useSchema) "application/json" else null,
                    responseJsonSchema = if (useSchema) TurnSchemas.turnResponseJsonSchema() else null,
                ),
            )
            var text = ""
            var finishReason: String? = null
            val calls = mutableListOf<GFunctionCall>()
            // 새 스트림은 처음부터 다시 쌓인다 — UI 가 이전 접두를 지우도록 빈 문자열을 먼저 보낸다.
            var lastPartial: String? = ""
            listener.onPartialSay("")
            client.generateStream(secret, model, req).collect { chunk ->
                chunk.text?.let { text += it }
                calls += chunk.functionCalls
                chunk.candidates.firstOrNull()?.finishReason?.let { finishReason = it }
                PartialSayExtractor.extract(text)?.let { partial ->
                    if (partial != lastPartial) { lastPartial = partial; listener.onPartialSay(partial) }
                }
            }

            if (calls.isNotEmpty()) {
                if (++toolRounds > MAX_TOOL_ROUNDS) throw BadResponse("도구 호출이 너무 많습니다")
                contents += GContent("model", calls.map { GPart(functionCall = it) })
                contents += GContent("user", calls.map { call ->
                    GPart(functionResponse = GFunctionResponse(call.name, runTool(tools, call, listener)))
                })
                continue
            }

            if (text.isEmpty()) throw BadResponse(if (finishReason == "MAX_TOKENS") TRUNCATED else "빈 응답")
            val parsed = runCatching { TurnResponseJson.decode(text) }.getOrElse {
                // 잘려서 못 읽는 거라면 다시 물어봐도 똑같다 — 온도 0 재시도는 건너뛴다.
                if (finishReason == "MAX_TOKENS") throw BadResponse(TRUNCATED)
                if (!jsonRetry) { jsonRetry = true; temperature = 0.0; continue }
                throw BadResponse("JSON 해석 실패: ${it.message}")
            }
            val repaired = parsed.post?.let { PostContentRepair.repair(it, attachedRefs) }
            return TurnResult.Success(if (repaired != null) parsed.copy(post = repaired.content) else parsed, repaired?.fixes ?: emptyList(), model)
        }
    }

    /** 도구는 throw 하지 않기로 약속돼 있지만, 어겨도 턴을 죽이지 않고 오류 JSON 으로 모델에 돌려준다. */
    private suspend fun runTool(tools: ToolExecutor, call: GFunctionCall, listener: TurnListener): JsonObject =
        try {
            tools.execute(call.name, call.args, listener::onToolStatus)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            buildJsonObject { put("error", e.message ?: "tool failed") }
        }

    /** 대화 기록 → contents. 사진은 첫 user 파트에 inlineData 로, ref 라벨을 텍스트로 함께 붙인다. 현재 계획·post 가 있으면 마지막에 전문을 붙인다. */
    private fun buildContents(ctx: ChatContext): List<GContent> {
        val out = mutableListOf<GContent>()
        if (ctx.attachments.isNotEmpty()) {
            val parts = mutableListOf(GPart(text = "첨부 사진 (ref 순서대로):"))
            ctx.attachments.forEach { a -> parts += GPart(text = "ref=${a.ref}"); parts += GPart(inlineData = GInlineData(a.mimeType, a.jpegBase64)) }
            out += GContent("user", parts)
        }
        ctx.history.forEach { m ->
            if (m.role == MessageRole.SYSTEM || m.kind == MessageKind.SYSTEM) return@forEach
            val role = if (m.role == MessageRole.USER) "user" else "model"
            val text = when (m.kind) {
                MessageKind.TEXT -> runCatching { json.parseToJsonElement(m.payloadJson).jsonObject["text"]!!.jsonPrimitive.content }.getOrDefault(m.payloadJson)
                MessageKind.PHOTOS -> "(사진 ${runCatching { json.parseToJsonElement(m.payloadJson).jsonObject["count"]!!.jsonPrimitive.content }.getOrDefault("")}장 첨부)"
                // 계획은 마크다운 전문 그대로 보여 준다 (payload 는 {"markdown": …} 래퍼일 뿐이다).
                MessageKind.PLAN -> "[계획]\n" + runCatching { json.parseToJsonElement(m.payloadJson).jsonObject["markdown"]!!.jsonPrimitive.content }.getOrDefault(m.payloadJson)
                MessageKind.POST -> m.payloadJson
                MessageKind.SYSTEM -> return@forEach
            }
            out += GContent(role, listOf(GPart(text = text)))
        }
        if (ctx.currentPlan != null) {
            val ask = if (ctx.draftTurn) "" else "\n요청을 반영해 계획 전체를 다시 내 주세요."
            out += GContent("user", listOf(GPart(text = "현재 계획:\n" + ctx.currentPlan + ask)))
        }
        if (ctx.currentPost != null) {
            out += GContent("user", listOf(GPart(text = "현재 초안(JSON): " + PostContentJson.encode(ctx.currentPost) + "\n요청을 반영해 수정된 전체 post 를 다시 내 주세요.")))
        }
        if (ctx.draftTurn) out += GContent("user", listOf(GPart(text = "이번 턴에는 post 를 채워 완성 초안을 내 주세요.")))
        return out
    }
}

private const val TAG = "ConversationEngine"
