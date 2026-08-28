package com.csh.blogwriter.domain.publish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishStateMachineTest {
    private val loginUrl = "https://nid.naver.com/nidlogin.login?url=x"
    private val publishedUrl = "https://blog.naver.com/PostView.naver?blogId=myblog&logNo=224000000001&isAfterWrite=true"
    private val writeUrl = "https://blog.naver.com/myblog?Redirect=Write&categoryNo=25"
    private val permalink = "https://blog.naver.com/myblog/224000000001"

    private fun machine(images: Int = 2, components: Int = 4, blogId: String = "myblog") =
        PublishStateMachine(totalImages = images, expectedComponents = components, blogId = blogId)

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
    fun tooFewComponentsFailsInjectStage() {
        val t = machine(images = 0, components = 3).run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded("u"), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.Injected(1),
        )
        assertEquals(PublishStage.INJECT, (t.last().state as PublishState.Failed).stage)
    }

    /** 에디터가 컴포넌트를 더 만들어 두는 경우가 있어, 기대보다 많으면 그대로 검토로 넘어간다. */
    @Test
    fun moreComponentsThanExpectedProceedsToReviewing() {
        val t = machine(images = 0, components = 3).run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded("u"), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.Injected(5),
        )
        assertEquals(PublishState.Reviewing, t.last().state)
        assertEquals(listOf(PublishEffect.ShowEditor), t.last().effects)
    }

    @Test
    fun reviewingIgnoresNonPublishedUrlsButLoginUrlExpires() {
        val m = machine(images = 0, components = 2)
        val reviewing = m.run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded("u"), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.Injected(2),
        ).last().state
        assertEquals(PublishState.Reviewing, reviewing)
        assertEquals(PublishState.Reviewing, m.reduce(reviewing, PublishEvent.UrlChanged(writeUrl)).state)
        // 로그인 이동은 어떤 상태에서든 세션 만료다.
        assertEquals(PublishState.SessionExpired, m.reduce(reviewing, PublishEvent.UrlChanged(loginUrl)).state)
    }

    /** 글쓰기 화면을 거친 작업에서만 내 글 주소를 발행으로 인정한다. 중간에 다른 주소를 거쳐도 유지된다. */
    @Test
    fun reviewingAcceptsOwnPostOnceWritePageWasSeen() {
        val m = machine(images = 0, components = 2)
        val reviewing = m.run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded(writeUrl), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.Injected(2),
        ).last().state
        assertEquals(PublishState.Reviewing, reviewing)

        // 사이에 관계없는 주소를 한 번 거쳐도 글쓰기 화면을 봤다는 사실은 남는다.
        assertEquals(reviewing, m.reduce(reviewing, PublishEvent.UrlChanged("https://blog.naver.com/PostList.naver?blogId=myblog")).state)

        val published = m.reduce(reviewing, PublishEvent.UrlChanged(permalink))
        assertEquals(PublishState.Published("224000000001", permalink), published.state)
        assertEquals(listOf(PublishEffect.SavePublished("224000000001", permalink)), published.effects)
    }

    /** 글쓰기 화면을 한 번도 보지 못했으면 내 글 주소라도 발행으로 보지 않는다. */
    @Test
    fun reviewingIgnoresOwnPostWhenWritePageWasNeverSeen() {
        val m = machine(images = 0, components = 2)
        val reviewing = m.run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded("https://blog.naver.com/myblog"),
            PublishEvent.EditorReady, PublishEvent.PopupsDismissed, PublishEvent.Injected(2),
        ).last().state
        assertEquals(PublishState.Reviewing, reviewing)
        assertEquals(reviewing, m.reduce(reviewing, PublishEvent.UrlChanged(permalink)).state)
    }

    /** 남의 글 주소는 글쓰기 화면을 거쳤더라도 무시한다. */
    @Test
    fun reviewingIgnoresOtherUsersPost() {
        val m = machine(images = 0, components = 2)
        val reviewing = m.run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded(writeUrl), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.Injected(2),
        ).last().state
        assertEquals(reviewing, m.reduce(reviewing, PublishEvent.UrlChanged("https://blog.naver.com/someoneelse/224000000001")).state)
    }

    /** 다시 시도하면 "글쓰기 화면을 봤다"는 기록도 초기화된다. */
    @Test
    fun writePageFlagResetsOnRetry() {
        val m = machine(images = 0, components = 2)
        m.run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded(writeUrl), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.Injected(2),
        )
        m.reduce(PublishState.Failed(PublishStage.INJECT, "x"), PublishEvent.Retry)
        assertEquals(PublishState.Reviewing, m.reduce(PublishState.Reviewing, PublishEvent.UrlChanged(permalink)).state)
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

    }

    /** 검토 중에는 사용자가 에디터를 직접 다루므로 JS 오류가 나도 성공한 흐름을 실패로 바꾸지 않는다. */
    @Test
    fun jsErrorWhileReviewingIsIgnored() {
        val m = machine(images = 0, components = 2)
        val reviewing = m.run(
            PublishEvent.Start, PublishEvent.ImagesPrepared, PublishEvent.PageLoaded(writeUrl), PublishEvent.EditorReady,
            PublishEvent.PopupsDismissed, PublishEvent.Injected(2),
        ).last().state
        assertEquals(PublishState.Reviewing, reviewing)
        val t = m.reduce(reviewing, PublishEvent.JsError(PublishStage.INJECT, "error"))
        assertEquals(PublishState.Reviewing, t.state)
        assertEquals(emptyList<PublishEffect>(), t.effects)
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
