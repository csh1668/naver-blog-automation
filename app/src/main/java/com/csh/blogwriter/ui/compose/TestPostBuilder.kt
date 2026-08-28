package com.csh.blogwriter.ui.compose

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run

/** 빈 줄로 문단을 나누고, 문단 사이에 사진을 하나씩 끼운다. 남는 사진은 끝에 붙인다. */
object TestPostBuilder {
    fun build(title: String, body: String, imageCount: Int): PostContent {
        val paragraphs = body.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val blocks = mutableListOf<Block>()
        var nextImage = 1
        fun image(): Block = Block.Image("img_%03d".format(nextImage++))
        paragraphs.forEach { p ->
            blocks += Block.Paragraph(listOf(Run(p)))
            if (nextImage <= imageCount) blocks += image()
        }
        while (nextImage <= imageCount) blocks += image()
        return PostContent(title.trim(), blocks)
    }
}
