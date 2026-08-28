package com.csh.blogwriter.llm

/**
 * 키·모델 선택 정책 (spec §4.3). 상태는 메모리에만 두며 앱 재시작 시 초기화된다(쿨다운은 짧고, 일일 상한은 보수적 회피용).
 * 순수 Kotlin — 시계는 주입.
 */
class KeyRotator(keyIds: List<String>, private val models: List<String>, private val clock: () -> Long) {
    data class Pick(val keyId: String, val model: String)
    enum class Outcome { SUCCESS, RATE_LIMITED, INVALID_KEY, TRANSIENT }

    companion object {
        const val KEY_COOLDOWN_MS = 60_000L
        const val MODEL_COOLDOWN_MS = 600_000L
        const val DAILY_CAP = 20
    }

    private class KeyState(val id: String) { var cooldownUntil = 0L; var disabled = false; var successesToday = 0; var dayStamp = 0L }
    private val keys = keyIds.map { KeyState(it) }
    private val modelCooldownUntil = HashMap<String, Long>()
    private var startIndex = 0

    fun next(): Pick? {
        val now = clock()
        rollDay(now)
        for (model in models) {
            if ((modelCooldownUntil[model] ?: 0L) > now) continue
            val order = (keys.indices).map { keys[(startIndex + it) % keys.size] }
                .filter { !it.disabled && it.cooldownUntil <= now }
                .sortedBy { if (it.successesToday >= DAILY_CAP) 1 else 0 }   // 상한 키는 마지막 순위
            val key = order.firstOrNull()
            if (key != null) return Pick(key.id, model)

            // No non-cooldown key found; try any non-disabled key as last resort
            val anyKey = (keys.indices).map { keys[(startIndex + it) % keys.size] }
                .filter { !it.disabled }
                .sortedBy { if (it.successesToday >= DAILY_CAP) 1 else 0 }
                .firstOrNull()
            if (anyKey != null) return Pick(anyKey.id, model)
        }
        return null
    }

    fun report(pick: Pick, outcome: Outcome) {
        val now = clock()
        val key = keys.first { it.id == pick.keyId }
        when (outcome) {
            Outcome.SUCCESS -> { key.successesToday++; startIndex = (keys.indexOf(key) + 1) % keys.size }
            Outcome.RATE_LIMITED -> {
                key.cooldownUntil = now + KEY_COOLDOWN_MS
                if (keys.all { it.disabled || it.cooldownUntil > now }) modelCooldownUntil[pick.model] = now + MODEL_COOLDOWN_MS
            }
            Outcome.INVALID_KEY -> key.disabled = true
            Outcome.TRANSIENT -> startIndex = (keys.indexOf(key) + 1) % keys.size
        }
    }

    fun nextAvailableAt(): Long? {
        val candidates = keys.filter { !it.disabled }.map { it.cooldownUntil } + modelCooldownUntil.values
        return candidates.filter { it > clock() }.minOrNull()
    }

    fun disabledKeys(): Set<String> = keys.filter { it.disabled }.map { it.id }.toSet()

    private fun rollDay(now: Long) {
        val day = now / 86_400_000L
        keys.forEach { if (it.dayStamp != day) { it.dayStamp = day; it.successesToday = 0 } }
    }
}
