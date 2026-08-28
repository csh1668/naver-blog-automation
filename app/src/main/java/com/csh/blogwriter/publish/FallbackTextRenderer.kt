package com.csh.blogwriter.publish

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.ListType
import com.csh.blogwriter.domain.model.PostContent

/** 자동 입력 실패 시 클립보드에 넣을 서식 없는 텍스트. 연속 목록 문단은 한 덩어리, 나머지는 빈 줄로 구분. */
object FallbackTextRenderer {
    fun render(content: PostContent): String {
        val chunks = mutableListOf<String>()
        var imageNo = 0
        var decimalNo = 0
        var listBuffer: MutableList<String>? = null
        fun flushList() { listBuffer?.let { chunks += it.joinToString("\n") }; listBuffer = null; decimalNo = 0 }

        chunks += content.title
        for (block in content.blocks) {
            when (block) {
                is Block.Paragraph -> {
                    val text = block.runs.joinToString("") { it.text }
                    if (block.list == null) { flushList(); chunks += text }
                    else {
                        val prefix = if (block.list == ListType.BULLET) "• " else "${++decimalNo}. "
                        (listBuffer ?: mutableListOf<String>().also { listBuffer = it }) += prefix + text
                    }
                }
                is Block.Image -> { flushList(); chunks += "[사진 ${++imageNo}]" }
                is Block.Quote -> { flushList(); chunks += "\"${block.text}\"" + (block.source?.let { " — $it" } ?: "") }
            }
        }
        flushList()
        return chunks.joinToString("\n\n")
    }
}
