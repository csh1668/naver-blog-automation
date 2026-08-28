package com.csh.blogwriter.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeyParserTest {
    @Test
    fun splitsOnNewlinesCommasSpacesAndStripsDecoration() {
        val text = """
            AQ.Ab8RN6abcdefghijklmnopqrstu, "AIzaSyD1234567890abcdefghijklmnop"
            key=AQ.Ab8RN6zzzzzzzzzzzzzzzzzzzzzzz ; short
            AQ.Ab8RN6abcdefghijklmnopqrstu
        """.trimIndent()
        assertEquals(
            listOf("AQ.Ab8RN6abcdefghijklmnopqrstu", "AIzaSyD1234567890abcdefghijklmnop", "AQ.Ab8RN6zzzzzzzzzzzzzzzzzzzzzzz"),
            ApiKeyParser.parse(text),
        )
    }

    @Test
    fun excludesAlreadyRegisteredAndBlank() {
        assertEquals(emptyList<String>(), ApiKeyParser.parse("   \n"))
        assertEquals(listOf("AQ.Ab8RN6zzzzzzzzzzzzzzzzzzzzzzz"), ApiKeyParser.parse("AQ.Ab8RN6abcdefghijklmnopqrstu\nAQ.Ab8RN6zzzzzzzzzzzzzzzzzzzzzzz", existing = setOf("AQ.Ab8RN6abcdefghijklmnopqrstu")))
    }
}
