package com.csh.blogwriter.chat

import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.domain.model.PostContent

data class Attachment(val ref: String, val jpegBase64: String, val mimeType: String = "image/jpeg")

data class ChatContext(
    val history: List<ChatMessage>,
    val attachments: List<Attachment>,
    val style: String?,
    val draftTurn: Boolean,
    /** 지금 오른쪽에 걸려 있는 계획(마크다운). 있으면 "이 계획을 고쳐라"가 된다. */
    val currentPlan: String? = null,
    val currentPost: PostContent?,
)

sealed interface TurnResult {
    data class Success(val response: TurnResponse, val repairs: List<String>, val usedModel: String) : TurnResult
    data class Failure(val reason: Reason, val retryAt: Long? = null, val detail: String = "") : TurnResult
    enum class Reason { NO_KEY, RATE_LIMITED, NETWORK, SERVER, BAD_RESPONSE, OTHER }
}

/** 턴이 진행되는 동안 UI 로 흘려보내는 신호. 구현은 UI 스레드를 가정하지 않는다. */
interface TurnListener {
    /** 도구 실행 진행 문구("검색 중" 등). */
    fun onToolStatus(text: String)
    /**
     * 스트리밍 중인 `say` 의 **현재 전체 접두**(JSON 이스케이프 해제 완료).
     * 이어붙이지 말고 항상 **교체**해야 한다. 새 스트림(도구 라운드, 온도 0 재시도, 키/모델 로테이션)이
     * 시작될 때마다 보내는 빈 문자열("")은 "지금까지 보여 준 것을 지우라"는 뜻이다 — 값은 줄어들 수 있다.
     */
    fun onPartialSay(text: String)
}

interface TurnRunner {
    suspend fun runTurn(ctx: ChatContext, listener: TurnListener): TurnResult
}
