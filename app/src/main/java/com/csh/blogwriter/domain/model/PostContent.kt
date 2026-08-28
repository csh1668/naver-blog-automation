package com.csh.blogwriter.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PostContent(val title: String, val blocks: List<Block>) {
    fun imageRefs(): List<String> = blocks.filterIsInstance<Block.Image>().map { it.ref }
}

@Serializable
sealed interface Block {
    @Serializable @SerialName("paragraph")
    data class Paragraph(val runs: List<Run>, val align: Align = Align.LEFT, val list: ListType? = null) : Block

    @Serializable @SerialName("image")
    data class Image(val ref: String) : Block

    @Serializable @SerialName("quote")
    data class Quote(val text: String, val source: String? = null) : Block
}

@Serializable
data class Run(
    val text: String,
    val bold: Boolean = false,
    val color: String? = null,
    val background: String? = null,
    val size: FontSize = FontSize.BODY,
)

/** 스마트에디터 fontSizeCode. 스파이크에서 검증된 값만 둔다. */
@Serializable
enum class FontSize(val code: String) { BODY("fs19"), TITLE("fs28") }

@Serializable enum class Align { LEFT, CENTER, RIGHT }
@Serializable enum class ListType { BULLET, DECIMAL }

object PostContentJson {
    val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(content: PostContent): String = json.encodeToString(PostContent.serializer(), content)
    fun decode(text: String): PostContent = json.decodeFromString(PostContent.serializer(), text)
}
