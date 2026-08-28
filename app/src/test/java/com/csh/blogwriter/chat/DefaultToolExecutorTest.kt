package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.research.PageText
import com.csh.blogwriter.research.ResearchTool
import com.csh.blogwriter.research.SearchHit
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
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

    @Test
    fun searchReturnsHitsWithProgressAndLimits() = runTest {
        val ex = DefaultToolExecutor(research, memory)
        val progress = mutableListOf<String>()
        val r = ex.execute("web_search", buildJsonObject { put("query", "원주 한우") }) { progress += it }
        assertEquals("원주 한우 맛집", r["results"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertTrue(progress[0].contains("네이버에서 '원주 한우'"))
        ex.execute("web_search", buildJsonObject { put("query", "b") }) {}
        val third = ex.execute("web_search", buildJsonObject { put("query", "c") }) {}
        assertEquals("limit", third["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun rememberStoresAndReportsAndUnknownToolErrors() = runTest {
        val ex = DefaultToolExecutor(research, memory)
        val r = ex.execute("remember", buildJsonObject { put("kind", "PREFERENCE"); put("text", "가격은 정확히 적기") }) {}
        assertEquals(true, r["saved"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf(MemoryKind.PREFERENCE to "가격은 정확히 적기"), added)
        assertEquals("unknown tool", ex.execute("nope", buildJsonObject {}) {}["error"]!!.jsonPrimitive.content)
    }
}
