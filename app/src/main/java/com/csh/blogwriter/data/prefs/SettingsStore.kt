package com.csh.blogwriter.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
}

class DataStoreSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) : SettingsStore {
    private val keyBlogId = stringPreferencesKey("blog_id")
    private val keyLastUpdateCheckAt = longPreferencesKey("last_update_check_at")
    private val keyDismissedUpdateTag = stringPreferencesKey("dismissed_update_tag")

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
}
