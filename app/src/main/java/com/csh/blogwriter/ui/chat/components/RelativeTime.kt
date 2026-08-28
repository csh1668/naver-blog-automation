package com.csh.blogwriter.ui.chat.components

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 대화 목록 캡션에 쓰는 짧은 상대 시각. 상태 라벨과 한 줄에 나란히 들어가므로
 * (`ui/format/DateFormats` 와 달리) 시:분은 붙이지 않는다.
 */
fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val diffMs = (now - epochMs).coerceAtLeast(0)
    val diffMin = diffMs / 60_000
    val diffHour = diffMs / 3_600_000
    if (diffMin < 1) return "방금"
    if (diffMin < 60) return "${diffMin}분 전"
    if (diffHour < 24) return "${diffHour}시간 전"

    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days <= 1 -> "어제"
        days < 7 -> "${days}일 전"
        date.year == today.year -> "${date.monthValue}월 ${date.dayOfMonth}일"
        else -> "${date.year}.${date.monthValue}.${date.dayOfMonth}"
    }
}
