package com.csh.blogwriter.domain.publish

import com.csh.blogwriter.domain.model.PostContent

enum class PublishStage { PREPARE, LOAD_EDITOR, DISMISS_POPUPS, UPLOAD, INJECT, REVIEW }

sealed interface PublishState {
    data object Idle : PublishState
    data class PreparingImages(val done: Int, val total: Int) : PublishState
    data object LoadingEditor : PublishState
    data object DismissingPopups : PublishState
    data class UploadingImages(val done: Int, val total: Int) : PublishState
    data object Injecting : PublishState
    data object Reviewing : PublishState
    data class Published(val logNo: String, val url: String) : PublishState
    data object SessionExpired : PublishState
    data class Failed(val stage: PublishStage, val message: String) : PublishState

    val isTerminal: Boolean get() = this is Published || this is SessionExpired || this is Failed
}

sealed interface PublishEvent {
    data object Start : PublishEvent
    data class ImagePrepared(val done: Int) : PublishEvent
    data object ImagesPrepared : PublishEvent
    data class PageLoaded(val url: String) : PublishEvent
    data object EditorReady : PublishEvent
    data object PopupsDismissed : PublishEvent
    data class ImageUploaded(val ref: String) : PublishEvent
    data class ImageFailed(val ref: String, val message: String) : PublishEvent
    data class Injected(val componentCount: Int) : PublishEvent
    data class UrlChanged(val url: String) : PublishEvent
    data class Timeout(val stage: PublishStage) : PublishEvent
    data class JsError(val stage: PublishStage, val message: String) : PublishEvent
    data object Retry : PublishEvent
    /** 채팅에서 글이 수정됐다 — 검토 중인 에디터에 새 내용을 다시 넣는다. */
    data class Reinject(val content: PostContent) : PublishEvent
}

sealed interface PublishEffect {
    data object PrepareImages : PublishEffect
    data object LoadEditor : PublishEffect
    data object StartReadyPolling : PublishEffect
    data object DismissPopups : PublishEffect
    data object UploadImages : PublishEffect
    data object Inject : PublishEffect
    data object ShowEditor : PublishEffect
    data class SavePublished(val logNo: String, val url: String) : PublishEffect
    data object SavePending : PublishEffect
    data class LogFailure(val stage: PublishStage, val message: String) : PublishEffect
}

data class Transition(val state: PublishState, val effects: List<PublishEffect>)
