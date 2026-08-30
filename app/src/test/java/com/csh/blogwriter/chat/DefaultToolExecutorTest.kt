package com.csh.blogwriter.chat

import com.csh.blogwriter.blog.BlogReader
import com.csh.blogwriter.blog.PostSummary
import com.csh.blogwriter.blog.PostText
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.research.PageText
import com.csh.blogwriter.research.ResearchTool
import com.csh.blogwriter.research.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultToolExecutorTest {
    private val research = object : ResearchTool {
        override suspend fun search(query: String) = listOf(SearchHit("원주 한우 맛집", "https://blog.naver.com/x/1", "요약"))
        override suspend fun openPage(url: String) = PageText("제목", "본문 텍스트")
    }
    private val added = mutableListOf<Pair<MemoryKind, String>>()
    private val memory = object : MemoryRepository {
        override fun observeAll() = flowOf(emptyList<MemoryItem>())
        override suspend fun activeItems(limit: Int) = emptyList<MemoryItem>()
        override suspend fun add(kind: MemoryKind, text: String, source: String) = MemoryItem(1, kind, text, source, 0, true, null).also { added += kind to text }
        override suspend fun update(id: Long, text: String) {}
        override suspend fun setEnabled(id: Long, enabled: Boolean) {}
        override suspend fun delete(id: Long) {}
        override suspend fun touch(ids: List<Long>) {}
    }
    private val researchOn = MutableStateFlow(true)
    private val blogIdFlow = MutableStateFlow<String?>("sampleblog")
    private val settings = object : SettingsStore {
        override val blogId: Flow<String?> get() = blogIdFlow
        override suspend fun setBlogId(id: String?) {}
        override val researchEnabled: Flow<Boolean> get() = researchOn
    }
    private val readCalls = mutableListOf<String>()
    private val blog = object : BlogReader {
        override suspend fun listPosts(blogId: String, count: Int) = listOf(PostSummary("1", "첫 글", 0, 1, 2, "요약", 3))
        override suspend fun readPost(blogId: String, logNo: String) = PostText(logNo, "글 $logNo", listOf("첫 문단", "[사진 2장]"), 2, 0).also { readCalls += logNo }
    }

    @Test
    fun searchReturnsHitsWithProgressAndLimits() = runTest {
        val ex = DefaultToolExecutor(research, memory, settings, blog)
        val progress = mutableListOf<String>()
        val r = ex.execute("web_search", buildJsonObject { put("query", "원주 한우") }) { progress += it }
        assertEquals("원주 한우 맛집", r["results"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertTrue(progress[0].contains("네이버에서 '원주 한우'"))
        repeat(7) { ex.execute("web_search", buildJsonObject { put("query", "b$it") }) {} }
        val ninth = ex.execute("web_search", buildJsonObject { put("query", "c") }) {}
        assertEquals("limit", ninth["error"]!!.jsonPrimitive.content)
        assertTrue(ninth["message"]!!.jsonPrimitive.content.contains("못 찾았다고"))
    }

    @Test
    fun rememberStoresAndReportsAndUnknownToolErrors() = runTest {
        val ex = DefaultToolExecutor(research, memory, settings, blog)
        val r = ex.execute("remember", buildJsonObject { put("kind", "PREFERENCE"); put("text", "가격은 정확히 적기") }) {}
        assertEquals(true, r["saved"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf(MemoryKind.PREFERENCE to "가격은 정확히 적기"), added)
        assertEquals("unknown tool", ex.execute("nope", buildJsonObject {}) {}["error"]!!.jsonPrimitive.content)
    }

    /** 빈 문장을 기억해 두면 "기억한 것들"에 빈 줄이 쌓이고 프롬프트 자리만 먹는다. */
    @Test
    fun rememberWithBlankTextSavesNothing() = runTest {
        val ex = DefaultToolExecutor(research, memory, settings, blog)
        val progress = mutableListOf<String>()
        val r = ex.execute("remember", buildJsonObject { put("kind", "PREFERENCE"); put("text", "   ") }) { progress += it }
        assertEquals("empty", r["error"]!!.jsonPrimitive.content)
        assertTrue(added.isEmpty())
        assertTrue(progress.isEmpty())
    }

    @Test
    fun researchDisabledSkipsToolsWithoutProgressOrCounter() = runTest {
        researchOn.value = false
        val ex = DefaultToolExecutor(research, memory, settings, blog)
        val progress = mutableListOf<String>()
        val search = ex.execute("web_search", buildJsonObject { put("query", "원주 한우") }) { progress += it }
        assertEquals("disabled", search["error"]!!.jsonPrimitive.content)
        val open = ex.execute("open_page", buildJsonObject { put("url", "https://blog.naver.com/x/1") }) { progress += it }
        assertEquals("disabled", open["error"]!!.jsonPrimitive.content)
        assertTrue(progress.isEmpty())

        // 꺼져 있던 동안의 시도는 횟수를 안 썼으므로 다시 켜면 2회 그대로 쓸 수 있다.
        researchOn.value = true
        ex.execute("web_search", buildJsonObject { put("query", "a") }) {}
        val second = ex.execute("web_search", buildJsonObject { put("query", "b") }) {}
        assertTrue(second["results"] != null)
    }

    @Test
    fun openPageOnlyAllowsUrlsFromThisTurnsSearch() = runTest {
        val ex = DefaultToolExecutor(research, memory, settings, blog)
        val notAllowed = ex.execute("open_page", buildJsonObject { put("url", "https://blog.naver.com/x/1") }) {}
        assertEquals("not_allowed", notAllowed["error"]!!.jsonPrimitive.content)

        ex.execute("web_search", buildJsonObject { put("query", "원주 한우") }) {}
        val allowed = ex.execute("open_page", buildJsonObject { put("url", "https://blog.naver.com/x/1") }) {}
        assertEquals("제목", allowed["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun openPageAllowsHitUrlEchoedWithTrailingSlashOrFragment() = runTest {
        val openedUrls = mutableListOf<String>()
        val capturingResearch = object : ResearchTool {
            override suspend fun search(query: String) = listOf(SearchHit("원주 한우 맛집", "https://blog.naver.com/x/1", "요약"))
            override suspend fun openPage(url: String): PageText? { openedUrls += url; return PageText("제목", "본문 텍스트") }
        }
        val ex = DefaultToolExecutor(capturingResearch, memory, settings, blog)
        ex.execute("web_search", buildJsonObject { put("query", "원주 한우") }) {}
        val echoed = "https://blog.naver.com/x/1/#review"
        val r = ex.execute("open_page", buildJsonObject { put("url", echoed) }) {}
        assertEquals("제목", r["title"]!!.jsonPrimitive.content)
        // 정규화는 허용 여부 판단에만 쓰고, research 에는 모델이 준 원래 url 을 그대로 넘긴다.
        assertEquals(listOf(echoed), openedUrls)
    }

    @Test
    fun cancellationIsNotSwallowed() = runTest {
        val cancellingResearch = object : ResearchTool {
            override suspend fun search(query: String): List<SearchHit> = throw CancellationException("cancelled")
            override suspend fun openPage(url: String): PageText? = null
        }
        val ex = DefaultToolExecutor(cancellingResearch, memory, settings, blog)
        var caught = false
        try {
            ex.execute("web_search", buildJsonObject { put("query", "x") }) {}
        } catch (e: CancellationException) {
            caught = true
        }
        assertTrue(caught)
    }

    @Test
    fun adviceToolsReturnPostsAndRespectLimits() = runTest {
        val ex = DefaultToolExecutor(research, memory, settings, blog)
        val list = ex.execute("list_my_posts", buildJsonObject {}) {}
        assertEquals("첫 글", list["posts"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("limit", ex.execute("list_my_posts", buildJsonObject {}) {}["error"]!!.jsonPrimitive.content)

        val progress = mutableListOf<String>()
        val post = ex.execute("read_my_post", buildJsonObject { put("logNo", "7") }) { progress += it }
        assertEquals("글 7", post["title"]!!.jsonPrimitive.content)
        assertTrue(post["text"]!!.jsonPrimitive.content.contains("첫 문단"))
        assertEquals(2, post["imageCount"]!!.jsonPrimitive.int)
        assertTrue(progress.any { it.contains("읽고 있어요") })
        ex.execute("read_my_post", buildJsonObject { put("logNo", "8") }) {}
        ex.execute("read_my_post", buildJsonObject { put("logNo", "9") }) {}
        assertEquals("limit", ex.execute("read_my_post", buildJsonObject { put("logNo", "10") }) {}["error"]!!.jsonPrimitive.content)
        assertEquals(listOf("7", "8", "9"), readCalls)
    }

    @Test
    fun adviceToolsWithoutLoginReportError() = runTest {
        blogIdFlow.value = null   // settings.blogId 의 MutableStateFlow
        val ex = DefaultToolExecutor(research, memory, settings, blog)
        assertEquals("not_logged_in", ex.execute("read_my_post", buildJsonObject { put("logNo", "7") }) {}["error"]!!.jsonPrimitive.content)
    }
}
