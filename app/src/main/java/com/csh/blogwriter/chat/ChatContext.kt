package com.csh.blogwriter.chat

import com.csh.blogwriter.blog.PostSummary
import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.data.repo.SessionMode
import com.csh.blogwriter.domain.model.PostContent

data class Attachment(val ref: String, val jpegBase64: String, val mimeType: String = "image/jpeg")

data class ChatContext(
    val history: List<ChatMessage>,
    val attachments: List<Attachment>,
    /** 사용자가 한 묶음으로 정한 사진들(ref 목록의 목록). 초안에서 imageGroup 하나로 나가야 한다. */
    val photoGroups: List<List<String>> = emptyList(),
    val style: String?,
    val draftTurn: Boolean,
    /** 지금 오른쪽에 걸려 있는 계획(마크다운). 있으면 "이 계획을 고쳐라"가 된다. */
    val currentPlan: String? = null,
    val currentPost: PostContent?,
    /** 계획을 내지 않고 되물은 횟수. [maxQuestionRounds] 에 닿으면 더 묻지 말고 계획을 내라고 한다. */
    val questionRounds: Int = 0,
    val maxQuestionRounds: Int = 4,
    val mode: SessionMode = SessionMode.WRITE,
    /** 조언 세션이 세션 시작 때 읽어 둔 최근 글 목록. null = 읽지 못함. */
    val blogPosts: List<PostSummary>? = null,
)

sealed interface TurnResult {
    data class Success(val response: TurnResponse, val repairs: List<String>, val usedModel: String, val thought: String? = null) : TurnResult
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
    /** 지금까지의 생각 요약 전체(교체). 새 시도(attempt)마다 "" 로 초기화. */
    fun onPartialThought(text: String) {}
    /** 조언 도구가 글 본문을 읽었을 때 — 오른쪽 패널을 그 글로 연다. */
    fun onPostRead(logNo: String, title: String) {}
}

interface TurnRunner {
    suspend fun runTurn(ctx: ChatContext, listener: TurnListener): TurnResult
}
