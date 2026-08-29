package com.csh.blogwriter.blog

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BlogReaderTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream("blog/$name")!!.bufferedReader().readText()

    @Test fun parsesPostListItems() {
        val posts = parsePostList(fixture("post-list.json"))!!
        assertEquals(3, posts.size)
        val first = posts[0]
        assertEquals("100000000001", first.logNo)
        assertTrue(first.title.startsWith("원주 단계동 맛집 봄들식당"))
        assertEquals(1787989202986L, first.addedAt)
        assertEquals(2, first.comments); assertEquals(4, first.likes); assertEquals(33, first.photoCount)
        assertTrue(first.brief.contains("봄들식당"))
    }

    @Test fun postListErrorBodyGivesNull() {
        assertNull(parsePostList(fixture("post-list-error.json")))
        assertNull(parsePostList("<html>403</html>"))
    }

    @Test fun parsesPostViewIntoLinesAndCounts() {
        val post = parsePostView(fixture("post-view.html"), "100000000001")!!
        assertEquals("100000000001", post.logNo)
        assertTrue(post.title.startsWith("원주 단계동 맛집 봄들식당"))
        assertEquals(4, post.imageCount)   // 단독 1장 + 콜라주 3장
        assertEquals(1, post.videoCount)
        val text = post.text()
        assertTrue(text.contains("> 한눈에 보기"))                       // 인용은 "> " 접두
        assertTrue(text.contains("[사진 1장]")); assertTrue(text.contains("[사진 3장]")); assertTrue(text.contains("[동영상]"))
        assertTrue(text.contains("주소: 강원 원주시 단계동 000-0"))         // 표는 "항목: 값"
        assertFalse(text.contains("\n\n"))                               // 빈 문단은 버린다
        assertTrue(text.indexOf("한눈에 보기") < text.indexOf("방문 계기")) // 순서 유지
    }

    @Test fun parsesPostViewNormalizesNonBreakingSpaces() {
        val html = "<div class=\"se-main-container\"><div class=\"se-component se-text\"><p class=\"se-text-paragraph\"><span>가 나</span></p></div></div>"
        val post = parsePostView(html, "1")!!
        assertEquals("가 나", post.text())
    }

    @Test fun postViewWithoutMainContainerGivesNull() {
        assertNull(parsePostView("<html><body><p>없음</p></body></html>", "1"))
    }

    @Test fun longBodyIsTruncated() {
        val long = PostText("1", "t", List(400) { "가".repeat(20) }, 0, 0)
        val text = long.text()
        assertTrue(text.endsWith("(이하 생략)"))
        assertTrue(text.length <= PostText.MAX_CHARS + 10)
    }

    @Test fun listPostsSendsRefererAndCookieAndCachesPost() = runTest {
        val server = MockWebServer().also { it.start() }
        server.enqueue(MockResponse().setBody(fixture("post-list.json")))
        server.enqueue(MockResponse().setBody(fixture("post-view.html")))
        val reader = NaverBlogReader(OkHttpClient(), { "NID_AUT=x; NID_SES=y" }, baseUrl = server.url("/").toString().trimEnd('/'))

        val posts = reader.listPosts("sampleblog")!!
        assertEquals(3, posts.size)
        val listReq = server.takeRequest()
        assertEquals("/api/blogs/sampleblog/post-list?categoryNo=0&itemCount=30&page=1", listReq.path)
        assertEquals("${server.url("/").toString().trimEnd('/')}/sampleblog", listReq.getHeader("Referer"))
        assertEquals("NID_AUT=x; NID_SES=y", listReq.getHeader("Cookie"))

        val post = reader.readPost("sampleblog", "100000000001")!!
        assertEquals("/PostView.naver?blogId=sampleblog&logNo=100000000001", server.takeRequest().path)
        // 같은 글은 다시 받지 않는다.
        assertSame(post, reader.readPost("sampleblog", "100000000001"))
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    /** 캐시는 10분만 산다 — 고친 글을 "다시 봐 줘" 하면 새로 받아야 한다. */
    @Test fun postCacheExpiresAfterTtl() = runTest {
        val server = MockWebServer().also { it.start() }
        repeat(2) { server.enqueue(MockResponse().setBody(fixture("post-view.html"))) }
        var now = 0L
        val reader = NaverBlogReader(OkHttpClient(), { null }, baseUrl = server.url("/").toString().trimEnd('/'), now = { now })

        assertNotNull(reader.readPost("sampleblog", "100000000001"))
        assertNotNull(reader.readPost("sampleblog", "100000000001"))
        assertEquals(1, server.requestCount)

        now += 11 * 60 * 1000L
        assertNotNull(reader.readPost("sampleblog", "100000000001"))
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test fun networkFailureGivesNull() = runTest {
        val server = MockWebServer().also { it.start() }
        server.enqueue(MockResponse().setResponseCode(403).setBody("{\"isSuccess\":false}"))
        val reader = NaverBlogReader(OkHttpClient(), { null }, baseUrl = server.url("/").toString().trimEnd('/'))
        assertNull(reader.listPosts("sampleblog"))
        server.shutdown()
    }
}
