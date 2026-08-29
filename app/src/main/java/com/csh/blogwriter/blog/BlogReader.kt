package com.csh.blogwriter.blog

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 로그인 WebView 의 쿠키를 OkHttp 요청에 실어 주는 통로 — 이웃공개 글도 보이게. 테스트에선 람다. */
fun interface CookieSource { fun cookieHeader(url: String): String? }

interface BlogReader {
    suspend fun listPosts(blogId: String, count: Int = 30): List<PostSummary>?
    suspend fun readPost(blogId: String, logNo: String): PostText?
}

/**
 * 모바일 블로그는 목록 API(JSON)와 글 페이지(서버 렌더링 HTML)를 로그인 없이 준다 — 스펙 §9.
 * 실패는 전부 null + Log.w. 본문은 같은 글을 턴마다 다시 받지 않도록 최근 [CACHE_SIZE]편을 기억한다.
 */
class NaverBlogReader(
    private val http: OkHttpClient,
    private val cookies: CookieSource,
    private val baseUrl: String = "https://m.blog.naver.com",
) : BlogReader {
    private val cache = object : LinkedHashMap<String, PostText>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PostText>?) = size > CACHE_SIZE
    }

    override suspend fun listPosts(blogId: String, count: Int): List<PostSummary>? =
        get("$baseUrl/api/blogs/$blogId/post-list?categoryNo=0&itemCount=$count&page=1", "https://m.blog.naver.com/$blogId")?.let(::parsePostList)

    override suspend fun readPost(blogId: String, logNo: String): PostText? {
        val key = "$blogId/$logNo"
        synchronized(cache) { cache[key] }?.let { return it }
        val html = get("$baseUrl/PostView.naver?blogId=$blogId&logNo=$logNo", "https://m.blog.naver.com/$blogId") ?: return null
        return parsePostView(html, logNo)?.also { synchronized(cache) { cache[key] = it } }
    }

    private suspend fun get(url: String, referer: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("Referer", referer).header("User-Agent", UA)
                .apply { cookies.cookieHeader(url)?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }.build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) { Log.w(TAG, "GET $url -> ${res.code}"); return@withContext null }
                res.body?.string()
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "GET $url failed", e); null }
    }

    companion object {
        private const val TAG = "BlogReader"
        private const val CACHE_SIZE = 20
        private const val UA = "Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** `post-list` 응답. `isSuccess` 가 아니면 null. 스파이크 §9 의 필드명 그대로. */
internal fun parsePostList(body: String): List<PostSummary>? = runCatching {
    val root = json.parseToJsonElement(body).jsonObject
    if (root["isSuccess"]?.jsonPrimitive?.booleanOrNull != true) return null
    root["result"]!!.jsonObject["items"]!!.jsonArray.map { e ->
        val o = e.jsonObject
        PostSummary(
            logNo = o["logNo"]!!.jsonPrimitive.content,
            title = o["titleWithInspectMessage"]?.jsonPrimitive?.content.orEmpty().trim(),
            addedAt = o["addDate"]?.jsonPrimitive?.long ?: 0L,
            comments = o["commentCnt"]?.jsonPrimitive?.int ?: 0,
            likes = o["sympathyCnt"]?.jsonPrimitive?.int ?: 0,
            brief = o["briefContents"]?.jsonPrimitive?.content.orEmpty().trim(),
            photoCount = o["thumbnailCount"]?.jsonPrimitive?.int ?: 0,
        )
    }
}.getOrNull()

/**
 * 모바일 PostView. `div.se-main-container` 바로 아래 `div.se-component` 를 순서대로 훑는다:
 * se-text/se-quotation → 문단(인용은 "> "), se-table → "항목: 값" 줄, 사진/동영상은 개수만.
 */
internal fun parsePostView(html: String, logNo: String): PostText? = runCatching {
    val doc = Jsoup.parse(html)
    val main = doc.selectFirst("div.se-main-container") ?: return null
    val title = doc.selectFirst(".se-title-text")?.text()?.trim().orEmpty()
    val lines = mutableListOf<String>()
    var images = 0
    var videos = 0
    for (comp in main.children().filter { it.hasClass("se-component") }) {
        when {
            comp.hasClass("se-quotation") -> comp.paragraphs().forEach { lines += "> $it" }
            comp.hasClass("se-text") -> lines += comp.paragraphs()
            comp.hasClass("se-table") -> comp.select("tr").forEach { tr ->
                val cells = tr.select("td, th").map { it.text().trim() }.filter { it.isNotEmpty() }
                if (cells.isNotEmpty()) lines += if (cells.size >= 2) "${cells[0]}: ${cells.drop(1).joinToString(" / ")}" else cells[0]
            }
            comp.hasClass("se-image") || comp.hasClass("se-imageGroup") || comp.hasClass("se-imageStrip") -> {
                val n = comp.select("img").size.coerceAtLeast(1)
                images += n; lines += "[사진 ${n}장]"
            }
            comp.hasClass("se-video") -> { videos++; lines += "[동영상]" }
            // se-placesMap, se-horizontalLine, se-oglink 등은 조언에 필요 없다.
        }
    }
    PostText(logNo, title, lines, images, videos)
}.getOrNull()

private fun Element.paragraphs(): List<String> =
    select("p.se-text-paragraph").map { it.text().replace(' ', ' ').trim() }.filter { it.isNotEmpty() }
