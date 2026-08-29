package com.csh.blogwriter.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PostContent(val title: String, val blocks: List<Block>) {
    /** 단독 사진과 사진 그룹의 ref 를 글 순서대로. 업로드 개수·누락 검사가 이 목록을 쓴다. */
    fun imageRefs(): List<String> = blocks.flatMap { b ->
        when (b) {
            is Block.Image -> listOf(b.ref)
            is Block.ImageGroup -> b.refs
            else -> emptyList()
        }
    }
}

@Serializable
sealed interface Block {
    @Serializable @SerialName("paragraph")
    data class Paragraph(val runs: List<Run>, val align: Align = Align.LEFT, val list: ListType? = null) : Block

    @Serializable @SerialName("image")
    data class Image(val ref: String) : Block

    @Serializable @SerialName("quote")
    data class Quote(val text: String, val source: String? = null) : Block

    /** 2열 정보 표(가게 정보 등). rows = [["주소", "…"], ["전화", "…"]]. 첫 열은 항목명. */
    @Serializable @SerialName("table")
    data class Table(val rows: List<List<String>>) : Block

    /** 같은 대상 여러 컷을 한 컴포넌트로 묶는다. collage = 바둑판, slide = 넘겨 보기. */
    @Serializable @SerialName("imageGroup")
    data class ImageGroup(val refs: List<String>, val layout: GroupLayout = GroupLayout.COLLAGE) : Block
}

@Serializable enum class GroupLayout { COLLAGE, SLIDE }

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
