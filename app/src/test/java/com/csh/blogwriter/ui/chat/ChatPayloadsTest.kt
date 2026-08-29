package com.csh.blogwriter.ui.chat

import com.csh.blogwriter.blog.PostSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatPayloadsTest {
    @Test fun blogPostsRoundTrip() {
        val posts = listOf(PostSummary("100000000001", "원주 카페 늘봄", 1_787_000_000_000L, 2, 4, "요약", 8))
        assertEquals(posts, ChatPayloads.readBlogPosts(ChatPayloads.blogPosts(posts)))
        assertNull(ChatPayloads.readBlogPosts("{}"))
    }
    @Test fun postViewRoundTrip() {
        val view = PostView("100000000001", "원주 카페 늘봄")
        assertEquals(view, ChatPayloads.readPostView(ChatPayloads.postView(view)))
        assertNull(ChatPayloads.readPostView("not json"))
    }
}
