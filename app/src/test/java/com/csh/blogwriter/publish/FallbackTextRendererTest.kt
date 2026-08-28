package com.csh.blogwriter.publish

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.ListType
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackTextRendererTest {
    @Test
    fun rendersPlainTextWithImageMarkers() {
        val content = PostContent("제목", listOf(
            Block.Paragraph(listOf(Run("첫 ", bold = true), Run("문단"))),
            Block.Image("img_001"),
            Block.Paragraph(listOf(Run("항목")), list = ListType.BULLET),
            Block.Paragraph(listOf(Run("번호")), list = ListType.DECIMAL),
            Block.Quote("인용", "출처"),
            Block.Quote("출처 없음"),
            Block.Image("img_002"),
        ))
        val expected = """
            제목

            첫 문단

            [사진 1]

            • 항목
            1. 번호

            "인용" — 출처

            "출처 없음"

            [사진 2]
        """.trimIndent()
        assertEquals(expected, FallbackTextRenderer.render(content))
    }
}
