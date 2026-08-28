package com.csh.blogwriter.domain.publish

import com.csh.blogwriter.publish.PublishUrlParser

/**
 * 발행 흐름의 순수 상태 전이. 부수효과는 [PublishEffect] 로 돌려주고 ViewModel 이 실행한다.
 * 어떤 상태에서든 로그인 페이지로의 이동은 SessionExpired, 타임아웃/JS 오류는 Failed.
 */
class PublishStateMachine(
    private val totalImages: Int,
    private val expectedComponents: Int,
    private val blogId: String,
) {

    fun reduce(state: PublishState, event: PublishEvent): Transition {
        if (state.isTerminal) {
            return if (event is PublishEvent.Retry && state !is PublishState.Published) start() else Transition(state, emptyList())
        }
        return when (event) {
            is PublishEvent.Start, is PublishEvent.Retry -> start()
            is PublishEvent.UrlChanged -> onUrl(state, event.url, event.previousUrl)
            is PublishEvent.PageLoaded ->
                if (PublishUrlParser.isLoginPage(event.url)) expired()
                else if (state is PublishState.LoadingEditor) Transition(state, listOf(PublishEffect.StartReadyPolling))
                else Transition(state, emptyList())
            is PublishEvent.Timeout -> fail(event.stage, "제한 시간 초과")
            is PublishEvent.JsError -> fail(event.stage, event.message)
            is PublishEvent.ImagePrepared ->
                if (state is PublishState.PreparingImages) Transition(state.copy(done = event.done), emptyList()) else Transition(state, emptyList())
            is PublishEvent.ImagesPrepared ->
                if (state is PublishState.PreparingImages) Transition(PublishState.LoadingEditor, listOf(PublishEffect.LoadEditor)) else Transition(state, emptyList())
            is PublishEvent.EditorReady ->
                if (state is PublishState.LoadingEditor) Transition(PublishState.DismissingPopups, listOf(PublishEffect.DismissPopups)) else Transition(state, emptyList())
            is PublishEvent.PopupsDismissed ->
                if (state !is PublishState.DismissingPopups) Transition(state, emptyList())
                else if (totalImages == 0) inject()
                else Transition(PublishState.UploadingImages(0, totalImages), listOf(PublishEffect.UploadImages))
            is PublishEvent.ImageUploaded ->
                if (state !is PublishState.UploadingImages) Transition(state, emptyList())
                else if (state.done + 1 >= state.total) inject()
                else Transition(state.copy(done = state.done + 1), emptyList())
            is PublishEvent.ImageFailed -> fail(PublishStage.UPLOAD, "사진 업로드 실패 (${event.ref}): ${event.message}")
            is PublishEvent.Injected ->
                if (state !is PublishState.Injecting) Transition(state, emptyList())
                else if (event.componentCount != expectedComponents) fail(PublishStage.INJECT, "컴포넌트 수 불일치: 기대 $expectedComponents, 실제 ${event.componentCount}")
                else Transition(PublishState.Reviewing, listOf(PublishEffect.ShowEditor))
        }
    }

    private fun start() = Transition(PublishState.PreparingImages(0, totalImages), listOf(PublishEffect.PrepareImages))
    private fun inject() = Transition(PublishState.Injecting, listOf(PublishEffect.Inject))
    private fun expired() = Transition(PublishState.SessionExpired, listOf(PublishEffect.SavePending))
    private fun fail(stage: PublishStage, message: String) =
        Transition(PublishState.Failed(stage, message), listOf(PublishEffect.LogFailure(stage, message)))

    private fun onUrl(state: PublishState, url: String, previousUrl: String?): Transition {
        if (PublishUrlParser.isLoginPage(url)) return expired()
        if (state is PublishState.Reviewing) {
            val post = PublishUrlParser.parsePublished(url) ?: return Transition(state, emptyList())
            // `/{blogId}/{logNo}` 는 플랫폼의 모든 글이 가지는 주소다. 방금 발행한 글로 인정하려면
            // 내 블로그의 글이면서, 직전 주소가 글쓰기 화면이어야 한다.
            if (post.blogId != blogId) return Transition(state, emptyList())
            if (previousUrl == null || !PublishUrlParser.isWritePage(previousUrl)) return Transition(state, emptyList())
            return Transition(PublishState.Published(post.logNo, post.url), listOf(PublishEffect.SavePublished(post.logNo, post.url)))
        }
        return Transition(state, emptyList())
    }
}
