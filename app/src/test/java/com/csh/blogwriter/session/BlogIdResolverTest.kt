package com.csh.blogwriter.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlogIdResolverTest {
    @Test
    fun extractsIdFromBlogHomeUrl() {
        assertEquals("myblog", BlogIdResolver.fromUrl("https://blog.naver.com/myblog"))
        assertEquals("my_blog-1", BlogIdResolver.fromUrl("https://blog.naver.com/my_blog-1/"))
        assertEquals("myblog", BlogIdResolver.fromUrl("https://blog.naver.com/myblog?tab=1"))
    }

    @Test
    fun rejectsNonHomeUrls() {
        assertNull(BlogIdResolver.fromUrl("https://blog.naver.com/MyBlog.naver"))
        assertNull(BlogIdResolver.fromUrl("https://blog.naver.com/PostView.naver?blogId=x"))
        assertNull(BlogIdResolver.fromUrl("https://nid.naver.com/nidlogin.login"))
        assertNull(BlogIdResolver.fromUrl("https://blog.naver.com/myblog/224000000001"))
    }
}
