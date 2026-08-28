package com.csh.blogwriter.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownLiteTest {

    private fun plain(block: MdBlock) = block.spans.joinToString("") { it.text }

    @Test
    fun readsHeadingLevels() {
        val blocks = parseMarkdownLite("# 제목\n## 글 구성\n### 잔가지")
        assertEquals(listOf(1, 2, 3), blocks.map { (it as MdBlock.Heading).level })
        assertEquals(listOf("제목", "글 구성", "잔가지"), blocks.map { plain(it) })
    }

    @Test
    fun readsBulletsWithBothMarkers() {
        val blocks = parseMarkdownLite("- 하나\n* 둘")
        assertEquals(2, blocks.count { it is MdBlock.Bullet })
        assertEquals(listOf("하나", "둘"), blocks.map { plain(it) })
    }

    @Test
    fun readsNumberedItemsKeepingTheirNumbers() {
        val blocks = parseMarkdownLite("1. 도입 — 왜 갔는지 (사진 img_001)\n2. 본론")
        assertEquals(listOf(1, 2), blocks.map { (it as MdBlock.Numbered).number })
        assertEquals("도입 — 왜 갔는지 (사진 img_001)", plain(blocks[0]))
    }

    @Test
    fun readsBoldSpans() {
        val spans = parseMarkdownLite("앞 **굵게** 뒤").single().spans
        assertEquals(listOf("앞 " to false, "굵게" to true, " 뒤" to false), spans.map { it.text to it.bold })
    }

    @Test
    fun blankLineSeparatesParagraphsAndAdjacentLinesStayTogether() {
        val blocks = parseMarkdownLite("첫 줄\n이어지는 줄\n\n다음 문단")
        assertEquals(2, blocks.size)
        assertEquals("첫 줄\n이어지는 줄", plain(blocks[0]))
        assertEquals("다음 문단", plain(blocks[1]))
    }

    @Test
    fun headingEndsTheParagraphBeforeIt() {
        val blocks = parseMarkdownLite("다른 제목: A / B\n## 글 구성\n1. 도입")
        assertEquals(listOf<Class<*>>(MdBlock.Paragraph::class.java, MdBlock.Heading::class.java, MdBlock.Numbered::class.java), blocks.map { it.javaClass })
    }

    @Test
    fun unmatchedBoldMarkerStaysAsText() {
        assertEquals(listOf("**반쪽 굵게" to false), parseMarkdownLite("**반쪽 굵게").single().spans.map { it.text to it.bold })
    }

    /** 계획을 그 자리에서 고치려면 블록 → 마크다운 → 블록이 같은 것으로 돌아와야 한다. */
    @Test
    fun blocksSurviveASerializeAndParseRoundTrip() {
        val markdown = """
            # 원주 한우, 가족과 다녀온 날
            다른 제목: 원주 맛집 후기
            이어지는 줄

            ## 글 구성
            1. 도입 — 어떻게 가게 됐는지
            2. 본론 — **무엇을** 먹었는지

            ### 잔가지
            - 주차 팁
            - 웨이팅

            따뜻한 존댓말로 씁니다.
        """.trimIndent()
        val blocks = parseMarkdownLite(markdown)

        assertEquals(blocks, parseMarkdownLite(blocksToMarkdown(blocks)))
    }

    /** 편집 칸이 돌려준 글자를 담아도(굵게 표시 포함) 그대로 살아 돌아온다. */
    @Test
    fun editedTextGoesBackIntoTheSameKindOfBlock() {
        val blocks = parseMarkdownLite("# 옛 제목\n- 옛 항목")
        val edited = blocks.mapIndexed { i, b -> b.withText(if (i == 0) "새 제목" else "**새** 항목") }

        assertEquals("# 새 제목\n\n- **새** 항목", blocksToMarkdown(edited))
        assertEquals(edited.map { it.javaClass }, parseMarkdownLite(blocksToMarkdown(edited)).map { it.javaClass })
    }

    @Test
    fun parsesTheWholePlanFormat() {
        val blocks = parseMarkdownLite(
            """
            # 원주 한우, 가족과 다녀온 날
            다른 제목: 원주 맛집 후기 / 한우 먹은 날

            ## 글 구성
            1. 도입 — 어떻게 가게 됐는지 (사진 img_001)
            2. 본론 — 무엇을 먹었는지 (사진 img_002)

            ## 말투와 분위기
            따뜻한 존댓말로 씁니다.
            """.trimIndent()
        )
        assertEquals(
            listOf<Class<*>>(
                MdBlock.Heading::class.java, MdBlock.Paragraph::class.java,
                MdBlock.Heading::class.java, MdBlock.Numbered::class.java, MdBlock.Numbered::class.java,
                MdBlock.Heading::class.java, MdBlock.Paragraph::class.java,
            ),
            blocks.map { it.javaClass },
        )
        assertEquals("원주 한우, 가족과 다녀온 날", plain(blocks[0]))
        assertEquals("따뜻한 존댓말로 씁니다.", plain(blocks.last()))
    }
}
