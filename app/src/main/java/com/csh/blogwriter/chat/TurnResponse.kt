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
    private val LITERAL_NEWLINE = """\n"""
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type"; coerceInputValues = true; isLenient = true }
    fun decode(text: String): TurnResponse {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "JSON 객체를 찾지 못했습니다" }
        val t = json.decodeFromString(TurnResponse.serializer(), text.substring(start, end + 1))
        // 모델이 줄바꿈을 이중으로 이스케이프해 보내는 일이 있다 — 채팅 글에 백슬래시 n 이 글자로 남을 일은 없으니 풀어 준다.
        return t.copy(say = unescapeNewlines(t.say), plan = t.plan?.let(::unescapeNewlines), question = t.question?.let(::unescapeNewlines))
    }
    private fun unescapeNewlines(text: String): String = text.replace(LITERAL_NEWLINE, "\n")
    fun encode(t: TurnResponse): String = json.encodeToString(TurnResponse.serializer(), t)
}
