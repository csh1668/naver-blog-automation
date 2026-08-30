package com.csh.blogwriter.chat

import com.csh.blogwriter.blog.PostSummary
import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.data.repo.MessageKind
import com.csh.blogwriter.data.repo.MessageRole
import com.csh.blogwriter.data.repo.SessionMode
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.llm.ApiKey
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.llm.GeminiClient
import com.csh.blogwriter.llm.KeyRotator
import com.csh.blogwriter.llm.ModelPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationEngineTest {
    private val server = MockWebServer()
    private val keys = MutableStateFlow(listOf(ApiKey("k1", "SECRET1", 0, lastOkAt = 1), ApiKey("k2", "SECRET2", 0, lastOkAt = 1)))
    private val keyStore = object : ApiKeyStore {
        override val keys: Flow<List<ApiKey>> = this@ConversationEngineTest.keys
        override val hasUsableKey: Flow<Boolean> = keys.map { l -> l.any { it.usable } }
        override suspend fun add(secrets: List<String>) = emptyList<ApiKey>()
        override suspend fun remove(id: String) {}
        override suspend fun markOk(id: String) {}
        override suspend fun markLimited(id: String) {}
        override suspend fun markInvalid(id: String) {
            this@ConversationEngineTest.keys.value = this@ConversationEngineTest.keys.value.map { if (it.id == id) it.copy(disabled = true) else it }
        }
        override suspend fun resetAll() {}
    }
    private val memory = object : MemoryRepository {
        val touched = mutableListOf<Long>()
        override fun observeAll() = flowOf(emptyList<MemoryItem>())
        override suspend fun activeItems(limit: Int) = listOf(MemoryItem(7, MemoryKind.PREFERENCE, "가격은 정확히", "chat", 0, true, null))
        override suspend fun add(kind: MemoryKind, text: String, source: String) = MemoryItem(1, kind, text, source, 0, true, null)
        override suspend fun update(id: Long, text: String) {}
        override suspend fun setEnabled(id: Long, enabled: Boolean) {}
        override suspend fun delete(id: Long) {}
        override suspend fun touch(ids: List<Long>) { touched += ids }
    }
    private val promptStore = object : PromptStore {
        override suspend fun text(section: PromptSection) = "[${section.name}] {{style}} {{memory}} {{minLen}} {{maxLen}}"
        override fun observe(section: PromptSection): Flow<String> = flowOf("[${section.name}]")
        override suspend fun override(section: PromptSection, text: String?) {}
        override suspend fun isOverridden(section: PromptSection) = false
    }
    private val toolCalls = mutableListOf<String>()
    private val tools = object : ToolExecutor {
        override suspend fun execute(name: String, args: JsonObject, onProgress: (String) -> Unit): JsonObject {
            toolCalls += name; onProgress("검색 중"); return buildJsonObject { put("results", "원주 한우 주소 …") }
        }
    }
    private var toolOverride: ToolExecutor? = null

    private class Recorder : TurnListener {
        val toolStatus = mutableListOf<String>()
        val partials = mutableListOf<String>()
        override fun onToolStatus(text: String) { toolStatus += text }
        override fun onPartialSay(text: String) { partials += text }
    }

    private fun thoughtChunk(text: String) = """{"candidates":[{"content":{"role":"model","parts":[{"text":${quote(text)},"thought":true}]}}]}"""

    private var now = 0L
    private lateinit var engine: ConversationEngine

    @Before fun setUp() {
        server.start()
        engine = engine()
    }

    private fun engine(toolsFactory: () -> ToolExecutor = { toolOverride ?: tools }): ConversationEngine {
        val client = GeminiClient(OkHttpClient(), server.url("/").toString().trimEnd('/'))
        return ConversationEngine(
            client, keyStore, { k, m -> KeyRotator(k, m) { now } }, { ModelPolicy(listOf("flash", "lite")) },
            PromptBuilder(promptStore), memory, toolsFactory,
        ) { now }
    }
    @After fun tearDown() = server.shutdown()

    private fun ctx(draft: Boolean = false) = ChatContext(
        history = listOf(ChatMessage(1, "s", 0, MessageRole.USER, MessageKind.TEXT, "{\"text\":\"원주 한우 다녀왔어요\"}", 0)),
        attachments = listOf(Attachment("img_001", "AAAA")), style = null, draftTurn = draft, currentPost = null,
    )

    private fun quote(s: String) = Json.encodeToString(kotlinx.serialization.serializer<String>(), s)
    private fun chunk(text: String, finishReason: String? = null): String {
        val finish = if (finishReason == null) "" else ",\"finishReason\":\"" + finishReason + "\""
        return """{"candidates":[{"content":{"role":"model","parts":[{"text":${quote(text)}}]}$finish}]}"""
    }
    private val callChunk = """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"web_search","args":{"query":"원주 한우"}},"thoughtSignature":"sig-abc"}]}}]}"""
    private fun sse(vararg chunks: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") { "data: $it\n\n" })

    /** 모델 텍스트를 `say` 값 한가운데에서 자른 SSE 두 청크로 내려 준다. */
    private fun textResponse(json: String): MockResponse {
        val cut = json.indexOf("\"say\":\"") + "\"say\":\"".length + 3
        return sse(chunk(json.substring(0, cut)), chunk(json.substring(cut)))
    }

    private fun error(code: Int, status: String) =
        MockResponse().setResponseCode(code).setBody("""{"error":{"code":$code,"status":"$status","message":"q"}}""")

    @Test
    fun happyTurnBuildsRequestAndParsesResponse() = runTest {
        server.enqueue(textResponse("""{"say":"이렇게 써 볼까요?","plan":"# 제목\n\n## 글 구성\n1. 도입","quickReplies":["더 짧게"],"readyToDraft":true}"""))
        val r = engine.runTurn(ctx(), Recorder()) as TurnResult.Success
        assertEquals("이렇게 써 볼까요?", r.response.say); assertEquals("flash", r.usedModel)
        assertEquals("# 제목\n\n## 글 구성\n1. 도입", r.response.plan)
        val req = server.takeRequest(); val body = req.body.readUtf8()
        assertEquals("SECRET1", req.getHeader("x-goog-api-key"))
        assertTrue(body.contains("\"inlineData\"")); assertTrue(body.contains("\"responseJsonSchema\"")); assertTrue(body.contains("web_search"))
        assertTrue(body.contains("[ROLE]")); assertTrue(body.contains("가격은 정확히"))
        assertEquals(listOf(7L), memory.touched)
    }

    /** 계획은 마크다운 그대로 히스토리에 실리고, 지금 계획은 "전체를 다시 내라"는 부탁과 함께 붙는다. */
    @Test
    fun planHistoryAndCurrentPlanGoIntoTheRequestAsMarkdown() = runTest {
        server.enqueue(textResponse("""{"say":"고쳤어요","quickReplies":[],"readyToDraft":true}"""))
        val markdown = "# 제목\n## 글 구성\n1. 도입"
        val payload = """{"markdown":"# 제목\n## 글 구성\n1. 도입"}"""
        val ctx = ctx().copy(
            history = ctx().history + ChatMessage(2, "s", 1, MessageRole.ASSISTANT, MessageKind.PLAN, payload, 0),
            currentPlan = markdown,
        )
        engine.runTurn(ctx, Recorder())

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("[계획]"))
        assertFalse(body.contains("markdown"))
        assertTrue(body.contains("현재 계획:"))
        assertTrue(body.contains("계획 전체를 다시 내 주세요"))
    }

    @Test
    fun streamsPartialSayWhileReceiving() = runTest {
        server.enqueue(textResponse("""{"say":"이렇게 써 볼까요?","quickReplies":[],"readyToDraft":false}"""))
        val rec = Recorder()
        val r = engine.runTurn(ctx(), rec) as TurnResult.Success
        assertEquals("", rec.partials.first())
        assertTrue("partials=${rec.partials}", rec.partials.size >= 3)
        rec.partials.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", b.length > a.length && b.startsWith(a)) }
        assertEquals(r.response.say, rec.partials.last())
    }

    @Test
    fun resetsPartialSayBetweenStreams() = runTest {
        server.enqueue(sse(chunk("""{"say":"찾아볼게"""), callChunk))
        server.enqueue(textResponse("""{"say":"찾았어요","quickReplies":[],"readyToDraft":false}"""))
        val rec = Recorder()
        val r = engine.runTurn(ctx(), rec) as TurnResult.Success
        assertEquals("찾았어요", r.response.say)
        val firstText = rec.partials.indexOfFirst { it.isNotEmpty() }
        assertTrue("partials=${rec.partials}", firstText >= 0)
        assertTrue("리셋 신호 없음: ${rec.partials}", rec.partials.drop(firstText).contains(""))
        assertEquals("찾았어요", rec.partials.last())
    }

    @Test
    fun toolLoopFeedsFunctionResponseBack() = runTest {
        server.enqueue(sse(callChunk))
        server.enqueue(textResponse("""{"say":"찾았어요","quickReplies":[],"readyToDraft":false}"""))
        val rec = Recorder()
        val r = engine.runTurn(ctx(), rec) as TurnResult.Success
        assertEquals("찾았어요", r.response.say); assertEquals(listOf("web_search"), toolCalls); assertEquals(listOf("검색 중"), rec.toolStatus)
        server.takeRequest(); val second = server.takeRequest().body.readUtf8()
        assertTrue(second.contains("\"functionResponse\"")); assertTrue(second.contains("원주 한우 주소"))
    }

    @Test
    fun throwingToolIsReportedBackAsErrorJson() = runTest {
        toolOverride = object : ToolExecutor {
            override suspend fun execute(name: String, args: JsonObject, onProgress: (String) -> Unit): JsonObject = throw IllegalStateException("도구 폭발")
        }
        server.enqueue(sse(callChunk))
        server.enqueue(textResponse("""{"say":"그래도 계속","quickReplies":[],"readyToDraft":false}"""))
        val r = engine.runTurn(ctx(), Recorder()) as TurnResult.Success
        assertEquals("그래도 계속", r.response.say)
        server.takeRequest(); val second = server.takeRequest().body.readUtf8()
        assertTrue(second.contains("\"error\"")); assertTrue(second.contains("도구 폭발"))
    }

    @Test
    fun tooManyToolRoundsFails() = runTest {
        repeat(8) { server.enqueue(sse(callChunk)) }
        val r = engine.runTurn(ctx(), Recorder()) as TurnResult.Failure
        assertEquals(TurnResult.Reason.BAD_RESPONSE, r.reason)
        assertEquals(ConversationEngine.MAX_TOOL_ROUNDS, toolCalls.size)
    }

    @Test
    fun rateLimitRotatesToNextKeyAndInvalidKeyIsDisabled() = runTest {
        server.enqueue(error(429, "RESOURCE_EXHAUSTED"))
        server.enqueue(textResponse("""{"say":"두 번째 키로","quickReplies":[],"readyToDraft":false}"""))
        val r = engine.runTurn(ctx(), Recorder()) as TurnResult.Success
        assertEquals("두 번째 키로", r.response.say)
        server.takeRequest(); assertEquals("SECRET2", server.takeRequest().getHeader("x-goog-api-key"))

        server.enqueue(error(403, "PERMISSION_DENIED"))
        server.enqueue(textResponse("""{"say":"ok!","quickReplies":[],"readyToDraft":false}"""))
        engine.runTurn(ctx(), Recorder())
        assertTrue(keys.value.any { it.disabled })
    }

    @Test
    fun allKeysExhaustedReturnsRateLimitedWithRetryAt() = runTest {
        repeat(4) { server.enqueue(error(429, "RESOURCE_EXHAUSTED")) }
        val r = engine.runTurn(ctx(), Recorder()) as TurnResult.Failure
        assertEquals(TurnResult.Reason.RATE_LIMITED, r.reason); assertEquals(now + KeyRotator.KEY_COOLDOWN_MS, r.retryAt)
    }

    @Test
    fun schemaRejectionRetriesWithoutSchemaAndDraftIsRepaired() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"response_json_schema is not supported with tools"}}"""))
        server.enqueue(textResponse("""{"say":"초안이에요","quickReplies":[],"readyToDraft":true,"post":{"title":"","blocks":[{"type":"paragraph","runs":[{"text":"본문 문단"}]},{"type":"image","ref":"img_999"}]}}"""))
        val r = engine.runTurn(ctx(draft = true), Recorder()) as TurnResult.Success
        server.takeRequest(); val second = server.takeRequest().body.readUtf8()
        assertTrue(!second.contains("responseJsonSchema"))
        val post = r.response.post!!
        assertEquals("본문 문단", post.title)
        assertEquals("img_001", (post.blocks[1] as com.csh.blogwriter.domain.model.Block.Image).ref)
        assertTrue(r.repairs.isNotEmpty())
    }

    @Test
    fun modelNotFoundFallsBackToNextModel() = runTest {
        server.enqueue(error(404, "NOT_FOUND"))
        server.enqueue(textResponse("""{"say":"라이트로 갔어요","quickReplies":[],"readyToDraft":false}"""))
        val r = engine.runTurn(ctx(), Recorder()) as TurnResult.Success
        assertEquals("lite", r.usedModel)
        assertTrue(server.takeRequest().path!!.contains("flash:streamGenerateContent"))
        assertTrue(server.takeRequest().path!!.contains("lite:streamGenerateContent"))
    }

    @Test
    fun toolCallSignatureIsEchoedBackToTheModel() = runTest {
        // Gemini 3.x: functionCall 의 thoughtSignature 를 다음 요청에 되돌려 주지 않으면 400 이다.
        server.enqueue(sse(callChunk))
        server.enqueue(textResponse("""{"say":"찾았어요","quickReplies":[],"readyToDraft":false}"""))
        engine.runTurn(ctx(), Recorder()) as TurnResult.Success
        server.takeRequest()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(second.contains("\"thoughtSignature\":\"sig-abc\""))
        assertTrue(second.contains("\"functionResponse\""))
    }

    @Test
    fun transientErrorRetriesSameKeyOnce() = runTest {
        server.enqueue(error(500, "INTERNAL"))
        server.enqueue(textResponse("""{"say":"다시 됐어요","quickReplies":[],"readyToDraft":false}"""))
        val r = engine.runTurn(ctx(), Recorder()) as TurnResult.Success
        assertEquals("다시 됐어요", r.response.say)
        assertEquals("SECRET1", server.takeRequest().getHeader("x-goog-api-key"))
        assertEquals("SECRET1", server.takeRequest().getHeader("x-goog-api-key"))
    }

    @Test
    fun repeatedServerErrorsGiveUpEveryModelAndFailWithServer() = runTest {
        // 503 이 계속되면: flash 접음 → lite → lite 도 접음 → SERVER 실패. 요청은 모델당 1번뿐(한도 절약).
        repeat(12) { server.enqueue(error(503, "UNAVAILABLE")) }
        val r = engine.runTurn(ctx(), Recorder()) as TurnResult.Failure
        assertEquals(TurnResult.Reason.SERVER, r.reason)
        val paths = (0 until server.requestCount).map { server.takeRequest().path!! }
        assertEquals(2, paths.size)
        assertTrue(paths.any { it.contains("lite:streamGenerateContent") })
    }

    @Test
    fun serverOverloadFallsBackToNextModelWithoutRetry() = runTest {
        // 503(과부하)은 재시도도 하지 않는다 — 거절된 요청도 일일 한도에서 빠지므로 바로 대체 모델로.
        server.enqueue(error(503, "UNAVAILABLE"))
        server.enqueue(textResponse("""{"say":"라이트로 갔어요","quickReplies":[],"readyToDraft":false}"""))
        val r = engine.runTurn(ctx(), Recorder()) as TurnResult.Success
        assertEquals("lite", r.usedModel)
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().path!!.contains("flash:streamGenerateContent"))
        assertTrue(server.takeRequest().path!!.contains("lite:streamGenerateContent"))
    }

    @Test
    fun otherServerErrorsStillGetOneRetryBeforeFallingBack() = runTest {
        server.enqueue(error(500, "INTERNAL"))
        server.enqueue(error(500, "INTERNAL"))
        server.enqueue(textResponse("""{"say":"라이트로 갔어요","quickReplies":[],"readyToDraft":false}"""))
        val r = engine.runTurn(ctx(), Recorder()) as TurnResult.Success
        assertEquals("lite", r.usedModel)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun unparsableTextRetriesAtTemperatureZeroThenFails() = runTest {
        repeat(2) { server.enqueue(sse(chunk("이건 JSON 이 아니에요"))) }
        val r = engine.runTurn(ctx(), Recorder()) as TurnResult.Failure
        assertEquals(TurnResult.Reason.BAD_RESPONSE, r.reason)
        assertEquals(2, server.requestCount)
        server.takeRequest(); assertTrue(server.takeRequest().body.readUtf8().contains("\"temperature\":0.0"))
    }

    @Test
    fun truncatedResponseFailsWithoutRetry() = runTest {
        server.enqueue(sse(chunk("""{"say":"길게 쓰다가 잘""", finishReason = "MAX_TOKENS")))
        val r = engine.runTurn(ctx(), Recorder()) as TurnResult.Failure
        assertEquals(TurnResult.Reason.BAD_RESPONSE, r.reason)
        assertEquals("응답이 너무 길어 잘렸어요", r.detail)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun noUsableKeyFailsFast() = runTest {
        keys.value = emptyList()
        assertEquals(TurnResult.Reason.NO_KEY, (engine.runTurn(ctx(), Recorder()) as TurnResult.Failure).reason)
    }
    // ---- 사실 확인 루프 (계획 전) ----

    /** 계획이 아직 없고 질문 횟수가 남았으면 "더 물어봐도 된다"는 지시가 붙는다. */
    @Test
    fun factCheckInstructionIsAskWhileRoundsRemain() = runTest {
        server.enqueue(textResponse("""{"say":"몇 가지만 여쭐게요","question":"어디 다녀오셨어요?","quickReplies":["잘 모르겠어요"]}"""))
        val r = engine.runTurn(ctx().copy(questionRounds = 1), Recorder()) as TurnResult.Success
        assertEquals("어디 다녀오셨어요?", r.response.question)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(ConversationEngine.ASK_FACTS))
        assertFalse(body.contains(ConversationEngine.STOP_ASKING))
    }

    /** 4번을 다 쓰면 지시가 "더 묻지 말고 계획을 내라"로 바뀐다. */
    @Test
    fun factCheckInstructionStopsAskingAtTheLimit() = runTest {
        server.enqueue(textResponse("""{"say":"계획이에요","plan":"# 제목","readyToDraft":true}"""))
        engine.runTurn(ctx().copy(questionRounds = 4), Recorder())
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(ConversationEngine.STOP_ASKING))
        assertFalse(body.contains(ConversationEngine.ASK_FACTS))
    }

    /** 계획이 나온 뒤(피드백 턴)와 초안 턴에는 사실 확인 지시가 빠진다. */
    @Test
    fun factCheckInstructionIsGoneOncePlanExistsAndOnDraftTurns() = runTest {
        server.enqueue(textResponse("""{"say":"고쳤어요","plan":"# 제목","readyToDraft":true}"""))
        engine.runTurn(ctx().copy(currentPlan = "# 제목"), Recorder())
        val feedback = server.takeRequest().body.readUtf8()
        assertFalse(feedback.contains(ConversationEngine.ASK_FACTS))
        assertFalse(feedback.contains(ConversationEngine.STOP_ASKING))

        server.enqueue(textResponse("""{"say":"초안이에요","readyToDraft":true}"""))
        engine.runTurn(ctx(draft = true), Recorder())
        val draft = server.takeRequest().body.readUtf8()
        assertFalse(draft.contains(ConversationEngine.ASK_FACTS))
        assertFalse(draft.contains(ConversationEngine.STOP_ASKING))
    }

    // ---- 턴별 thinking ----

    @Test
    fun draftAndRevisionTurnsAskForHighThinkingAndPlanTurnsForLow() = runTest {
        server.enqueue(textResponse("""{"say":"초안이에요","readyToDraft":true}"""))
        engine.runTurn(ctx(draft = true), Recorder())
        assertTrue(server.takeRequest().body.readUtf8().contains(""""thinkingLevel":"high""""))

        server.enqueue(textResponse("""{"say":"고쳤어요","readyToDraft":true}"""))
        engine.runTurn(ctx().copy(currentPost = PostContent("제목", emptyList())), Recorder())
        assertTrue(server.takeRequest().body.readUtf8().contains(""""thinkingLevel":"high""""))

        server.enqueue(textResponse("""{"say":"계획이에요","plan":"# 제목","readyToDraft":true}"""))
        engine.runTurn(ctx(), Recorder())
        assertTrue(server.takeRequest().body.readUtf8().contains(""""thinkingLevel":"low""""))
    }

    /** thinking 을 안 받아 주는 모델이면 그 필드만 빼고 같은 pick 으로 한 번 더 — 스키마는 그대로 둔다. */
    @Test
    fun thinkingRejectionRetriesWithoutThinkingConfig() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"thinking_config not supported"}}""")
        )
        server.enqueue(textResponse("""{"say":"초안이에요","readyToDraft":true}"""))
        val r = engine.runTurn(ctx(draft = true), Recorder()) as TurnResult.Success
        assertEquals("초안이에요", r.response.say)
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().body.readUtf8().contains("thinkingConfig"))
        val second = server.takeRequest().body.readUtf8()
        assertFalse(second.contains("thinkingConfig"))
        assertTrue(second.contains("responseJsonSchema"))
    }

    /** 사용자가 묶어 둔 사진은 요청에 한 줄로 실리고, 초안 보정에도 그대로 넘어간다. */
    @Test
    fun userPhotoGroupsGoIntoTheRequestAndTheRepair() = runTest {
        server.enqueue(textResponse("""{"say":"초안이에요","readyToDraft":true,"post":{"title":"제목","blocks":[{"type":"image","ref":"img_001"},{"type":"image","ref":"img_002"}]}}"""))
        val ctx = ctx(draft = true).copy(
            attachments = listOf(Attachment("img_001", "AAAA"), Attachment("img_002", "BBBB")),
            photoGroups = listOf(listOf("img_001", "img_002")),
        )
        val r = engine.runTurn(ctx, Recorder()) as TurnResult.Success

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("사용자가 묶어 둔 사진: [img_001, img_002]"))
        val group = r.response.post!!.blocks.single() as com.csh.blogwriter.domain.model.Block.ImageGroup
        assertEquals(listOf("img_001", "img_002"), group.refs)
    }

    // ---- 조언 모드 ----

    @Test
    fun adviceTurnUsesAdviceSchemaToolsAndReportsPostRead() = runTest {
        // 1라운드: read_my_post 호출, 2라운드: {say}
        val callJson = """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"read_my_post","args":{"logNo":"100000000001"}},"thoughtSignature":"sig-x"}]}}]}"""
        server.enqueue(sse(callJson))
        server.enqueue(textResponse("""{"say":"잘한 점: …"}"""))
        val reads = mutableListOf<Pair<String, String>>()
        val listener = object : TurnListener {
            override fun onToolStatus(text: String) {}
            override fun onPartialSay(text: String) {}
            override fun onPostRead(logNo: String, title: String) { reads += logNo to title }
        }
        val advicedTools = object : ToolExecutor {
            override suspend fun execute(name: String, args: JsonObject, onProgress: (String) -> Unit) =
                buildJsonObject { put("logNo", "100000000001"); put("title", "원주 카페 늘봄"); put("text", "본문"); put("imageCount", 3); put("videoCount", 0) }
        }
        val engine = engine(toolsFactory = { advicedTools })
        val ctx = ChatContext(
            history = listOf(ChatMessage(1, "s", 0, MessageRole.USER, MessageKind.TEXT, "{\"text\":\"최근 글 봐 줘\"}", 0)),
            attachments = emptyList(), style = null, draftTurn = false, currentPost = null,
            mode = SessionMode.ADVICE, blogPosts = listOf(PostSummary("100000000001", "원주 카페 늘봄", 0, 1, 2, "", 3)),
        )

        val result = engine.runTurn(ctx, listener) as TurnResult.Success
        assertEquals("잘한 점: …", result.response.say)
        assertEquals(listOf("100000000001" to "원주 카페 늘봄"), reads)

        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val system = first["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(system.contains("[ADVICE_ROLE]")); assertFalse(system.contains("[STRUCTURE]"))
        assertTrue(system.contains("[최근 글 목록]")); assertTrue(system.contains("원주 카페 늘봄"))
        val toolNames = first["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("list_my_posts", "read_my_post"), toolNames)
        val required = first["generationConfig"]!!.jsonObject["responseJsonSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("say"), required)
        assertEquals("high", first["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
        // 조언 턴에는 사실 확인·계획 지시가 붙지 않는다.
        val userTexts = first["contents"]!!.jsonArray.flatMap { it.jsonObject["parts"]!!.jsonArray }.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
        assertFalse(userTexts.any { it.contains("plan 을") })
    }

    // ---- 생각(thought) 요약 ----

    @Test
    fun thoughtPartsStreamSeparatelyAndAreReturned() = runTest {
        server.enqueue(sse(thoughtChunk("**Plan** "), thoughtChunk("think"), chunk("""{"say":"이렇게"""), chunk(""" 써 볼까요?","quickReplies":[],"readyToDraft":false}""")))
        val thoughts = mutableListOf<String>(); val says = mutableListOf<String>()
        val listener = object : TurnListener {
            override fun onToolStatus(text: String) {}
            override fun onPartialSay(text: String) { says += text }
            override fun onPartialThought(text: String) { thoughts += text }
        }
        val r = engine().runTurn(ctx(), listener) as TurnResult.Success
        assertEquals("이렇게 써 볼까요?", r.response.say)
        assertEquals("**Plan** think", r.thought)
        assertEquals(listOf("", "**Plan** ", "**Plan** think"), thoughts)   // 시도 시작의 "" 다음 누적
        assertFalse(says.any { it.contains("Plan") })
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"includeThoughts\":true"))
    }

    @Test
    fun thoughtsAccumulateAcrossToolRounds() = runTest {
        server.enqueue(sse(thoughtChunk("first"), callChunk))
        server.enqueue(sse(thoughtChunk("second"), chunk("""{"say":"끝","quickReplies":[],"readyToDraft":false}""")))
        val thoughts = mutableListOf<String>()
        val listener = object : TurnListener { override fun onToolStatus(text: String) {}; override fun onPartialSay(text: String) {}; override fun onPartialThought(text: String) { thoughts += text } }
        val r = engine().runTurn(ctx(), listener) as TurnResult.Success
        assertEquals("first\n\nsecond", r.thought)
        assertEquals("first\n\nsecond", thoughts.last())
    }
}
