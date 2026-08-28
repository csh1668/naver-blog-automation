package com.csh.blogwriter.chat

import com.csh.blogwriter.domain.model.PostContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class TurnResponse(
    val say: String,
    /** 글 계획 전문(마크다운). 피드백 턴마다 전체를 다시 낸다 — 부분 수정이 아니다. */
    val plan: String? = null,
    val question: String? = null,
    val quickReplies: List<String> = emptyList(),
    val readyToDraft: Boolean = false,
    val post: PostContent? = null,
)

object TurnResponseJson {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type"; coerceInputValues = true; isLenient = true }
    fun decode(text: String): TurnResponse {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "JSON 객체를 찾지 못했습니다" }
        return json.decodeFromString(TurnResponse.serializer(), text.substring(start, end + 1))
    }
    fun encode(t: TurnResponse): String = json.encodeToString(TurnResponse.serializer(), t)
}
