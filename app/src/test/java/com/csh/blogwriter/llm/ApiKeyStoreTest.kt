package com.csh.blogwriter.llm

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApiKeyStoreTest {
    @get:Rule val folder = TemporaryFolder()

    /** 테스트용: XOR 로 뒤집기만 하는 가짜 암호기 — 평문이 그대로 저장되지 않음을 확인하는 데 충분하다. */
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray) = blob.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
    private fun store(): ApiKeyStore = DataStoreApiKeyStore(PreferenceDataStoreFactory.create { folder.newFile("k.preferences_pb") }, cipher)

    @Test
    fun addsMasksAndRoundTripsThroughCipher() = runTest {
        val s = store()
        assertFalse(s.hasUsableKey.first())
        val added = s.add(listOf("AQ.Ab8RN6abcdefghijklmnopqrstu", "AQ.Ab8RN6abcdefghijklmnopqrstu", "AQ.Ab8RN6zzzzzzzzzzzzzzzzzzzzzzz"))
        assertEquals(2, added.size)
        assertEquals("…rstu", added[0].masked)
        assertEquals(listOf("AQ.Ab8RN6abcdefghijklmnopqrstu", "AQ.Ab8RN6zzzzzzzzzzzzzzzzzzzzzzz"), s.keysOnce().map { it.secret })
        assertFalse(s.hasUsableKey.first())          // 검증 전
        s.markOk(added[0].id)
        assertTrue(s.hasUsableKey.first())
        s.markInvalid(added[0].id)
        assertFalse(s.hasUsableKey.first())
        s.remove(added[1].id)
        assertEquals(1, s.keysOnce().size)
    }

    @Test
    fun storedBlobIsNotPlaintext() = runTest {
        val ds = PreferenceDataStoreFactory.create { folder.newFile("k2.preferences_pb") }
        val s = DataStoreApiKeyStore(ds, cipher)
        s.add(listOf("AQ.Ab8RN6abcdefghijklmnopqrstu"))
        val raw = ds.data.first().asMap().values.joinToString()
        assertFalse(raw.contains("AQ.Ab8RN6"))
    }
}
