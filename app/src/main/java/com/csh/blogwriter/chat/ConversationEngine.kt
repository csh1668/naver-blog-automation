package com.csh.blogwriter.chat

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    private val clock: () -> Long = System::currentTimeMillis,
) : TurnRunner {
    companion object {
        const val MAX_TOOL_ROUNDS = 6
        private const val JSON_ONLY_HINT = "\n\n반드시 JSON 객체 하나만 출력하세요. 코드 펜스나 설명을 붙이지 마세요."
    }

    private var rotator: KeyRotator? = null
    private var rotatorKeys: List<String> = emptyList()
    private var rotatorModels: List<String> = emptyList()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun runTurn(ctx: ChatContext, listener: TurnListener): TurnResult {
        val keys = keyStore.keysOnce().filter { it.usable }
        if (keys.isEmpty()) return TurnResult.Failure(TurnResult.Reason.NO_KEY, detail = "쓸 수 있는 API 키가 없어요")
        val policy = policyProvider()
        val ids = keys.map { it.id }
        if (rotator == null || rotatorKeys != ids || rotatorModels != policy.models) {
            rotator = rotatorFactory(ids, policy.models); rotatorKeys = ids; rotatorModels = policy.models
        }
        val rot = rotator!!

        val memItems = memory.activeItems()
        val system = promptBuilder.system(memItems, ctx.style, policy.targetLength, ctx.draftTurn)
        val contents = buildContents(ctx)
        val attachedRefs = ctx.attachments.map { it.ref }
        val tools = toolsFactory()

        var useSchema = true
        var transientRetried = false
        var attempts = 0
        var lastDetail = ""
        val maxAttempts = ids.size * policy.models.size + 2
        while (attempts++ < maxAttempts) {
            val pick = rot.next() ?: return TurnResult.Failure(TurnResult.Reason.RATE_LIMITED, rot.nextAvailableAt(), "모든 키가 쉬는 중이에요")
            val secret = keys.firstOrNull { it.id == pick.keyId }?.secret ?: continue
            try {
                val result = runWithTools(secret, pick.model, system, contents, attachedRefs, policy, useSchema, tools, listener)
                rot.report(pick, KeyRotator.Outcome.SUCCESS)
                memory.touch(memItems.map { it.id })
                return result
            } catch (e: GeminiException) {
                lastDetail = e.message.orEmpty()
                when (e.kind) {
                    GeminiException.Kind.RATE_LIMITED -> { rot.report(pick, KeyRotator.Outcome.RATE_LIMITED); keyStore.markLimited(pick.keyId) }
                    GeminiException.Kind.INVALID_KEY -> { rot.report(pick, KeyRotator.Outcome.INVALID_KEY); keyStore.markInvalid(pick.keyId) }
                    // 스키마를 안 받아 주는 모델이면 스키마 없이 같은 pick 으로 한 번 더 (재시도 횟수에 넣지 않는다).
                    GeminiException.Kind.BAD_REQUEST ->
                        if (useSchema) { useSchema = false; attempts-- }
                        else return TurnResult.Failure(TurnResult.Reason.OTHER, detail = lastDetail)
                    // 일시적 오류는 같은 pick 으로 한 번 재시도한 뒤 다음 pick 으로 넘긴다.
                    GeminiException.Kind.SERVER, GeminiException.Kind.NETWORK ->
                        if (!transientRetried) { transientRetried = true; attempts-- }
                        else rot.report(pick, KeyRotator.Outcome.TRANSIENT)
                }
            } catch (e: BadResponse) {
                return TurnResult.Failure(TurnResult.Reason.BAD_RESPONSE, detail = e.message.orEmpty())
            }
        }
        return TurnResult.Failure(TurnResult.Reason.NETWORK, detail = lastDetail.ifEmpty { "재시도 한도 초과" })
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
        repeat(MAX_TOOL_ROUNDS + 2) {
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
            val calls = mutableListOf<GFunctionCall>()
            var lastPartial: String? = null
            client.generateStream(secret, model, req).collect { chunk ->
                chunk.text?.let { text += it }
                calls += chunk.functionCalls
                PartialSayExtractor.extract(text)?.let { partial ->
                    if (partial != lastPartial) { lastPartial = partial; listener.onPartialSay(partial) }
                }
            }

            if (calls.isNotEmpty()) {
                if (++toolRounds > MAX_TOOL_ROUNDS) throw BadResponse("도구 호출이 너무 많습니다")
                contents += GContent("model", calls.map { GPart(functionCall = it) })
                contents += GContent("user", calls.map { call ->
                    GPart(functionResponse = GFunctionResponse(call.name, tools.execute(call.name, call.args, listener::onToolStatus)))
                })
                return@repeat
            }

            if (text.isEmpty()) throw BadResponse("빈 응답")
            val parsed = runCatching { TurnResponseJson.decode(text) }.getOrElse {
                // 온도 0 으로 한 번 더 — 그래도 안 되면 포기.
                if (!jsonRetry) { jsonRetry = true; temperature = 0.0; return@repeat }
                throw BadResponse("JSON 해석 실패: ${it.message}")
            }
            val repaired = parsed.post?.let { PostContentRepair.repair(it, attachedRefs) }
            return TurnResult.Success(if (repaired != null) parsed.copy(post = repaired.content) else parsed, repaired?.fixes ?: emptyList(), model)
        }
        throw BadResponse("도구 호출이 너무 많습니다")
    }

    /** 대화 기록 → contents. 사진은 첫 user 파트에 inlineData 로, ref 라벨을 텍스트로 함께 붙인다. 현재 post 가 있으면 마지막에 전문을 붙인다. */
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
                MessageKind.PLAN, MessageKind.POST -> m.payloadJson
                MessageKind.SYSTEM -> return@forEach
            }
            out += GContent(role, listOf(GPart(text = text)))
        }
        if (ctx.currentPost != null) {
            out += GContent("user", listOf(GPart(text = "현재 초안(JSON): " + PostContentJson.encode(ctx.currentPost) + "\n요청을 반영해 수정된 전체 post 를 다시 내 주세요.")))
        }
        if (ctx.draftTurn) out += GContent("user", listOf(GPart(text = "이번 턴에는 post 를 채워 완성 초안을 내 주세요.")))
        return out
    }
}
