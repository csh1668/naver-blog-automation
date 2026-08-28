package com.csh.blogwriter.ui.compose

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class TestPostBuilderTest {
    @Test
    fun splitsParagraphsOnBlankLinesAndInterleavesImages() {
        val content = TestPostBuilder.build("제목", "첫 문단\n둘째 줄\n\n두 번째 문단\n\n\n세 번째", imageCount = 2)
        assertEquals("제목", content.title)
        assertEquals(listOf(
            Block.Paragraph(listOf(Run("첫 문단\n둘째 줄"))),
            Block.Image("img_001"),
            Block.Paragraph(listOf(Run("두 번째 문단"))),
            Block.Image("img_002"),
            Block.Paragraph(listOf(Run("세 번째"))),
        ), content.blocks)
    }

    @Test
    fun extraImagesGoToTheEndAndEmptyBodyStillWorks() {
        val content = TestPostBuilder.build("t", "하나", imageCount = 3)
        assertEquals(listOf("paragraph", "image", "image", "image"), content.blocks.map { it.kind() })
        assertEquals(listOf(Block.Image("img_001")), TestPostBuilder.build("t", "  ", imageCount = 1).blocks)
    }

    private fun Block.kind() = when (this) { is Block.Paragraph -> "paragraph"; is Block.Image -> "image"; is Block.Quote -> "quote" }
}
