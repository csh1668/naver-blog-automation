package com.csh.blogwriter.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csh.blogwriter.llm.ModelPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

interface SettingsStore {
    val blogId: Flow<String?>
    suspend fun setBlogId(id: String?)
    suspend fun blogIdOnce(): String? = blogId.first()

    val lastUpdateCheckAt: Flow<Long> get() = flowOf(0L)
    suspend fun setLastUpdateCheckAt(timestamp: Long) {}
    suspend fun lastUpdateCheckAtOnce(): Long = lastUpdateCheckAt.first()

    val dismissedUpdateTag: Flow<String?> get() = flowOf(null)
    suspend fun setDismissedUpdateTag(tag: String?) {}
    suspend fun dismissedUpdateTagOnce(): String? = dismissedUpdateTag.first()

    val pinHash: Flow<String?> get() = flowOf(null)
    suspend fun setPinHash(hash: String?) {}

    val modelPolicy: Flow<ModelPolicy> get() = flowOf(ModelPolicy.DEFAULT)
    suspend fun setModelPolicy(policy: ModelPolicy) {}
    suspend fun modelPolicyOnce(): ModelPolicy = modelPolicy.first()

    val researchEnabled: Flow<Boolean> get() = flowOf(true)
    suspend fun setResearchEnabled(enabled: Boolean) {}
}

class DataStoreSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) : SettingsStore {
    private val keyBlogId = stringPreferencesKey("blog_id")
    private val keyLastUpdateCheckAt = longPreferencesKey("last_update_check_at")
    private val keyDismissedUpdateTag = stringPreferencesKey("dismissed_update_tag")
    private val keyPinHash = stringPreferencesKey("pin_hash")
    private val keyModelList = stringPreferencesKey("model_list")
    private val keyModelTemperature = stringPreferencesKey("model_temperature")
    private val keyTargetLength = stringPreferencesKey("target_length")
    private val keyResearchEnabled = booleanPreferencesKey("research_enabled")

    override val blogId: Flow<String?> = dataStore.data.map { it[keyBlogId] }
    override suspend fun setBlogId(id: String?) {
        dataStore.edit { prefs -> if (id == null) prefs.remove(keyBlogId) else prefs[keyBlogId] = id }
    }

    override val lastUpdateCheckAt: Flow<Long> = dataStore.data.map { it[keyLastUpdateCheckAt] ?: 0L }
    override suspend fun setLastUpdateCheckAt(timestamp: Long) {
        dataStore.edit { prefs -> prefs[keyLastUpdateCheckAt] = timestamp }
    }

    override val dismissedUpdateTag: Flow<String?> = dataStore.data.map { it[keyDismissedUpdateTag] }
    override suspend fun setDismissedUpdateTag(tag: String?) {
        dataStore.edit { prefs -> if (tag == null) prefs.remove(keyDismissedUpdateTag) else prefs[keyDismissedUpdateTag] = tag }
    }

    override val pinHash: Flow<String?> = dataStore.data.map { it[keyPinHash] }
    override suspend fun setPinHash(hash: String?) {
        dataStore.edit { prefs -> if (hash == null) prefs.remove(keyPinHash) else prefs[keyPinHash] = hash }
    }

    override val modelPolicy: Flow<ModelPolicy> = dataStore.data.map { prefs ->
        val models = prefs[keyModelList]?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)?.takeIf { it.isNotEmpty() }
        val temperature = prefs[keyModelTemperature]?.toDoubleOrNull()
        val targetLength = prefs[keyTargetLength]?.let(::parseRange)
        ModelPolicy(
            models = models ?: ModelPolicy.DEFAULT.models,
            temperature = temperature ?: ModelPolicy.DEFAULT.temperature,
            targetLength = targetLength ?: ModelPolicy.DEFAULT.targetLength,
        )
    }
    override suspend fun setModelPolicy(policy: ModelPolicy) {
        dataStore.edit { prefs ->
            prefs[keyModelList] = policy.models.joinToString(",")
            prefs[keyModelTemperature] = policy.temperature.toString()
            prefs[keyTargetLength] = "${policy.targetLength.first}..${policy.targetLength.last}"
        }
    }

    override val researchEnabled: Flow<Boolean> = dataStore.data.map { it[keyResearchEnabled] ?: true }
    override suspend fun setResearchEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[keyResearchEnabled] = enabled }
    }

    private fun parseRange(raw: String): IntRange? {
        val parts = raw.split("..")
        if (parts.size != 2) return null
        val min = parts[0].trim().toIntOrNull() ?: return null
        val max = parts[1].trim().toIntOrNull() ?: return null
        if (min > max) return null
        return min..max
    }
}
