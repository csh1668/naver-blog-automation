package com.csh.blogwriter.publish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalImageInterceptorTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun buildsAndParsesUrls() {
        assertEquals("https://blog.naver.com/__app__/img_001.jpg", LocalImageInterceptor.urlFor("img_001"))
        assertEquals("img_001", LocalImageInterceptor.refFromUrl("https://blog.naver.com/__app__/img_001.jpg"))
        assertNull(LocalImageInterceptor.refFromUrl("https://blog.naver.com/myblog?Redirect=Write"))
    }

    @Test
    fun servesKnownFilesOnly() {
        val file = folder.newFile("img_001.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val interceptor = LocalImageInterceptor(mapOf("img_001" to file))
        val response = interceptor.intercept(LocalImageInterceptor.urlFor("img_001"))
        assertNotNull(response)
        assertEquals("image/jpeg", response!!.mimeType)
        assertEquals(3, response.data.readBytes().size)
        assertNull(interceptor.intercept(LocalImageInterceptor.urlFor("img_999")))
        assertNull(interceptor.intercept("https://blogfiles.pstatic.net/x.png"))
    }
}
