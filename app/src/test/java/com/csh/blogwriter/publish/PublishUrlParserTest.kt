package com.csh.blogwriter.publish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishUrlParserTest {
    private val published = "https://blog.naver.com/PostView.naver?blogId=myblog&Redirect=View&logNo=224000000001&categoryNo=25&isAfterWrite=true&isMrblogPost=false"

    @Test
    fun detectsLoginRedirect() {
        assertTrue(PublishUrlParser.isLoginPage("https://nid.naver.com/nidlogin.login?mode=form&url=https%3A%2F%2Fblog.naver.com"))
        assertFalse(PublishUrlParser.isLoginPage("https://blog.naver.com/myblog?Redirect=Write"))
    }

    @Test
    fun parsesPublishedUrl() {
        val post = PublishUrlParser.parsePublished(published)!!
        assertEquals("myblog", post.blogId)
        assertEquals("224000000001", post.logNo)
        assertEquals("https://blog.naver.com/myblog/224000000001", post.url)
    }

    @Test
    fun ignoresPostViewWithoutAfterWriteFlag() {
        assertNull(PublishUrlParser.parsePublished("https://blog.naver.com/PostView.naver?blogId=myblog&logNo=1"))
        assertNull(PublishUrlParser.parsePublished("https://blog.naver.com/myblog?Redirect=Write&categoryNo=25"))
        assertNull(PublishUrlParser.parsePublished("not a url"))
    }
}
