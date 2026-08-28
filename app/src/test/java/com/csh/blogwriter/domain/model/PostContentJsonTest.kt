package com.csh.blogwriter.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostContentJsonTest {
    private val sample = PostContent(
        title = "제목",
        blocks = listOf(
            Block.Paragraph(runs = listOf(Run("굵게", bold = true), Run(" 보통"))),
            Block.Image(ref = "img_001"),
            Block.Paragraph(runs = listOf(Run("가운데")), align = Align.CENTER, list = ListType.BULLET),
            Block.Quote(text = "인용", source = "출처"),
            Block.Quote(text = "출처 없음"),
        ),
    )

    @Test
    fun roundTripsThroughJson() {
        val json = PostContentJson.encode(sample)
        assertEquals(sample, PostContentJson.decode(json))
    }

    @Test
    fun usesStableTypeDiscriminators() {
        val json = PostContentJson.encode(sample)
        assertTrue(json.contains("\"type\":\"paragraph\""))
        assertTrue(json.contains("\"type\":\"image\""))
        assertTrue(json.contains("\"type\":\"quote\""))
    }

    @Test
    fun imageRefsAreListedInOrder() {
        assertEquals(listOf("img_001"), sample.imageRefs())
    }
}
