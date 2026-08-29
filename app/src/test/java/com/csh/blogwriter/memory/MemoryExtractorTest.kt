package com.csh.blogwriter.memory

import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.data.repo.ChatRepository
import com.csh.blogwriter.data.repo.ChatSession
import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.data.repo.MessageKind
import com.csh.blogwriter.data.repo.MessageRole
import com.csh.blogwriter.data.repo.SessionMode
import com.csh.blogwriter.llm.ApiKey
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.llm.GeminiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [MemoryExtractor.onPublished] fire-and-forget 로 자기 스코프에 [kotlinx.coroutines.launch] 하고 곧바로 반환하므로,
 * 여기서는 실제(가상 시계가 아닌) 스코프를 주고 방금 뜬 자식 job 을 직접 join 해서 완료를 기다린다.
 */
class MemoryExtractorTest {
    private val server = MockWebServer()
    private lateinit var client: GeminiClient

    private var messages: List<ChatMessage> = emptyList()
    private val appended = mutableListOf<Triple<String, MessageKind, String>>()
    private val chatRepo = object : ChatRepository {
        override fun observeSessions(): Flow<List<ChatSession>> = flowOf(emptyList())
        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> = flowOf(emptyList())
        override suspend fun createSession(mode: SessionMode) = throw NotImplementedError()
        override suspend fun getSession(id: String): ChatSession? = null
        override suspend fun updateSession(session: ChatSession) {}
        override suspend fun setTitle(id: String, title: String) {}
        override suspend fun appendMessage(sessionId: String, role: MessageRole, kind: MessageKind, payloadJson: String): ChatMessage {
            appended += Triple(sessionId, kind, payloadJson)
            return ChatMessage(1, sessionId, 0, role, kind, payloadJson, 0)
        }
        override suspend fun messagesOnce(sessionId: String): List<ChatMessage> = messages
        override suspend fun deleteSession(id: String) {}
    }

    private val keys = MutableStateFlow(listOf(ApiKey("k1", "SECRET1", 0, lastOkAt = 1)))
    private val keyStore = object : ApiKeyStore {
        override val keys: Flow<List<ApiKey>> = this@MemoryExtractorTest.keys
        override val hasUsableKey: Flow<Boolean> = keys.map { l -> l.any { it.usable } }
        override suspend fun add(secrets: List<String>) = emptyList<ApiKey>()
        override suspend fun remove(id: String) {}
        override suspend fun markOk(id: String) {}
        override suspend fun markLimited(id: String) {}
        override suspend fun markInvalid(id: String) {}
        override suspend fun resetAll() {}
    }

    private val settings = object : SettingsStore {
        override val blogId: Flow<String?> = flowOf(null)
        override suspend fun setBlogId(id: String?) {}
    }

    private var existingTexts: List<String> = emptyList()
    private val addedItems = mutableListOf<Pair<MemoryKind, String>>()
    private val memory = object : MemoryRepository {
        override fun observeAll() = flowOf(emptyList<MemoryItem>())
        override suspend fun activeItems(limit: Int) = existingTexts.mapIndexed { i, t -> MemoryItem(i.toLong(), MemoryKind.STYLE, t, "chat", 0, true, null) }
        override suspend fun add(kind: MemoryKind, text: String, source: String): MemoryItem {
            addedItems += kind to text
            return MemoryItem(addedItems.size.toLong(), kind, text, source, 0, true, null)
        }
        override suspend fun update(id: Long, text: String) {}
        override suspend fun setEnabled(id: Long, enabled: Boolean) {}
        override suspend fun delete(id: Long) {}
        override suspend fun touch(ids: List<Long>) {}
    }

    @Before fun setUp() {
        server.start()
        client = GeminiClient(OkHttpClient(), server.url("/").toString().trimEnd('/'))
    }
    @After fun tearDown() = server.shutdown()

