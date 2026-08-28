package com.csh.blogwriter.research

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

data class SearchHit(val title: String, val url: String, val snippet: String)
data class PageText(val title: String, val text: String)

interface ResearchTool {
    suspend fun search(query: String): List<SearchHit>
    suspend fun openPage(url: String): PageText?
}

/** 숨은 WebView 로 네이버 검색(0건이거나 타임아웃이면 구글로 폴백) 후 결과/본문을 돌려준다. */
@Singleton
class WebResearchTool @Inject constructor(@ApplicationContext private val context: Context) : ResearchTool {
    private val hidden by lazy { HiddenWebView(context) }
    private val script by lazy { context.assets.open("research_extract.js").bufferedReader().readText() }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String): List<SearchHit> {
        val q = Uri.encode(query)
        val naver = extract("https://search.naver.com/search.naver?where=view&query=$q", "__research.searchNaver()", 8_000)
        if (naver.isNotEmpty()) return naver
        return extract("https://www.google.com/search?hl=ko&q=$q", "__research.searchGoogle()", 8_000)
    }

    override suspend fun openPage(url: String): PageText? {
        if (!url.startsWith("http")) return null
        val raw = hidden.loadAndExtract(url, "$script; __research.pageText()", 10_000) ?: return null
        val obj = runCatching { json.parseToJsonElement(unquote(raw)).jsonObject }.getOrNull() ?: return null
        return PageText(obj["title"]?.jsonPrimitive?.content.orEmpty(), obj["text"]?.jsonPrimitive?.content.orEmpty().take(4000))
    }

    private suspend fun extract(url: String, call: String, timeout: Long): List<SearchHit> {
        val raw = hidden.loadAndExtract(url, "$script; $call", timeout) ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(unquote(raw)).jsonArray.map { it.jsonObject }.map { SearchHit(it["title"]!!.jsonPrimitive.content, it["url"]!!.jsonPrimitive.content, it["snippet"]?.jsonPrimitive?.content.orEmpty()) }
        }.getOrDefault(emptyList()).take(5)
    }

    /** evaluateJavascript 는 문자열 결과를 JSON 문자열 리터럴로 돌려준다 → 한 겹 벗긴다. */
    private fun unquote(raw: String): String = runCatching { json.parseToJsonElement(raw).jsonPrimitive.content }.getOrDefault(raw)
}
