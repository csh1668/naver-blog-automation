package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
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
}