    private fun textMessage(role: MessageRole, text: String) =
        ChatMessage(0, "s1", 0, role, MessageKind.TEXT, Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject { put("text", text) }), 0)

    /** [MemoryExtractor.onPublished] 를 부르고, 그 안에서 뜬 백그라운드 job 이 끝날 때까지 실제로 기다린다. */
    private fun publish(sessionId: String = "s1", url: String = "https://blog.naver.com/x/1") = runBlocking {
        val root = SupervisorJob()
        val scope = CoroutineScope(root + Dispatchers.Default)
        MemoryExtractor(chatRepo, client, keyStore, settings, memory, scope).onPublished(sessionId, url)
        root.children.toList().forEach { it.join() }
    }

    @Test
    fun extractsAndAppendsSystemMessage() {
        messages = listOf(
            textMessage(MessageRole.USER, "원주에서 한우 먹은 얘기 써줘"),
            textMessage(MessageRole.ASSISTANT, "좋아요, 어떤 톤으로 쓸까요?"),
        )
        server.enqueue(MockResponse().setBody(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"{\"memories\":[{\"kind\":\"PREFERENCE\",\"text\":\"짧은 문장을 선호해요\"}]}"}]}}]}"""
        ))

        publish()

        assertEquals(listOf(MemoryKind.PREFERENCE to "짧은 문장을 선호해요"), addedItems)
        assertEquals(1, appended.size)
        val (sessionId, kind, payload) = appended[0]
        assertEquals("s1", sessionId)
        assertEquals(MessageKind.SYSTEM, kind)
        val text = Json.parseToJsonElement(payload).jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text, text.contains("이런 점을 기억해 둘게요"))
        assertTrue(text, text.contains("짧은 문장을 선호해요"))

        val req = server.takeRequest()
        assertEquals("SECRET1", req.getHeader("x-goog-api-key"))
        assertTrue(req.path!!, req.path!!.contains("gemini-3.5-flash-lite"))
        assertTrue(req.body.readUtf8().contains("responseJsonSchema"))
    }

    @Test
    fun dedupesAgainstExistingMemoryAndSkipsSystemMessageWhenNothingNew() {
        messages = listOf(textMessage(MessageRole.USER, "다음에도 이렇게 써줘"))
        existingTexts = listOf("짧은 문장을 선호해요")
        server.enqueue(MockResponse().setBody(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"{\"memories\":[{\"kind\":\"PREFERENCE\",\"text\":\"짧은 문장을 선호해요\"}]}"}]}}]}"""
        ))

        publish()

        assertTrue(addedItems.toString(), addedItems.isEmpty())
        assertTrue(appended.toString(), appended.isEmpty())
    }

    @Test
    fun capsAtThreeItemsEvenIfModelReturnsMore() {
        messages = listOf(textMessage(MessageRole.USER, "이거 써줘"))
        val memoriesJson = """{"memories":[{"kind":"FACT","text":"a"},{"kind":"FACT","text":"b"},{"kind":"FACT","text":"c"},{"kind":"FACT","text":"d"}]}"""
        val escaped = Json.encodeToString(kotlinx.serialization.serializer<String>(), memoriesJson)
        server.enqueue(MockResponse().setBody(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":$escaped}]}}]}"""
        ))

        publish()

        assertEquals(3, addedItems.size)
    }

    @Test
    fun noUsableKeySkipsNetworkCall() {
        keys.value = emptyList()
        messages = listOf(textMessage(MessageRole.USER, "이거 써줘"))

        publish()

        assertEquals(0, server.requestCount)
        assertTrue(appended.isEmpty())
    }

    @Test
    fun emptyConversationSkipsNetworkCall() {
        messages = emptyList()

        publish()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun networkFailureIsSwallowedSilently() {
        messages = listOf(textMessage(MessageRole.USER, "이거 써줘"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":500,"status":"INTERNAL","message":"x"}}"""))

        publish()

        assertTrue(appended.isEmpty())
        assertTrue(addedItems.isEmpty())
    }
}
