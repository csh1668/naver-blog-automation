package com.csh.blogwriter.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class GeminiClientTest {
    private val server = MockWebServer()
    private lateinit var client: GeminiClient

    @Before fun setUp() {
        server.start()
        client = GeminiClient(OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build(), server.url("/").toString().trimEnd('/'), Json { ignoreUnknownKeys = true })
    }
    @After fun tearDown() = server.shutdown()

    @Test
    fun sendsKeyHeaderAndParsesText() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"role":"model","parts":[{"text":"안녕하세요"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2,"totalTokenCount":5}}"""))
        val req = GRequest(contents = listOf(GContent("user", listOf(GPart(text = "hi")))))
        val res = client.generate("SECRET-KEY", "gemini-3.7-flash", req)
        assertEquals("안녕하세요", res.candidates[0].content!!.parts[0].text)
        assertEquals(5, res.usageMetadata!!.totalTokenCount)
        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.7-flash:generateContent", recorded.path)
        assertEquals("SECRET-KEY", recorded.getHeader("x-goog-api-key"))
        assertTrue(recorded.body.readUtf8().contains("\"text\":\"hi\""))
    }

    @Test
    fun parsesFunctionCallParts() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"web_search","args":{"query":"원주 한우"}}}]}}]}"""))
        val res = client.generate("k", "m", GRequest(contents = emptyList()))
        val call = res.candidates[0].content!!.parts[0].functionCall!!
        assertEquals("web_search", call.name)
        assertEquals("원주 한우", call.args["query"]!!.jsonPrimitive.content)
    }

    @Test
    fun mapsErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota"}}"""))
        try { client.generate("k", "m", GRequest(contents = emptyList())); fail() } catch (e: GeminiException) { assertEquals(GeminiException.Kind.RATE_LIMITED, e.kind) }
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"API key not valid. Please pass a valid API key."}}"""))
        try { client.generate("k", "m", GRequest(contents = emptyList())); fail() } catch (e: GeminiException) { assertEquals(GeminiException.Kind.INVALID_KEY, e.kind) }
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":503,"status":"UNAVAILABLE","message":"x"}}"""))
        try { client.generate("k", "m", GRequest(contents = emptyList())); fail() } catch (e: GeminiException) { assertEquals(GeminiException.Kind.SERVER, e.kind) }
    }

    @Test
    fun listModelsValidatesKey() = runTest {
        server.enqueue(MockResponse().setBody("""{"models":[]}"""))
        assertEquals(KeyProbe.VALID, client.listModels("k"))
        assertEquals("/v1beta/models", server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota"}}"""))
        assertEquals(KeyProbe.LIMITED, client.listModels("k"))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"status":"PERMISSION_DENIED","message":"denied"}}"""))
        try { client.listModels("k"); fail() } catch (e: GeminiException) { assertEquals(GeminiException.Kind.INVALID_KEY, e.kind) }
    }

    @Test
    fun streamsSseChunksInOrder() = runTest {
        val chunk1 = """{"candidates":[{"content":{"role":"model","parts":[{"text":"{\"say\":\"안녕"}]}}]}"""
        val chunk2 = """{"candidates":[{"content":{"role":"model","parts":[{"text":"하세요\"}"}]},"finishReason":"STOP"}],"usageMetadata":{"totalTokenCount":9}}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $chunk1\n\ndata: $chunk2\n\n"))
        val chunks = client.generateStream("k", "m", GRequest(contents = emptyList())).toList()
        assertEquals(2, chunks.size)
        assertEquals("{\"say\":\"안녕", chunks[0].text)
        assertEquals("STOP", chunks[1].candidates[0].finishReason)
        assertEquals("/v1beta/models/m:streamGenerateContent?alt=sse", server.takeRequest().path)
    }

    @Test
    fun streamHttpErrorThrows() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"q"}}"""))
        try { client.generateStream("k", "m", GRequest(contents = emptyList())).toList(); fail() } catch (e: GeminiException) { assertEquals(GeminiException.Kind.RATE_LIMITED, e.kind) }
    }

    @Test
    fun requestSerializesSchemaAndTools() {
        val req = GRequest(
            contents = listOf(GContent("user", listOf(GPart(text = "x")))),
            systemInstruction = GSystemInstruction(listOf(GPart(text = "sys"))),
            tools = listOf(GTool(listOf(GFunctionDeclaration("web_search", "검색", buildJsonObject { put("type", "object") })))),
            toolConfig = GToolConfig(GFunctionCallingConfig("AUTO")),
            generationConfig = GGenerationConfig(temperature = 0.7, responseMimeType = "application/json", responseJsonSchema = buildJsonObject { put("type", "object") }),
        )
        val text = Json { explicitNulls = false }.encodeToString(GRequest.serializer(), req)
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("application/json", obj["generationConfig"]!!.jsonObject["responseMimeType"]!!.jsonPrimitive.content)
        assertTrue(text.contains("\"functionDeclarations\""))
        assertTrue(!text.contains("\"inlineData\":null"))   // null 필드는 직렬화하지 않는다
    }
}
