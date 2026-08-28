package com.csh.blogwriter.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test
    fun parsesTagWithVPrefix() {
        assertEquals(SemVer(1, 2, 3), SemVer.parse("v1.2.3"))
    }

    @Test
    fun parsesTagWithoutVPrefix() {
        assertEquals(SemVer(1, 2, 3), SemVer.parse("1.2.3"))
    }

    @Test
    fun malformedTagReturnsNull() {
        assertNull(SemVer.parse("not-a-version"))
        assertNull(SemVer.parse("v1.2"))
        assertNull(SemVer.parse("v1.2.3-rc1"))
        assertNull(SemVer.parse(""))
    }

    @Test
    fun newerVersionComparesGreater() {
        assertTrue(SemVer.parse("v0.2.0")!! > SemVer.parse("v0.1.0")!!)
        assertTrue(SemVer.parse("v1.0.0")!! > SemVer.parse("v0.9.9")!!)
        assertTrue(SemVer.parse("v1.2.4")!! > SemVer.parse("v1.2.3")!!)
    }

    @Test
    fun olderVersionComparesLess() {
        assertFalse(SemVer.parse("v0.1.0")!! > SemVer.parse("v0.2.0")!!)
    }

    @Test
    fun equalVersionsCompareEqual() {
        assertEquals(SemVer.parse("v1.2.3"), SemVer.parse("1.2.3"))
        assertFalse(SemVer.parse("v1.2.3")!! > SemVer.parse("v1.2.3")!!)
    }
}
