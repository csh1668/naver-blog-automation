package com.csh.blogwriter.ui.chat

import com.csh.blogwriter.ui.chat.components.relativeTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RelativeTimeTest {
    private val zone = ZoneId.systemDefault()
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun buckets() {
        val now = at(2026, 8, 28, 15, 30)

        assertEquals("방금", relativeTime(now - 30_000, now))
        assertEquals("5분 전", relativeTime(now - 5 * 60_000, now))
        assertEquals("59분 전", relativeTime(now - 59 * 60_000, now))
        assertEquals("3시간 전", relativeTime(now - 3 * 3_600_000, now))
        assertEquals("23시간 전", relativeTime(now - 23 * 3_600_000, now))
        assertEquals("어제", relativeTime(at(2026, 8, 27, 15, 30), now))
        assertEquals("3일 전", relativeTime(at(2026, 8, 25, 15, 30), now))
        assertEquals("8월 10일", relativeTime(at(2026, 8, 10, 9, 5), now))
        assertEquals("2025.12.1", relativeTime(at(2025, 12, 1, 9, 5), now))
    }
}
