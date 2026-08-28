package com.csh.blogwriter.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartialSayExtractorTest {
    @Test fun returnsPrefixWhileValueIsStillOpen() = assertEquals("안녕", PartialSayExtractor.extract("""{"say":"안녕"""))

    @Test fun decodesEscapedQuote() = assertEquals("안녕\"하세요", PartialSayExtractor.extract("""{"say":"안녕\"하세요"""))

    @Test fun stopsAtClosingQuote() = assertEquals("다 왔어요", PartialSayExtractor.extract("""{"say":"다 왔어요","plan":"""))

    @Test fun returnsNullBeforeSayAppears() {
        assertNull(PartialSayExtractor.extract("""{"plan":{"""))
        assertNull(PartialSayExtractor.extract("""{"say":"""))
    }

    @Test fun dropsIncompleteUnicodeEscape() = assertEquals("a", PartialSayExtractor.extract("""{"say":"a\u"""))

    @Test fun decodesCompleteUnicodeEscape() = assertEquals("aéb", PartialSayExtractor.extract("""{"say":"a\u00e9b"""))
}
