package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.domain.model.PostContent

data class Attachment(val ref: String, val jpegBase64: String, val mimeType: String = "image/jpeg")

data class ChatContext(
    val history: List<ChatMessage>,
    val attachments: List<Attachment>,
    val style: String?,
    val draftTurn: Boolean,
    val currentPost: PostContent?,
)

sealed interface TurnResult {
    data class Success(val response: TurnResponse, val repairs: List<String>, val usedModel: String) : TurnResult
    data class Failure(val reason: Reason, val retryAt: Long? = null, val detail: String = "") : TurnResult
    enum class Reason { NO_KEY, RATE_LIMITED, NETWORK, BAD_RESPONSE, OTHER }
}

/** 턴이 진행되는 동안 UI 로 흘려보내는 신호. 구현은 UI 스레드를 가정하지 않는다. */
interface TurnListener {
    /** 도구 실행 진행 문구("검색 중" 등). */
    fun onToolStatus(text: String)
    /** 스트리밍 중인 `say` 의 현재 접두(JSON 이스케이프 해제 완료). */
    fun onPartialSay(text: String)
}

interface TurnRunner {
    suspend fun runTurn(ctx: ChatContext, listener: TurnListener): TurnResult
}
