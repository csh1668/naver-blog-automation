package com.csh.blogwriter.chat

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent

object PostContentRepair {
    /** 뷰모델의 품질 게이트가 이 접두로 사진 보정을 골라낸다. */
    const val MISSING = "누락 사진 추가: "
    const val DUPLICATE = "중복 사진 제거: "

    data class Repaired(val content: PostContent, val fixes: List<String>)

    fun repair(post: PostContent, attachedRefs: List<String>): Repaired {
        val fixes = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun keep(ref: String): Boolean =
            if (ref !in attachedRefs) { fixes += "없는 사진 제거: $ref"; false }
            else if (!seen.add(ref)) { fixes += DUPLICATE + ref; false }
            else true
        val blocks = post.blocks.mapNotNull { b ->
            when (b) {
                is Block.Image -> if (keep(b.ref)) b else null
                // 그룹 안의 사진도 같은 규칙으로 거르고, 한 장만 남으면 단독 사진으로, 비면 없앤다.
                is Block.ImageGroup -> when (val refs = b.refs.filter { keep(it) }) {
                    emptyList<String>() -> null
                    else -> if (refs.size == 1) Block.Image(refs[0]) else b.copy(refs = refs)
                }
                else -> b
            }
        }.toMutableList()
        attachedRefs.filterNot { it in seen }.forEach { fixes += MISSING + it; blocks += Block.Image(it) }
        var title = post.title.trim()
        if (title.isEmpty()) {
            title = blocks.filterIsInstance<Block.Paragraph>().firstOrNull()?.runs?.joinToString("") { it.text }?.trim()?.take(30).orEmpty().ifEmpty { "새 글" }
            fixes += "제목 보정"
        }
        return Repaired(PostContent(title, blocks), fixes)
    }
}
