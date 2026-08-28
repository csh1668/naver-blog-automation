package com.csh.blogwriter.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

interface SettingsStore {
    val blogId: Flow<String?>
    suspend fun setBlogId(id: String?)
    suspend fun blogIdOnce(): String? = blogId.first()
}

class DataStoreSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) : SettingsStore {
    private val keyBlogId = stringPreferencesKey("blog_id")
    override val blogId: Flow<String?> = dataStore.data.map { it[keyBlogId] }
    override suspend fun setBlogId(id: String?) {
        dataStore.edit { prefs -> if (id == null) prefs.remove(keyBlogId) else prefs[keyBlogId] = id }
    }
}
