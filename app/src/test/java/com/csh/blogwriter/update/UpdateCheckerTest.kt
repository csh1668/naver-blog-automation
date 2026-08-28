package com.csh.blogwriter.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class UpdateCheckerTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .writeTimeout(300, TimeUnit.MILLISECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun checker() = GithubUpdateChecker(client, baseUrl = server.url("/").toString().removeSuffix("/"))

    @Test
    fun newerReleaseReturnsUpdateInfo() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"tag_name":"v1.2.3","html_url":"https://github.com/o/r/releases/tag/v1.2.3"}"""),
        )

        val result = checker().checkForUpdate(repo = "o/r", currentVersion = "1.0.0")

        assertEquals(UpdateInfo("v1.2.3", "https://github.com/o/r/releases/tag/v1.2.3"), result)
    }

    @Test
    fun sameVersionReturnsNull() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"tag_name":"v1.0.0","html_url":"https://github.com/o/r/releases/tag/v1.0.0"}"""),
        )

        val result = checker().checkForUpdate(repo = "o/r", currentVersion = "1.0.0")

        assertNull(result)
    }

    @Test
    fun notFoundReturnsNull() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = checker().checkForUpdate(repo = "o/r", currentVersion = "1.0.0")

        assertNull(result)
    }

    @Test
    fun timeoutReturnsNull() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = checker().checkForUpdate(repo = "o/r", currentVersion = "1.0.0")

        assertNull(result)
    }

    @Test
    fun sendsGithubAcceptHeaderAndPath() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"tag_name":"v1.0.0","html_url":"https://github.com/o/r/releases/tag/v1.0.0"}"""),
        )

        checker().checkForUpdate(repo = "o/r", currentVersion = "1.0.0")

        val recorded = server.takeRequest()
        assertEquals("application/vnd.github+json", recorded.getHeader("Accept"))
        assertEquals("/repos/o/r/releases/latest", recorded.path)
    }
}
