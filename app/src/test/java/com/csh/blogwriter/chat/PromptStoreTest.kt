package com.csh.blogwriter.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PromptStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun defaultsComeFromAssetsAndOverridesRoundTrip() = runTest {
        val store = AssetPromptStore(RuntimeEnvironment.getApplication(), PreferenceDataStoreFactory.create { folder.newFile("p.preferences_pb") })
        val default = store.text(PromptSection.ROLE)
        assertTrue(default.contains("40대"))
        assertFalse(store.isOverridden(PromptSection.ROLE))
        store.override(PromptSection.ROLE, "내 역할")
        assertEquals("내 역할", store.observe(PromptSection.ROLE).first())
        assertTrue(store.isOverridden(PromptSection.ROLE))
        store.override(PromptSection.ROLE, null)
        assertEquals(default, store.text(PromptSection.ROLE))
    }
}
