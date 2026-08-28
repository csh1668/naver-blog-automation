package com.csh.blogwriter.chat

import com.csh.blogwriter.domain.model.PostContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class OutlineItem(val heading: String, val summary: String, val photoRefs: List<String> = emptyList())
@Serializable data class Plan(val titleCandidates: List<String>, val outline: List<OutlineItem>, val tone: String)
@Serializable data class TurnResponse(
    val say: String,
    val plan: Plan? = null,
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
