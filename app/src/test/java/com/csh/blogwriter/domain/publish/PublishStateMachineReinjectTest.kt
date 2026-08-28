package com.csh.blogwriter.domain.publish

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class PublishStateMachineReinjectTest {
    private val post = PostContent("t", listOf(Block.Paragraph(listOf(Run("x")))))
    private val writeUrl = "https://blog.naver.com/myblog?Redirect=Write&categoryNo=25"
    private val permalink = "https://blog.naver.com/myblog/224000000001"

    @Test
    fun reinjectFromReviewingGoesBackToInjecting() {
        val m = PublishStateMachine(totalImages = 0, expectedComponents = 2, blogId = "b")
        val t = m.reduce(PublishState.Reviewing, PublishEvent.Reinject(post))
        assertEquals(PublishState.Injecting, t.state)
        assertEquals(listOf(PublishEffect.Inject), t.effects)
    }

    @Test
    fun reinjectIgnoredElsewhere() {
        val m = PublishStateMachine(0, 2, "b")
        assertEquals(PublishState.LoadingEditor, m.reduce(PublishState.LoadingEditor, PublishEvent.Reinject(post)).state)
        assertEquals(PublishState.Injecting, m.reduce(PublishState.Injecting, PublishEvent.Reinject(post)).state)
        assertEquals(PublishState.DismissingPopups, m.reduce(PublishState.DismissingPopups, PublishEvent.Reinject(post)).state)
    }

    @Test
    fun expectedComponentsCanBeUpdatedForTheNewContent() {
        val m = PublishStateMachine(0, 5, "myblog")
        // 새 글이 짧아져 컴포넌트가 2개면, 2개만 들어와도 성공이어야 한다.
        m.expectedComponents = 2
        assertEquals(PublishState.Reviewing, m.reduce(PublishState.Injecting, PublishEvent.Injected(2)).state)
    }

    /** 재주입 뒤에도 같은 기계를 쓰므로 "글쓰기 화면을 봤다"는 기록이 남아 발행을 감지한다. */
    @Test
    fun reinjectKeepsWritePageMemorySoPublishIsStillDetected() {
        val m = PublishStateMachine(0, 2, "myblog")
        m.reduce(PublishState.LoadingEditor, PublishEvent.PageLoaded(writeUrl))
        val injecting = m.reduce(PublishState.Reviewing, PublishEvent.Reinject(post)).state
        assertEquals(PublishState.Injecting, injecting)
        val reviewing = m.reduce(injecting, PublishEvent.Injected(2)).state
        assertEquals(PublishState.Reviewing, reviewing)

        val published = m.reduce(reviewing, PublishEvent.UrlChanged(permalink))
        assertEquals(PublishState.Published("224000000001", permalink), published.state)
        assertEquals(listOf(PublishEffect.SavePublished("224000000001", permalink)), published.effects)
    }
}
