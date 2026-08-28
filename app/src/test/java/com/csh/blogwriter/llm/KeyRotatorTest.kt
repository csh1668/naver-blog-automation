package com.csh.blogwriter.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyRotatorTest {
    private var now = 1_000_000L
    private fun rotator(keys: List<String> = listOf("k1", "k2", "k3"), models: List<String> = listOf("flash", "lite")) =
        KeyRotator(keys, models) { now }

    @Test
    fun roundRobinContinuesFromLastSuccess() {
        val r = rotator()
        val first = r.next()!!; assertEquals("k1" to "flash", first.keyId to first.model)
        r.report(first, KeyRotator.Outcome.SUCCESS)
        assertEquals("k2", r.next()!!.keyId)
    }

    @Test
    fun rateLimitedKeyCoolsDownAndModelDowngradesWhenAllKeysCool() {
        val r = rotator(keys = listOf("k1", "k2"))
        val p1 = r.next()!!; r.report(p1, KeyRotator.Outcome.RATE_LIMITED)
        val p2 = r.next()!!; assertEquals("k2", p2.keyId); assertEquals("flash", p2.model)
        r.report(p2, KeyRotator.Outcome.RATE_LIMITED)
        val p3 = r.next()!!; assertEquals("lite", p3.model)
        now += KeyRotator.KEY_COOLDOWN_MS + 1
        assertEquals("flash", r.next()!!.model)   // 키 쿨다운 해제 → primary 복귀
    }

    @Test
    fun modelCooldownKeepsFallbackUntilExpiry() {
        val r = rotator(keys = listOf("k1"))
        repeat(1) { val p = r.next()!!; r.report(p, KeyRotator.Outcome.RATE_LIMITED) }
        assertEquals("lite", r.next()!!.model)
        now += KeyRotator.KEY_COOLDOWN_MS + 1
        assertEquals("lite", r.next()!!.model)    // 모델 쿨다운(10분)은 아직
        now += KeyRotator.MODEL_COOLDOWN_MS
        assertEquals("flash", r.next()!!.model)
    }

    @Test
    fun invalidKeyIsDisabledAndAllExhaustedReturnsNullWithNextTime() {
        val r = rotator(keys = listOf("k1", "k2"), models = listOf("flash"))
        r.report(r.next()!!, KeyRotator.Outcome.INVALID_KEY)
        assertEquals(setOf("k1"), r.disabledKeys())
        r.report(r.next()!!, KeyRotator.Outcome.RATE_LIMITED)
        assertNull(r.next())
        assertEquals(now + KeyRotator.KEY_COOLDOWN_MS, r.nextAvailableAt())
    }

    @Test
    fun dailyCapDemotesKeyUntilReset() {
        val r = rotator(keys = listOf("k1", "k2"), models = listOf("flash"))
        repeat(KeyRotator.DAILY_CAP) { val p = r.next()!!; assertEquals("k1", p.keyId); r.report(p, KeyRotator.Outcome.SUCCESS); r.report(r.next()!!, KeyRotator.Outcome.SUCCESS) }
        // k1, k2 모두 20회 성공 → 둘 다 하루 상한 → 그래도 null 이 아니라 상한 키를 마지막 순위로 시도한다
        assertEquals("k1", r.next()!!.keyId)
    }
}
