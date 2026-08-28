package com.csh.blogwriter.research

import android.util.Log

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

data class SearchHit(val title: String, val url: String, val snippet: String)
data class PageText(val title: String, val text: String)

data class SearchResult(val hits: List<SearchHit>, val summary: String)

interface ResearchTool {
    suspend fun search(query: String): List<SearchHit>
    /** 결과 목록에 더해 결과 페이지 자체의 본문 요약(플레이스 카드의 영업시간·주소 등)을 돌려준다. */
    suspend fun searchDetailed(query: String): SearchResult = SearchResult(search(query), "")
    suspend fun openPage(url: String): PageText?
}

/** 숨은 WebView 로 네이버 검색(0건이거나 타임아웃이면 구글로 폴백) 후 결과/본문을 돌려준다. */
@Singleton
class WebResearchTool @Inject constructor(@ApplicationContext private val context: Context) : ResearchTool {
    private val hidden by lazy { HiddenWebView(context) }
    @Volatile private var scriptCache: String? = null
    private val json = Json { ignoreUnknownKeys = true }

    /** 에셋 읽기는 디스크 I/O 라 IO 디스패처에서 하고 결과를 캐시한다. */
    private suspend fun script(): String = scriptCache ?: withContext(Dispatchers.IO) {
        context.assets.open("research_extract.js").bufferedReader().readText()
    }.also { scriptCache = it }

    override suspend fun search(query: String): List<SearchHit> = searchDetailed(query).hits

    override suspend fun searchDetailed(query: String): SearchResult {
        val q = Uri.encode(query)
        val started = System.currentTimeMillis()
        // 통합검색(nexearch): 플레이스 카드(영업시간·주소·전화)가 결과 페이지 요약에 바로 들어온다.
        val naver = extract("https://search.naver.com/search.naver?where=nexearch&query=$q", "__research.searchNaver()", 8_000)
        if (naver.hits.isNotEmpty() || naver.summary.length >= 200) {
            Log.d(TAG, "naver search hits=${naver.hits.size} summary=${naver.summary.length}c ${System.currentTimeMillis() - started}ms")
            return naver
        }
        val google = extract("https://www.google.com/search?hl=ko&q=$q", "__research.searchGoogle()", 8_000)
        Log.d(TAG, "google fallback hits=${google.hits.size} summary=${google.summary.length}c ${System.currentTimeMillis() - started}ms")
        return google
    }

    override suspend fun openPage(url: String): PageText? {
        // "httpx://…" 같은 것도 startsWith("http") 를 통과한다 — 스킴을 제대로 본다.
        val scheme = runCatching { java.net.URI(url).scheme }.getOrNull()?.lowercase()
        if (scheme !in setOf("http", "https")) return null
        val raw = hidden.loadAndExtract(url, "${script()}; __research.pageText()", 10_000) ?: return null
        val obj = runCatching { json.parseToJsonElement(unquote(raw)).jsonObject }.getOrNull() ?: return null
        return PageText(obj["title"]?.jsonPrimitive?.content.orEmpty(), obj["text"]?.jsonPrimitive?.content.orEmpty().take(4000))
    }

    private val empty = SearchResult(emptyList(), "")

    private suspend fun extract(url: String, call: String, timeout: Long): SearchResult {
        val raw = hidden.loadAndExtract(url, "${script()}; $call", timeout)
        if (raw == null) { Log.w(TAG, "search page timed out or failed: ${runCatching { java.net.URI(url).host }.getOrNull()}"); return empty }
        return runCatching {
            val obj = json.parseToJsonElement(unquote(raw)).jsonObject
            val hits = obj["hits"]?.jsonArray.orEmpty().map { it.jsonObject }
                .map { SearchHit(it["title"]!!.jsonPrimitive.content, it["url"]!!.jsonPrimitive.content, it["snippet"]?.jsonPrimitive?.content.orEmpty()) }
                .take(6)
            SearchResult(hits, obj["summary"]?.jsonPrimitive?.content.orEmpty().take(1800))
        }.getOrElse { e -> Log.w(TAG, "search extract parse failed: ${e.javaClass.simpleName}"); empty }
    }

    private companion object { const val TAG = "ResearchTool" }

    /** evaluateJavascript 는 문자열 결과를 JSON 문자열 리터럴로 돌려준다 → 한 겹 벗긴다. */
    private fun unquote(raw: String): String = runCatching { json.parseToJsonElement(raw).jsonPrimitive.content }.getOrDefault(raw)
}
