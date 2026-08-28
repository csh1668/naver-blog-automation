package com.csh.blogwriter.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedNavigationTest {
    @Test
    fun allowsOrdinaryHttpPages() {
        assertFalse(blockedNavigation("https://search.naver.com/search.naver?query=a"))
        assertFalse(blockedNavigation("http://example.com/a"))
        // 인코딩 안 된 한글·공백이 섞인 리다이렉트도 정상 http(s) 면 통과한다.
        assertFalse(blockedNavigation("https://m.search.naver.com/search.naver?query=원주 한우 맛집&where=m"))
        assertFalse(blockedNavigation("https://example.com:8443/{a}|b"))
    }

    @Test
    fun blocksNonHttpSchemes() {
        assertTrue(blockedNavigation("intent://x#Intent;scheme=naversearchapp;end"))
        assertTrue(blockedNavigation("file:///sdcard/a.html"))
        assertTrue(blockedNavigation("javascript:alert(1)"))
        assertTrue(blockedNavigation("나쁜 주소"))
    }

    @Test
    fun blocksTheNaverLoginAndWriteScreens() {
        assertTrue(blockedNavigation("https://nid.naver.com/nidlogin.login?url=x"))
        assertTrue(blockedNavigation("https://blog.naver.com/myblog?Redirect=Write"))
    }
}

class DomainsTest {
    @Test
    fun collapsesSubdomainsToTheRegistrableDomain() {
        assertEquals("naver.com", registrableDomain("search.naver.com"))
        assertEquals("naver.com", registrableDomain("m.search.naver.com"))
        assertEquals("google.com", registrableDomain("www.google.com"))
        assertEquals("google.com", registrableDomain("consent.google.com"))
    }

    @Test
    fun keepsThreeLabelsForTwoLevelCountryCodes() {
        assertEquals("naver.co.kr", registrableDomain("blog.naver.co.kr"))
    }

    @Test
    fun leavesAnAlreadyBareDomainUnchanged() {
        assertEquals("naver.com", registrableDomain("naver.com"))
    }
}
