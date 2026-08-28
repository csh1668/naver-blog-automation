package com.csh.blogwriter.llm

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit.SECONDS

class GeminiHttpTest {
    @Test
    fun configuresGeminiTimeoutsWithoutMutatingBase() {
        val base = OkHttpClient.Builder().readTimeout(5, SECONDS).build()
        val c = GeminiHttp.configure(base)

        assertEquals(15_000, c.connectTimeoutMillis)
        assertEquals(120_000, c.readTimeoutMillis)
        assertEquals(30_000, c.writeTimeoutMillis)
        assertEquals(180_000, c.callTimeoutMillis)

        assertEquals(5_000, base.readTimeoutMillis)
    }
}
