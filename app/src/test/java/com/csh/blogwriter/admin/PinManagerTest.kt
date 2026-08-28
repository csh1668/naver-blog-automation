package com.csh.blogwriter.admin

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.csh.blogwriter.data.prefs.DataStoreSettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PinManagerTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = 0L
    private fun manager() = PinManager(DataStoreSettingsStore(PreferenceDataStoreFactory.create { folder.newFile("s.preferences_pb") })) { now }

    @Test
    fun setVerifyAndLockout() = runTest {
        val m = manager()
        assertFalse(m.isSet())
        m.set("1234")
        assertTrue(m.isSet())
        assertEquals(PinManager.VerifyResult.OK, m.verify("1234"))
        repeat(4) { assertTrue(m.verify("0000") is PinManager.VerifyResult.WRONG) }
        val locked = m.verify("0000")
        assertTrue(locked is PinManager.VerifyResult.LOCKED)
        assertEquals(now + PinManager.LOCK_MS, (locked as PinManager.VerifyResult.LOCKED).untilMs)
        assertTrue(m.verify("1234") is PinManager.VerifyResult.LOCKED)
        now += PinManager.LOCK_MS + 1
        assertEquals(PinManager.VerifyResult.OK, m.verify("1234"))
    }

    @Test
    fun rejectsNonDigitsOrBadLength() = runTest {
        val m = manager()
        assertFalse(PinManager.isValidPin("12a4")); assertFalse(PinManager.isValidPin("123")); assertTrue(PinManager.isValidPin("123456"))
    }
}
