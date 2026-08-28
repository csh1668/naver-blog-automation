package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.data.repo.MessageKind
import com.csh.blogwriter.data.repo.MessageRole
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

    private var now = 0L
    private lateinit var engine: ConversationEngine

    @Before fun setUp() {
        server.start()
        val client = GeminiClient(OkHttpClient(), server.url("/").toString().trimEnd('/'))
        engine = ConversationEngine(
            client, keyStore, { k, m -> KeyRotator(k, m) { now } }, { ModelPolicy(listOf("flash", "lite")) },
            PromptBuilder(promptStore), memory, { toolOverride ?: tools },
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
    private val callChunk = """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"web_search","args":{"query":"원주 한우"}}}]}}]}"""
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
        // 503 이 계속되면: 같은 pick 재시도 1회 → flash 접음 → lite 재시도 → lite 도 접음 → SERVER 실패.
        repeat(12) { server.enqueue(error(503, "UNAVAILABLE")) }
        val r = engine.runTurn(ctx(), Recorder()) as TurnResult.Failure
        assertEquals(TurnResult.Reason.SERVER, r.reason)
        val paths = (0 until server.requestCount).map { server.takeRequest().path!! }
        assertTrue(paths.size in 3..4)
        assertTrue(paths.any { it.contains("lite:streamGenerateContent") })
    }

    @Test
    fun serverOverloadFallsBackToNextModel() = runTest {
        // 503 두 번(원래 + 무료 재시도) 뒤에는 키를 돌리지 않고 대체 모델로 내려간다.
        server.enqueue(error(503, "UNAVAILABLE"))
        server.enqueue(error(503, "UNAVAILABLE"))
        server.enqueue(textResponse("""{"say":"라이트로 갔어요","quickReplies":[],"readyToDraft":false}"""))
        val r = engine.runTurn(ctx(), Recorder()) as TurnResult.Success
        assertEquals("lite", r.usedModel)
        assertEquals(3, server.requestCount)
        assertTrue(server.takeRequest().path!!.contains("flash:streamGenerateContent"))
        assertTrue(server.takeRequest().path!!.contains("flash:streamGenerateContent"))
        assertTrue(server.takeRequest().path!!.contains("lite:streamGenerateContent"))
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
}
