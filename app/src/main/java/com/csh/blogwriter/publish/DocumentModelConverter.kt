package com.csh.blogwriter.publish

import com.csh.blogwriter.domain.model.Align
import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.ListType
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

/** PostContent → 스마트에디터 ONE documentModel (spike/findings.md §3). */
class DocumentModelConverter(private val idGenerator: () -> String = { "SE-" + UUID.randomUUID() }) {

    fun convert(content: PostContent, images: Map<String, UploadedImage>, documentId: String, version: String): JsonObject {
        var representAssigned = false
        val components = buildJsonArray {
            add(titleComponent(content.title))
            val pendingParagraphs = mutableListOf<Block.Paragraph>()
            fun flush() {
                if (pendingParagraphs.isNotEmpty()) { add(textComponent(pendingParagraphs.toList())); pendingParagraphs.clear() }
            }
            for (block in content.blocks) {
                when (block) {
                    is Block.Paragraph -> pendingParagraphs += block
                    is Block.Image -> {
                        flush()
                        val uploaded = requireNotNull(images[block.ref]) { "업로드 결과 없음: ${block.ref}" }
                        add(imageComponent(uploaded, represent = !representAssigned))
                        representAssigned = true
                    }
                    is Block.Quote -> { flush(); add(quoteComponent(block)) }
                    // TODO 스마트에디터 표(table) 컴포넌트 형식을 확인하면 진짜 표로 바꾼다. 그때까지는 "항목 : 값" 문단으로.
                    is Block.Table -> block.rows.filter { it.isNotEmpty() }.forEach { row ->
                        pendingParagraphs += Block.Paragraph(listOf(Run(row.joinToString(" : "))))
                    }
                }
            }
            flush()
        }
        return buildJsonObject {
            putJsonObject("document") {
                put("version", version); put("theme", "default"); put("language", "ko-KR"); put("id", documentId)
                put("components", components)
            }
            put("documentId", "")
        }
    }

    private fun titleComponent(title: String) = buildJsonObject {
        put("id", idGenerator()); put("layout", "default")
        put("title", buildJsonArray { add(plainParagraph(title)) })
        put("subTitle", null as String?); put("align", "left"); put("@ctype", "documentTitle")
    }

    private fun textComponent(paragraphs: List<Block.Paragraph>) = buildJsonObject {
        put("id", idGenerator()); put("layout", "default")
        put("value", buildJsonArray { paragraphs.forEach { add(paragraph(it)) } })
        put("@ctype", "text")
    }

    private fun paragraph(p: Block.Paragraph) = buildJsonObject {
        put("id", idGenerator())
        put("nodes", buildJsonArray { p.runs.forEach { add(textNode(it)) } })
        putJsonObject("style") {
            put("lineHeight", 1.7)
            if (p.align != Align.LEFT) put("align", p.align.name.lowercase())
            if (p.list != null) putJsonObject("list") {
                put("type", if (p.list == ListType.BULLET) "bullet" else "decimal"); put("level", 0); put("@ctype", "paragraphListStyle")
            }
            put("@ctype", "paragraphStyle")
        }
        put("@ctype", "paragraph")
    }

    private fun textNode(run: Run) = buildJsonObject {
        put("id", idGenerator()); put("value", run.text)
        putJsonObject("style") {
            put("fontFamily", "nanumsquare"); put("fontSizeCode", run.size.code)
            if (run.bold) put("bold", true)
            if (run.color != null) put("fontColor", run.color)
            if (run.background != null) put("backgroundColor", run.background)
            put("@ctype", "nodeStyle")
        }
        put("@ctype", "textNode")
    }

    private fun plainParagraph(text: String) = buildJsonObject {
        put("id", idGenerator())
        put("nodes", buildJsonArray { add(buildJsonObject { put("id", idGenerator()); put("value", text); put("@ctype", "textNode") }) })
        put("@ctype", "paragraph")
    }

    private fun quoteComponent(q: Block.Quote) = buildJsonObject {
        put("id", idGenerator()); put("layout", "default")
        put("value", buildJsonArray { add(plainParagraph(q.text)) })
        if (q.source != null) put("source", buildJsonArray { add(plainParagraph(q.source)) })
        put("@ctype", "quotation")
    }

    private fun imageComponent(img: UploadedImage, represent: Boolean): JsonObject {
        val width = min(693, img.width)
        val height = (img.height.toDouble() * width / img.width).roundToInt()
        return buildJsonObject {
            put("id", idGenerator()); put("layout", "default")
            put("src", "${img.domain}${img.url}?type=w1")
            put("internalResource", true); put("represent", represent)
            put("path", img.url); put("domain", img.domain)
            put("fileSize", img.fileSize)
            put("width", width); put("widthPercentage", 0); put("height", height)
            put("originalWidth", img.width); put("originalHeight", img.height)
            put("fileName", img.fileName)
            put("format", "normal"); put("displayFormat", "normal"); put("imageLoaded", true); put("contentMode", "fit")
            putJsonObject("origin") { put("srcFrom", "local"); put("@ctype", "imageOrigin") }
            put("ai", false); put("@ctype", "image")
        }
    }

    companion object {
        /** 제목 1 + (연속 문단은 하나의 text 컴포넌트) + 이미지/인용구 각 1. 주입 후 검증에 사용. */
        fun expectedComponentCount(content: PostContent): Int {
            var count = 1
            var inText = false
            for (block in content.blocks) {
                if (block is Block.Paragraph) { if (!inText) { count++; inText = true } }
                else { count++; inText = false }
            }
            return count
        }
    }
}
