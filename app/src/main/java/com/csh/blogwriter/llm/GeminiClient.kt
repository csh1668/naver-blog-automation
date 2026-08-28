package com.csh.blogwriter.llm

import android.util.Log

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** `listModels` 로 키를 검증한 결과. LIMITED 도 실존하는 키라는 뜻이다(429 = 한도 초과일 뿐 키 자체는 유효). */
enum class KeyProbe { VALID, LIMITED }

/** Gemini REST (`v1beta` generateContent). 키는 헤더로만 보내고 어디에도 기록하지 않는다. */
class GeminiClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
) {
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun generate(apiKey: String, model: String, request: GRequest): GResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(GRequest.serializer(), request).toRequestBody(mediaType)
        val req = Request.Builder().url("$baseUrl/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey).header("Content-Type", "application/json").post(body).build()
        val text = execute(req)
        json.decodeFromString(GResponse.serializer(), text)
    }

    /** SSE 스트리밍. `data:` 줄마다 GResponse 로 디코드해 emit. 오류 청크(`{"error":…}`)는 예외로. */
    fun generateStream(apiKey: String, model: String, request: GRequest): Flow<GResponse> = flow {
        val body = json.encodeToString(GRequest.serializer(), request).toRequestBody(mediaType)
        val req = Request.Builder().url("$baseUrl/v1beta/models/$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey).header("Content-Type", "application/json").post(body).build()
        val call = http.newCall(req)
        val startedAt = System.currentTimeMillis()
        fun elapsed() = "${System.currentTimeMillis() - startedAt}ms"
        Log.d(TAG, "stream start model=$model bodyBytes=${body.contentLength()}")
        // 수집을 멈추면 소켓을 바로 끊는다. finally 만으로는 블로킹 read 에 묶여 다음 SSE 줄(최대 120초)까지 안 풀린다.
        val cancelOnStop = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { res ->
                Log.d(TAG, "stream response code=${res.code} protocol=${res.protocol} ${elapsed()}")
                if (!res.isSuccessful) throw parseError(res.code, res.body?.string().orEmpty())
                val source = res.body!!.source()
                var chunks = 0
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    if (payload.startsWith("{\"error\"")) throw parseError(500, payload)
                    if (chunks++ == 0) Log.d(TAG, "stream first chunk ${elapsed()}")
                    emit(json.decodeFromString(GResponse.serializer(), payload))
                }
                Log.d(TAG, "stream end chunks=$chunks ${elapsed()}")
            }
        } catch (e: IOException) {
            // 취소로 끊긴 소켓이면 네트워크 오류가 아니라 취소로 알린다.
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "stream io error ${e.javaClass.simpleName}: ${e.message} ${elapsed()}")
            throw GeminiException(0, null, "네트워크 오류", e)
        } finally {
            cancelOnStop.dispose()
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseError(httpCode: Int, text: String): GeminiException {
        val err = runCatching { json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject }.getOrNull()
        val code = err?.get("code")?.jsonPrimitive?.content?.toIntOrNull() ?: httpCode
        Log.w(TAG, "api error http=$httpCode code=$code status=${err?.get("status")?.jsonPrimitive?.content} body=${text.take(300)}")
        return GeminiException(code, err?.get("status")?.jsonPrimitive?.content, err?.get("message")?.jsonPrimitive?.content ?: text.take(200))
    }

    private companion object {
        const val TAG = "GeminiClient"
        val EXCLUDED_KEYWORDS = listOf("image", "tts", "audio", "live", "omni", "embedding", "lyria", "deep-research")
        val MODEL_PRIORITY = listOf("gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite")
    }

    suspend fun listModels(apiKey: String): KeyProbe = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/v1beta/models").header("x-goog-api-key", apiKey).get().build()
        try { execute(req); KeyProbe.VALID } catch (e: GeminiException) { if (e.kind == GeminiException.Kind.RATE_LIMITED) KeyProbe.LIMITED else throw e }
    }

    /** `generateContent` 를 지원하는 gemini-* 텍스트 모델 이름 목록(`models/` 접두사 제거). 추천 모델을 앞세우고 나머지는 이름 역순. */
    suspend fun modelNames(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val infos = mutableListOf<GModelInfo>()
        var pageToken: String? = null
        do {
            val urlBuilder = "$baseUrl/v1beta/models".toHttpUrl().newBuilder().addQueryParameter("pageSize", "200")
            pageToken?.let { urlBuilder.addQueryParameter("pageToken", it) }
            val req = Request.Builder().url(urlBuilder.build()).header("x-goog-api-key", apiKey).get().build()
            val page = json.decodeFromString(GModelsListResponse.serializer(), execute(req))
            infos += page.models
            pageToken = page.nextPageToken
        } while (pageToken != null)

        val names = infos
            .filter { "generateContent" in it.supportedGenerationMethods }
            .map { it.name.removePrefix("models/") }
            .distinct()
            .filter { it.startsWith("gemini-") && EXCLUDED_KEYWORDS.none { kw -> it.contains(kw) } }

        val (preferred, rest) = names.partition { it in MODEL_PRIORITY }
        preferred.sortedBy { MODEL_PRIORITY.indexOf(it) } + rest.sortedDescending()
    }

    private fun execute(req: Request): String {
        val response = try { http.newCall(req).execute() } catch (e: IOException) { throw GeminiException(0, null, "네트워크 오류", e) }
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.isSuccessful) return text
            throw parseError(it.code, text)
        }
    }
}
