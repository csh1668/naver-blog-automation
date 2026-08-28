package com.csh.blogwriter.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store(): SettingsStore {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create { folder.newFile("settings.preferences_pb") }
        return DataStoreSettingsStore(dataStore)
    }

    @Test
    fun blogIdRoundTrip() = runTest {
        val s = store()
        assertNull(s.blogId.first())
        s.setBlogId("myblog")
        assertEquals("myblog", s.blogIdOnce())
        s.setBlogId(null)
        assertNull(s.blogId.first())
    }
}
