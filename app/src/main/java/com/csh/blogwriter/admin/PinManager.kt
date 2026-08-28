package com.csh.blogwriter.admin

import com.csh.blogwriter.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject

/**
 * 관리자 PIN 해시·잠금 정책.
 * `pinHash` 는 SettingsStore 에 `"<saltHex>:<sha256Hex>"` 형태의 한 문자열로 저장된다(설정마다 랜덤 솔트).
 * 잠금 상태(실패 횟수, 잠금 해제 시각)는 프로세스 메모리에만 둔다.
 */
class PinManager(private val settings: SettingsStore, private val clock: () -> Long) {
    @Inject constructor(settings: SettingsStore) : this(settings, System::currentTimeMillis)

    sealed interface VerifyResult {
        data object OK : VerifyResult
        data class WRONG(val remaining: Int) : VerifyResult
        data class LOCKED(val untilMs: Long) : VerifyResult
    }

    companion object {
        const val MAX_FAILURES = 5
        const val LOCK_MS = 30_000L

        fun isValidPin(pin: String) = pin.length in 4..6 && pin.all(Char::isDigit)

        private fun sha256Hex(input: String): String =
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun randomSaltHex(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun matches(storedHash: String, pin: String): Boolean {
            val parts = storedHash.split(":", limit = 2)
            if (parts.size != 2) return false
            return sha256Hex(parts[0] + pin) == parts[1]
        }
    }

    private var failures = 0
    private var lockedUntil = 0L

    suspend fun isSet(): Boolean = settings.pinHash.first() != null

    suspend fun set(pin: String) {
        require(isValidPin(pin))
        val salt = randomSaltHex()
        settings.setPinHash("$salt:${sha256Hex(salt + pin)}")
        failures = 0
    }

    suspend fun verify(pin: String): VerifyResult {
        val now = clock()
        if (lockedUntil > now) return VerifyResult.LOCKED(lockedUntil)
        val stored = settings.pinHash.first()
        if (stored != null && matches(stored, pin)) {
            failures = 0
            return VerifyResult.OK
        }
        failures++
        if (failures >= MAX_FAILURES) {
            failures = 0
            lockedUntil = now + LOCK_MS
            return VerifyResult.LOCKED(lockedUntil)
        }
        return VerifyResult.WRONG(MAX_FAILURES - failures)
    }
}
