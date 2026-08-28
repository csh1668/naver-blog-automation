package com.csh.blogwriter.chat

/**
 * 스트리밍 중인 (아직 닫히지 않은) JSON 에서 `"say"` 값의 현재 접두를 뽑아 낸다.
 * 파서를 돌리지 않고 첫 `"say"` 키의 문자열 값만 훑으며 JSON 이스케이프를 해제한다.
 * 잘린 이스케이프(`\` 하나, 미완성 `\uXX`)는 버린다 — 다음 청크에서 완성되면 다시 나온다.
 */
object PartialSayExtractor {
    private const val KEY = "\"say\""

    fun extract(partialJson: String): String? {
        val start = valueStart(partialJson) ?: return null
        val sb = StringBuilder()
        var i = start
        val len = partialJson.length
        while (i < len) {
            val c = partialJson[i]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    val n = partialJson.getOrNull(i + 1) ?: return sb.toString()
                    if (n == 'u') {
                        val hex = partialJson.substring(minOf(i + 2, len), minOf(i + 6, len))
                        val code = if (hex.length == 4) hex.toIntOrNull(16) else null
                        if (code == null) return sb.toString()
                        sb.append(code.toChar()); i += 6
                    } else {
                        sb.append(when (n) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'; 'f' -> '\u000C'; else -> n })
                        i += 2
                    }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /** 첫 `"say"` 키 문자열 값의 여는 따옴표 다음 위치. 아직 도착하지 않았으면 null. */
    private fun valueStart(text: String): Int? {
        var from = 0
        while (true) {
            val k = text.indexOf(KEY, from)
            if (k < 0) return null
            from = k + KEY.length
            var i = from
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != ':') continue
            i++
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) return null
            if (text[i] == '"') return i + 1
        }
    }
}
