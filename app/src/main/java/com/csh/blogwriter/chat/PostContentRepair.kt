package com.csh.blogwriter.chat

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent

object PostContentRepair {
    /** 뷰모델의 품질 게이트가 이 접두로 사진 보정을 골라낸다. */
    const val MISSING = "누락 사진 추가: "
    const val DUPLICATE = "중복 사진 제거: "
    const val GROUPED = "사진 묶음 적용: "

    data class Repaired(val content: PostContent, val fixes: List<String>)

    /**
     * @param userGroups 사용자가 직접 정한 사진 묶음. 모델이 무시하고 낱장으로 냈거나 다른 조합으로 묶었으면
     *   묶음의 첫 사진이 나오는 자리에 [Block.ImageGroup] 하나로 다시 세운다.
     */
    fun repair(post: PostContent, attachedRefs: List<String>, userGroups: List<List<String>> = emptyList()): Repaired {
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
        // 여기까지 오면 붙인 사진이 모두 정확히 한 번씩 들어 있다 — 그 위에 사용자 묶음을 다시 세운다.
        userGroups.map { group -> group.filter { it in attachedRefs }.distinct() }
            .filter { it.size >= 2 }
            .forEach { group ->
                if (blocks.any { it is Block.ImageGroup && it.refs == group }) return@forEach
                val rebuilt = applyGroup(blocks, group)
                if (rebuilt != null) { blocks.clear(); blocks += rebuilt; fixes += GROUPED + group.joinToString(",") }
            }
        var title = post.title.trim()
        if (title.isEmpty()) {
            title = blocks.filterIsInstance<Block.Paragraph>().firstOrNull()?.runs?.joinToString("") { it.text }?.trim()?.take(30).orEmpty().ifEmpty { "새 글" }
            fixes += "제목 보정"
        }
        return Repaired(PostContent(title, blocks), fixes)
    }

    /**
     * [group] 의 사진들을 다른 사진 블록에서 모두 빼고, 그중 처음 나오던 자리에 묶음 하나로 놓는다.
     * 묶음의 사진이 글에 하나도 없으면(있을 수 없지만) null.
     */
    private fun applyGroup(blocks: List<Block>, group: List<String>): List<Block>? {
        var placed = false
        val out = mutableListOf<Block>()
        blocks.forEach { b ->
            val refs = when (b) {
                is Block.Image -> listOf(b.ref)
                is Block.ImageGroup -> b.refs
                else -> emptyList()
            }
            if (refs.none { it in group }) { out += b; return@forEach }
            if (!placed) { out += Block.ImageGroup(group); placed = true }
            // 같은 블록에 묶음 밖 사진이 섞여 있었으면 그것들만 남겨 뒤에 둔다.
            when (val rest = refs.filterNot { it in group }) {
                emptyList<String>() -> {}
                else -> out += if (rest.size == 1) Block.Image(rest[0]) else (b as Block.ImageGroup).copy(refs = rest)
            }
        }
        return if (placed) out else null
    }
}
