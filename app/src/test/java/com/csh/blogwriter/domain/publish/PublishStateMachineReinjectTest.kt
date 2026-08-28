package com.csh.blogwriter.domain.publish

import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class PublishStateMachineReinjectTest {
    private val post = PostContent("t", listOf(Block.Paragraph(listOf(Run("x")))))

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
    }
}
