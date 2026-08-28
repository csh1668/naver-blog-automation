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

    @Test fun ignoresSayInsideAnEarlierStringValue() {
        // 앞 문자열 값 안에 이스케이프 없는 "say":" 가 섞여 들어와도 그건 키가 아니다(앞글자가 a).
        assertEquals("진짜", PartialSayExtractor.extract("""{"note":"a "say":"가짜 b","say":"진짜"""))
        // 쉼표 뒤 공백/줄바꿈이 끼어도 키로 인정한다.
        assertEquals("ok", PartialSayExtractor.extract("{\"plan\":null ,\n \"say\":\"ok"))
    }

    @Test fun dropsIncompleteUnicodeEscape() = assertEquals("a", PartialSayExtractor.extract("""{"say":"a\u"""))

    @Test fun decodesCompleteUnicodeEscape() = assertEquals("aéb", PartialSayExtractor.extract("""{"say":"a\u00e9b"""))
}
