package com.csh.blogwriter.domain.publish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishStateMachineTest {
    private val loginUrl = "https://nid.naver.com/nidlogin.login?url=x"
    private val publishedUrl = "https://blog.naver.com/PostView.naver?blogId=myblog&logNo=224000000001&isAfterWrite=true"

    private fun machine(images: Int = 2, components: Int = 4) = PublishStateMachine(totalImages = images, expectedComponents = components)

    private fun PublishStateMachine.run(vararg events: PublishEvent): List<Transition> {
        var state: PublishState = PublishState.Idle
        return events.map { e -> reduce(state, e).also { state = it.state } }
    }

    @Test
    fun happyPathWithImages() {
        val t = machine().run(
            PublishEvent.Start,
            PublishEvent.ImagePrepared(1), PublishEvent.ImagesPrepared,
            PublishEvent.PageLoaded("https://blog.naver.com/myblog?Redirect=Write&categoryNo=25"),
            PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed,
            PublishEvent.ImageUploaded("img_001"), PublishEvent.ImageUploaded("img_002"),
            PublishEvent.Injected(4),
            PublishEvent.UrlChanged(publishedUrl),
        )
        assertEquals(PublishState.PreparingImages(0, 2), t[0].state)
        assertEquals(listOf(PublishEffect.PrepareImages), t[0].effects)
        assertEquals(PublishState.PreparingImages(1, 2), t[1].state)
        assertEquals(PublishState.LoadingEditor, t[2].state)
        assertEquals(listOf(PublishEffect.LoadEditor), t[2].effects)
        assertEquals(PublishState.LoadingEditor, t[3].state)
        assertEquals(listOf(PublishEffect.StartReadyPolling), t[3].effects)
        assertEquals(PublishState.DismissingPopups, t[4].state)
        assertEquals(listOf(PublishEffect.DismissPopups), t[4].effects)
        assertEquals(PublishState.UploadingImages(0, 2), t[5].state)
        assertEquals(listOf(PublishEffect.UploadImages), t[5].effects)
        assertEquals(PublishState.UploadingImages(1, 2), t[6].state)
        assertEquals(PublishState.Injecting, t[7].state)
        assertEquals(listOf(PublishEffect.Inject), t[7].effects)
        assertEquals(PublishState.Reviewing, t[8].state)
        assertEquals(listOf(PublishEffect.ShowEditor), t[8].effects)
        assertEquals(PublishState.Published("224000000001", "https://blog.naver.com/myblog/224000000001"), t[9].state)
        assertEquals(listOf(PublishEffect.SavePublished("224000000001", "https://blog.naver.com/myblog/224000000001")), t[9].effects)
    }

    @Test
    fun noImagesSkipsUploadStage() {
        val t = machine(images = 0, components = 2).run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded("https://blog.naver.com/x?Redirect=Write"),
            PublishEvent.EditorReady, PublishEvent.PopupsDismissed,
        )
        assertEquals(PublishState.Injecting, t.last().state)
        assertEquals(listOf(PublishEffect.Inject), t.last().effects)
    }

    @Test
    fun loginRedirectBecomesSessionExpiredAndSavesPending() {
        val t = machine().run(PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.UrlChanged(loginUrl))
        assertEquals(PublishState.SessionExpired, t.last().state)
        assertEquals(listOf(PublishEffect.SavePending), t.last().effects)
    }

    @Test
    fun timeoutFailsWithStageAndLogs() {
        val t = machine().run(PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.Timeout(PublishStage.LOAD_EDITOR))
        val failed = t.last().state as PublishState.Failed
        assertEquals(PublishStage.LOAD_EDITOR, failed.stage)
        assertEquals(listOf(PublishEffect.LogFailure(PublishStage.LOAD_EDITOR, failed.message)), t.last().effects)
    }

    @Test
    fun imageFailureFailsUploadStage() {
        val t = machine().run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded("u"), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.ImageFailed("img_001", "SERVER_ERROR"),
        )
        val failed = t.last().state as PublishState.Failed
        assertEquals(PublishStage.UPLOAD, failed.stage)
        assertTrue(failed.message.contains("img_001"))
    }

    @Test
    fun wrongComponentCountFailsInjectStage() {
        val t = machine(images = 0, components = 3).run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded("u"), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.Injected(1),
        )
        assertEquals(PublishStage.INJECT, (t.last().state as PublishState.Failed).stage)
    }

    @Test
    fun reviewingIgnoresNonPublishedUrlsButLoginUrlExpires() {
        val m = machine(images = 0, components = 2)
        val reviewing = m.run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded("u"), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.Injected(2),
        ).last().state
        assertEquals(PublishState.Reviewing, reviewing)
        assertEquals(PublishState.Reviewing, m.reduce(reviewing, PublishEvent.UrlChanged("https://blog.naver.com/myblog?Redirect=Write&categoryNo=25")).state)
        assertEquals(PublishState.SessionExpired, m.reduce(reviewing, PublishEvent.UrlChanged(loginUrl)).state)
    }

    @Test
    fun jsErrorFailsWithStageAndLogs() {
        val t = machine().run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded("u"), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.JsError(PublishStage.DISMISS_POPUPS, "boom"),
        )
        val failed = t.last().state as PublishState.Failed
        assertEquals(PublishStage.DISMISS_POPUPS, failed.stage)
        assertEquals("boom", failed.message)
        assertEquals(listOf(PublishEffect.LogFailure(PublishStage.DISMISS_POPUPS, "boom")), t.last().effects)

        // JsError from Reviewing is NOT ignored
        val m = machine(images = 0, components = 2)
        val reviewing = m.run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded("u"), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.Injected(2),
        ).last().state
        assertEquals(PublishState.Reviewing, reviewing)
        val failedFromReview = m.reduce(reviewing, PublishEvent.JsError(PublishStage.INJECT, "error")).state as PublishState.Failed
        assertEquals(PublishStage.INJECT, failedFromReview.stage)
    }

    @Test
    fun retryFromSessionExpiredRestarts() {
        val m = machine()
        val t = m.reduce(PublishState.SessionExpired, PublishEvent.Retry)
        assertEquals(PublishState.PreparingImages(0, 2), t.state)
        assertEquals(listOf(PublishEffect.PrepareImages), t.effects)
    }

    @Test
    fun terminalStatesIgnoreFurtherEvents() {
        val m = machine()
        val failed = PublishState.Failed(PublishStage.UPLOAD, "x")
        assertEquals(Transition(failed, emptyList()), m.reduce(failed, PublishEvent.EditorReady))
        val published = PublishState.Published("1", "u")
        assertEquals(Transition(published, emptyList()), m.reduce(published, PublishEvent.UrlChanged(loginUrl)))
        // Retry on Published is ignored
        assertEquals(Transition(published, emptyList()), m.reduce(published, PublishEvent.Retry))
    }

    @Test
    fun retryFromFailedRestarts() {
        val t = machine().reduce(PublishState.Failed(PublishStage.UPLOAD, "x"), PublishEvent.Retry)
        assertEquals(PublishState.PreparingImages(0, 2), t.state)
        assertEquals(listOf(PublishEffect.PrepareImages), t.effects)
    }
}
