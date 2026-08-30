package com.csh.blogwriter.llm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun thoughtPartsAreSeparatedFromText() {
        val r = json.decodeFromString(GResponse.serializer(), """{"candidates":[{"content":{"role":"model","parts":[{"text":"**Plan** thinking","thought":true},{"text":"{\"say\":\"안녕\"}"}]}}]}""")
        assertEquals("{\"say\":\"안녕\"}", r.text)
        assertEquals("**Plan** thinking", r.thoughtText)
    }

    @Test fun noThoughtGivesNullThoughtText() {
        val r = json.decodeFromString(GResponse.serializer(), """{"candidates":[{"content":{"role":"model","parts":[{"text":"hi"}]}}]}""")
        assertEquals("hi", r.text); assertNull(r.thoughtText)
    }

    @Test fun thinkingConfigSerializesIncludeThoughts() {
        assertEquals("""{"thinkingLevel":"high","includeThoughts":true}""", Json.encodeToString(GThinkingConfig.serializer(), GThinkingConfig("high")))
    }
}
