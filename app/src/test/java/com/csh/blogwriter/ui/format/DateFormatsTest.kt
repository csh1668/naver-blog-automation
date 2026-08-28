package com.csh.blogwriter.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DateFormatsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun relativeAndAbsoluteForms() {
        val now = at(2026, 8, 28, 15, 30)
        assertEquals("방금 전", DateFormats.relative(at(2026, 8, 28, 15, 30), now, zone))
        assertEquals("5분 전", DateFormats.relative(at(2026, 8, 28, 15, 25), now, zone))
        assertEquals("2시간 전", DateFormats.relative(at(2026, 8, 28, 13, 30), now, zone))
        assertEquals("어제 오후 3:30", DateFormats.relative(at(2026, 8, 27, 15, 30), now, zone))
        assertEquals("8월 20일 오전 9:05", DateFormats.relative(at(2026, 8, 20, 9, 5), now, zone))
        assertEquals("2025년 12월 1일", DateFormats.relative(at(2025, 12, 1, 9, 5), now, zone))
    }
}
