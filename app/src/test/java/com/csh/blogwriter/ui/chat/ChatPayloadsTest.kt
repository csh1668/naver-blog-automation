package com.csh.blogwriter.ui.chat

import com.csh.blogwriter.blog.PostSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    @Test fun assistantTextCarriesThoughtAndStaysReadable() {
        val p = ChatPayloads.assistantText("답", "생각 요약")
        assertEquals("답", ChatPayloads.readText(p)); assertEquals("생각 요약", ChatPayloads.readThought(p))
        val noThought = ChatPayloads.assistantText("답", null)
        assertEquals("답", ChatPayloads.readText(noThought)); assertNull(ChatPayloads.readThought(noThought))
        assertFalse(noThought.contains("thought"))
        assertNull(ChatPayloads.readThought(ChatPayloads.text("옛 메시지")))
    }
}
