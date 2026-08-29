package com.csh.blogwriter.chat

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class PostContentRepairTest {
    @Test
    fun dropsUnknownRefsAppendsMissingAndDedupes() {
        val post = PostContent("제목", listOf(
            Block.Paragraph(listOf(Run("a"))), Block.Image("img_009"), Block.Image("img_001"), Block.Paragraph(listOf(Run("b"))), Block.Image("img_001"),
        ))
        val r = PostContentRepair.repair(post, attachedRefs = listOf("img_001", "img_002"))
        assertEquals(listOf("paragraph", "image:img_001", "paragraph", "image:img_002"), r.content.blocks.map { b -> when (b) { is Block.Image -> "image:${b.ref}"; is Block.Paragraph -> "paragraph"; is Block.Quote -> "quote"; is Block.Table -> "table"; is Block.ImageGroup -> "group" } })
        assertEquals(3, r.fixes.size)
    }

    @Test
    fun fillsEmptyTitleFromFirstParagraph() {
        val r = PostContentRepair.repair(PostContent("  ", listOf(Block.Paragraph(listOf(Run("오늘은 원주에 다녀왔어요. 정말 좋았답니다."))))), emptyList())
        assertEquals("오늘은 원주에 다녀왔어요. 정말 좋았답니다.".take(30), r.content.title)
    }

    @Test
    fun groupRefsCountAsUsedAndUnknownOnesAreDropped() {
        val post = PostContent("t", listOf(
            Block.ImageGroup(listOf("img_001", "img_009", "img_002")),
            Block.Image("img_002"),
        ))
        val r = PostContentRepair.repair(post, attachedRefs = listOf("img_001", "img_002", "img_003"))
        val group = r.content.blocks[0] as Block.ImageGroup
        assertEquals(listOf("img_001", "img_002"), group.refs)
        // 그룹에서 이미 쓴 img_002 는 중복 제거, 안 쓴 img_003 은 끝에 추가
        assertEquals(listOf("img_001", "img_002", "img_003"), r.content.imageRefs())
        assertEquals(3, r.fixes.size)
    }

    /** 모델이 낱장으로 흩어 놨어도 사용자가 묶어 둔 사진은 imageGroup 하나로 모은다. */
    @Test
    fun userGroupsAreForcedOntoSinglePhotos() {
        val post = PostContent("제목", listOf(
            Block.Image("img_001"), Block.Paragraph(listOf(Run("사이 글"))), Block.Image("img_002"), Block.Image("img_003"),
        ))
        val r = PostContentRepair.repair(post, listOf("img_001", "img_002", "img_003"), listOf(listOf("img_001", "img_002")))
        assertEquals(listOf("group", "paragraph", "image:img_003"), kinds(r.content))
        assertEquals(listOf("img_001", "img_002"), (r.content.blocks[0] as Block.ImageGroup).refs)
        assertEquals(listOf("img_001", "img_002", "img_003"), r.content.imageRefs())
        assertEquals(listOf(PostContentRepair.GROUPED + "img_001,img_002"), r.fixes)
    }

    /** 모델이 다른 조합으로 묶었으면 사용자 묶음대로 다시 짠다 — 남은 사진은 낱장으로 돌아간다. */
    @Test
    fun aDifferentGroupingIsRebuiltAsTheUserAskedFor() {
        val post = PostContent("제목", listOf(
            Block.ImageGroup(listOf("img_002", "img_003")), Block.Image("img_001"),
        ))
        val r = PostContentRepair.repair(post, listOf("img_001", "img_002", "img_003"), listOf(listOf("img_001", "img_002")))
        assertEquals(listOf("group", "image:img_003"), kinds(r.content))
        assertEquals(listOf("img_001", "img_002"), (r.content.blocks[0] as Block.ImageGroup).refs)
    }

    /** 이미 그대로 나온 묶음은 건드리지 않는다. */
    @Test
    fun anAlreadyCorrectGroupIsLeftAlone() {
        val post = PostContent("제목", listOf(Block.ImageGroup(listOf("img_001", "img_002"))))
        val r = PostContentRepair.repair(post, listOf("img_001", "img_002"), listOf(listOf("img_001", "img_002")))
        assertEquals(listOf("group"), kinds(r.content))
        assertEquals(emptyList<String>(), r.fixes)
    }

    private fun kinds(content: PostContent) = content.blocks.map { b ->
        when (b) {
            is Block.Image -> "image:${b.ref}"
            is Block.Paragraph -> "paragraph"
            is Block.Quote -> "quote"
            is Block.Table -> "table"
            is Block.ImageGroup -> "group"
        }
    }
}
