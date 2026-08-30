package com.csh.blogwriter.ui.admin

import com.csh.blogwriter.BuildConfig
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.llm.ApiKey
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.session.NaverSession
import com.csh.blogwriter.update.UpdateChecker
import com.csh.blogwriter.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private class FakeSettingsStore : SettingsStore {
        val lastCheckAt = MutableStateFlow(0L)
        val dismissedTag = MutableStateFlow<String?>(null)

        val blogIdFlow = MutableStateFlow<String?>(null)
        override val blogId: Flow<String?> = blogIdFlow
        override suspend fun setBlogId(id: String?) { blogIdFlow.value = id }

        override val lastUpdateCheckAt: Flow<Long> = lastCheckAt
        override suspend fun setLastUpdateCheckAt(timestamp: Long) { lastCheckAt.value = timestamp }

        override val dismissedUpdateTag: Flow<String?> = dismissedTag
        override suspend fun setDismissedUpdateTag(tag: String?) { dismissedTag.value = tag }
    }

    private class FakeApiKeyStore : ApiKeyStore {
        private val stored = MutableStateFlow<List<ApiKey>>(emptyList())
        override val keys: Flow<List<ApiKey>> = stored
        override val hasUsableKey: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun add(secrets: List<String>): List<ApiKey> = emptyList()
        override suspend fun remove(id: String) {}
        override suspend fun markOk(id: String) {}
        override suspend fun markLimited(id: String) {}
        override suspend fun markInvalid(id: String) {}
        override suspend fun resetAll() {}
    }

    /** 새 버전 확인기. [result] 를 돌려주거나 없으면 그대로 최신. */
    private class FakeUpdateChecker(private val result: UpdateInfo?) : UpdateChecker {
        override suspend fun check(repo: String, currentVersion: String): Result<UpdateInfo?> = Result.success(result)
    }

    private val settings = FakeSettingsStore()

    private fun newViewModel(checker: UpdateChecker): SettingsViewModel =
        SettingsViewModel(settings, FakeApiKeyStore(), NaverSession(settings), checker)

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun checkFindsNewerVersionAndArmsChatBanner() = runTest {
        settings.dismissedTag.value = "v9.9.9"; settings.lastCheckAt.value = 123L
        val vm = newViewModel(checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com")))
        backgroundScope.launch { vm.uiState.collect {} }
        vm.checkForUpdate(); advanceUntilIdle()
        assertEquals(UpdateCheckState.Available(UpdateInfo("v9.9.9", "https://example.com")), vm.uiState.value.updateCheck)
        assertNull(settings.dismissedTag.value)   // 닫아 둔 태그를 풀어 채팅 배너가 다시 뜨게
        assertEquals(0L, settings.lastCheckAt.value)  // 채팅으로 돌아가면 바로 다시 확인
    }

    @Test fun checkReportsUpToDate() = runTest {
        val vm = newViewModel(checker = FakeUpdateChecker(null))
        backgroundScope.launch { vm.uiState.collect {} }
        vm.checkForUpdate(); advanceUntilIdle()
        assertEquals(UpdateCheckState.UpToDate(BuildConfig.VERSION_NAME), vm.uiState.value.updateCheck)
    }

    @Test fun checkFailureIsReported() = runTest {
        val vm = newViewModel(checker = object : UpdateChecker { override suspend fun check(repo: String, currentVersion: String): Result<UpdateInfo?> = Result.failure(java.io.IOException("offline")) })
        backgroundScope.launch { vm.uiState.collect {} }
        vm.checkForUpdate(); advanceUntilIdle()
        assertEquals(UpdateCheckState.Failed, vm.uiState.value.updateCheck)
    }
}
