package com.csh.blogwriter.llm

/** 붙여넣은 텍스트에서 키 후보를 뽑는다. 접두 형태는 검사하지 않는다(발급 형식이 바뀐다). 유효성은 검증 호출로만 판단. */
object ApiKeyParser {
    private const val MIN_LENGTH = 20
    fun parse(text: String, existing: Set<String> = emptySet()): List<String> =
        text.split('\n', ',', ';', ' ', '\t')
            .map { it.trim().trim('"', '\'', '`').removePrefix("key=").removePrefix("KEY=").trim() }
            .filter { it.length >= MIN_LENGTH && it.none(Char::isWhitespace) }
            .distinct()
            .filterNot { it in existing }
}
