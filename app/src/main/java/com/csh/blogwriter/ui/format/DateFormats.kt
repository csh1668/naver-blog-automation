package com.csh.blogwriter.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

object DateFormats {
    fun relative(epochMillis: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val diffMin = (now - epochMillis) / 60_000
        val t = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            diffMin < 1 -> "방금 전"
            diffMin < 60 -> "${diffMin}분 전"
            diffMin < 24 * 60 && t.toLocalDate() == today -> "${diffMin / 60}시간 전"
            t.toLocalDate() == today.minusDays(1) -> "어제 ${time(t)}"
            t.year == today.year -> "${t.monthValue}월 ${t.dayOfMonth}일 ${time(t)}"
            else -> "${t.year}년 ${t.monthValue}월 ${t.dayOfMonth}일"
        }
    }

    private fun time(t: ZonedDateTime): String {
        val h = t.hour
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = when (h % 12) { 0 -> 12; else -> h % 12 }
        return "$ampm $h12:${"%02d".format(t.minute)}"
    }
}
