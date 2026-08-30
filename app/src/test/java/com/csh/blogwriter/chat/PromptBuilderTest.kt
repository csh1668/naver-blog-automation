package com.csh.blogwriter.chat

import com.csh.blogwriter.blog.PostSummary
import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.SessionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private val texts = mapOf(
        PromptSection.ROLE to "역할 문안", PromptSection.AUDIENCE to "독자 문안",
        PromptSection.STYLE to "스타일: {{style}}", PromptSection.MEMORY to "기억:\n{{memory}}",
        PromptSection.STRUCTURE to "구조 문안 길이 {{minLen}}~{{maxLen}}자", PromptSection.CONVERSATION to "대화 문안",
        PromptSection.OUTPUT to "출력 문안", PromptSection.SELFCHECK to "점검 문안",
        PromptSection.ADVICE_ROLE to "조언 역할", PromptSection.ADVICE_GUARDS to "조언 규칙", PromptSection.ADVICE_OUTPUT to "조언 출력",
        PromptSection.FREE_ROLE to "자유 역할", PromptSection.FREE_MEMORY to "자유 기억",
    )
    private val store = object : PromptStore {
        override suspend fun text(section: PromptSection) = texts.getValue(section)
        override fun observe(section: PromptSection): Flow<String> = flowOf(texts.getValue(section))
        override suspend fun override(section: PromptSection, text: String?) {}
        override suspend fun isOverridden(section: PromptSection) = false
    }
    private fun mem(i: Int, kind: MemoryKind = MemoryKind.PREFERENCE) = MemoryItem(i.toLong(), kind, "항목$i", "chat", i.toLong(), true, null)

    @Test
    fun assemblesSectionsInOrderWithSubstitutions() = runTest {
        val s = PromptBuilder(store).system(memory = listOf(mem(1), mem(2, MemoryKind.EXPRESSION)), style = "존댓말", targetLength = 900..1400, draftTurn = false)
        val idx = listOf("역할 문안", "독자 문안", "스타일: 존댓말", "기억:", "- PREFERENCE: 항목1", "- EXPRESSION: 항목2", "구조 문안 길이 900~1400자", "대화 문안", "출력 문안").map { s.indexOf(it) }
        assertTrue(idx.all { it >= 0 })
        assertEquals(idx, idx.sorted())
        assertFalse(s.contains("점검 문안"))
    }

    /** STYLE 항목은 {{style}} 로 이미 들어간다 — 기억 목록에 또 넣으면 같은 문장이 두 번 실린다. */
    @Test
    fun styleMemoryIsNotRepeatedInTheMemoryList() = runTest {
        val s = PromptBuilder(store).system(
            memory = listOf(mem(1, MemoryKind.STYLE), mem(2)),
            style = "항목1", targetLength = 900..1400, draftTurn = false,
        )
        assertFalse(s.contains("- STYLE: 항목1"))
        assertTrue(s.contains("스타일: 항목1"))
        assertTrue(s.contains("- PREFERENCE: 항목2"))
    }

    @Test
    fun draftTurnAppendsSelfCheckAndCapsMemory() = runTest {
        val s = PromptBuilder(store).system(memory = (1..50).map { mem(it) }, style = null, targetLength = 900..1400, draftTurn = true)
        assertTrue(s.contains("점검 문안"))
        assertEquals(40, Regex("- PREFERENCE: 항목").findAll(s).count())
        assertTrue(s.contains("스타일: (아직 없음)"))
    }

    @Test
    fun adviceModeAssemblesAdviceSectionsOnly() = runTest {
        val s = PromptBuilder(store).system(memory = listOf(mem(1)), style = "존댓말", targetLength = 900..1400, draftTurn = false, mode = SessionMode.ADVICE)
        val idx = listOf("조언 역할", "스타일: 존댓말", "- PREFERENCE: 항목1", "조언 규칙", "조언 출력").map { s.indexOf(it) }
        assertTrue(idx.all { it >= 0 })
        assertEquals(idx, idx.sorted())
        listOf("역할 문안", "독자 문안", "구조 문안", "대화 문안", "출력 문안", "점검 문안").forEach { assertFalse(it, s.contains(it)) }
    }

    @Test
    fun writeModeDoesNotIncludeAdviceSections() = runTest {
        val s = PromptBuilder(store).system(memory = emptyList(), style = null, targetLength = 900..1400, draftTurn = false)
        assertFalse(s.contains("조언 역할"))
    }

    @Test
    fun freeModeAssemblesRoleMemoryAndSuggestionOnly() = runTest {
        val s = PromptBuilder(store).system(memory = listOf(mem(1)), style = "존댓말", targetLength = 900..1400, draftTurn = false, mode = SessionMode.FREE)
        val idx = listOf("자유 역할", "- PREFERENCE: 항목1", "자유 기억").map { s.indexOf(it) }
        assertTrue(idx.all { it >= 0 }); assertEquals(idx, idx.sorted())
        listOf("역할 문안", "독자 문안", "스타일:", "구조 문안", "대화 문안", "출력 문안", "점검 문안", "조언 역할").forEach { assertFalse(it, s.contains(it)) }
    }

    @Test
    fun postsSectionRendersTableOrFallback() {
        val b = PromptBuilder(store)
        val posts = listOf(PostSummary("100000000001", "원주 카페 늘봄", 1_787_989_202_986L, 2, 4, "쑥라떼가 달지 않았어요", 8))
        val table = b.postsSection(posts)
        assertTrue(table.startsWith("[최근 글 목록]"))
        assertTrue(table.contains("100000000001 | 원주 카페 늘봄 | 2026-08-29 | 댓글 2 | 공감 4 | 사진 8"))
        assertTrue(table.contains("쑥라떼가 달지 않았어요"))
        val none = b.postsSection(null)
        assertTrue(none.contains("목록 없음"))
        assertTrue(none.contains("read_my_post"))
    }
}
