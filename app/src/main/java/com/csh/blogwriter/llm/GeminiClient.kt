package com.csh.blogwriter.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val response = try { http.newCall(req).execute() } catch (e: IOException) { throw GeminiException(0, null, "네트워크 오류", e) }
        response.use { res ->
            if (!res.isSuccessful) throw parseError(res.code, res.body?.string().orEmpty())
            val source = res.body!!.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                if (payload.startsWith("{\"error\"")) throw parseError(500, payload)
                emit(json.decodeFromString(GResponse.serializer(), payload))
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun parseError(httpCode: Int, text: String): GeminiException {
        val err = runCatching { json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject }.getOrNull()
        val code = err?.get("code")?.jsonPrimitive?.content?.toIntOrNull() ?: httpCode
        return GeminiException(code, err?.get("status")?.jsonPrimitive?.content, err?.get("message")?.jsonPrimitive?.content ?: text.take(200))
    }

    suspend fun listModels(apiKey: String): KeyProbe = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/v1beta/models").header("x-goog-api-key", apiKey).get().build()
        try { execute(req); KeyProbe.VALID } catch (e: GeminiException) { if (e.kind == GeminiException.Kind.RATE_LIMITED) KeyProbe.LIMITED else throw e }
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
