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
        fun good(r: SearchResult) = r.hits.isNotEmpty() || r.summary.length >= 200
        // 1) 네이버 모바일 통합검색: PC 페이지보다 훨씬 가볍고 플레이스 카드(영업시간·주소·전화)가 요약에 바로 들어온다.
        val mobile = extract("https://m.search.naver.com/search.naver?where=m&query=$q", "__research.searchNaver()", 15_000)
        if (good(mobile)) return mobile.also { Log.d(TAG, "naver(m) hits=${it.hits.size} summary=${it.summary.length}c ${System.currentTimeMillis() - started}ms") }
        // 2) 네이버 PC 통합검색
        val pc = extract("https://search.naver.com/search.naver?where=nexearch&query=$q", "__research.searchNaver()", 15_000)
        if (good(pc)) return pc.also { Log.d(TAG, "naver(pc) hits=${it.hits.size} summary=${it.summary.length}c ${System.currentTimeMillis() - started}ms") }
        // 3) 빙: 구글은 집 회선에서도 자동 트래픽으로 보고 보안문자 페이지를 내놓아 쓸 수 없다.
        val bing = extract("https://www.bing.com/search?setlang=ko&q=$q", "__research.searchBing()", 12_000)
        Log.d(TAG, "bing fallback hits=${bing.hits.size} summary=${bing.summary.length}c ${System.currentTimeMillis() - started}ms")
        return bing
    }

    /** 보안문자·차단 페이지는 결과가 아니다. */
    private fun looksBlocked(summary: String): Boolean =
        summary.contains("비정상적인 트래픽") || summary.contains("unusual traffic") || summary.contains("보안문자") || summary.contains("captcha", ignoreCase = true)

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
            val summary = obj["summary"]?.jsonPrimitive?.content.orEmpty().take(1800)
            if (looksBlocked(summary)) { Log.w(TAG, "search page looks blocked (captcha): ${runCatching { java.net.URI(url).host }.getOrNull()}"); empty }
            else SearchResult(hits, summary)
        }.getOrElse { e -> Log.w(TAG, "search extract parse failed: ${e.javaClass.simpleName}"); empty }
    }

    private companion object { const val TAG = "ResearchTool" }

    /** evaluateJavascript 는 문자열 결과를 JSON 문자열 리터럴로 돌려준다 → 한 겹 벗긴다. */
    private fun unquote(raw: String): String = runCatching { json.parseToJsonElement(raw).jsonPrimitive.content }.getOrDefault(raw)
}
