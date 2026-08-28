package com.csh.blogwriter.research

import org.junit.Assert.assertEquals
import org.junit.Test

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
