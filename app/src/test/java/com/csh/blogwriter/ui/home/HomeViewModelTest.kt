package com.csh.blogwriter.ui.home

import app.cash.turbine.test
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.PendingJob
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.update.UpdateChecker
import com.csh.blogwriter.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class HomeViewModelTest {
    private val blogId = MutableStateFlow<String?>(null)
    private val pending = MutableStateFlow<PendingJob?>(null)
    private val lastUpdateCheckAt = MutableStateFlow(0L)
    private val dismissedUpdateTag = MutableStateFlow<String?>(null)

    private val settings = object : SettingsStore {
        override val blogId: Flow<String?> = this@HomeViewModelTest.blogId
        override suspend fun setBlogId(id: String?) { this@HomeViewModelTest.blogId.value = id }

        override val lastUpdateCheckAt: Flow<Long> = this@HomeViewModelTest.lastUpdateCheckAt
        override suspend fun setLastUpdateCheckAt(timestamp: Long) { this@HomeViewModelTest.lastUpdateCheckAt.value = timestamp }

        override val dismissedUpdateTag: Flow<String?> = this@HomeViewModelTest.dismissedUpdateTag
        override suspend fun setDismissedUpdateTag(tag: String?) { this@HomeViewModelTest.dismissedUpdateTag.value = tag }
    }
    private val pendingRepo = object : PendingJobRepository {
        override fun observeLatest(): Flow<PendingJob?> = pending
        override suspend fun get(id: String): PendingJob? = pending.value?.takeIf { it.id == id }
        override suspend fun save(job: PendingJob) { pending.value = job }
        override suspend fun setPreparedPaths(id: String, paths: List<String>?) {}
        override suspend fun setLastFailure(id: String, message: String?) {}
        override suspend fun delete(id: String) { pending.value = null }
    }

    private class FakeUpdateChecker(private val result: UpdateInfo?) : UpdateChecker {
        var callCount = 0
            private set
        override suspend fun checkForUpdate(repo: String, currentVersion: String): UpdateInfo? {
            callCount++
            return result
        }
    }

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun reflectsBlogIdAndPendingJob() = runTest {
        val vm = HomeViewModel(settings, pendingRepo, FakeUpdateChecker(null))
        vm.uiState.test {
            assertEquals(HomeUiState(hasBlogId = false, pendingJobId = null, pendingTitle = null), awaitItem())
            blogId.value = "myblog"
            assertEquals(true, awaitItem().hasBlogId)
            pending.value = PendingJob("j1", PostContent("올리다 만 글", emptyList()), emptyList(), null, 1L, null)
            val s = awaitItem()
            assertEquals("j1", s.pendingJobId); assertEquals("올리다 만 글", s.pendingTitle)
        }
    }

    @Test
    fun throttledWithinSixHoursSkipsCheck() = runTest {
        lastUpdateCheckAt.value = System.currentTimeMillis()
        val checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com"))

        val vm = HomeViewModel(settings, pendingRepo, checker)
        advanceUntilIdle()

        assertEquals(0, checker.callCount)
        assertNull(vm.updateInfo.value)
    }

    @Test
    fun dismissedTagHidesBanner() = runTest {
        dismissedUpdateTag.value = "v9.9.9"
        val checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com"))

        val vm = HomeViewModel(settings, pendingRepo, checker)
        advanceUntilIdle()

        assertEquals(1, checker.callCount)
        assertNull(vm.updateInfo.value)
    }

    @Test
    fun newTagShowsBanner() = runTest {
        val checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com"))

        val vm = HomeViewModel(settings, pendingRepo, checker)
        advanceUntilIdle()

        assertEquals(UpdateInfo("v9.9.9", "https://example.com"), vm.updateInfo.value)
    }

    @Test
    fun dismissUpdateClearsBannerAndRemembersTag() = runTest {
        val checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com"))
        val vm = HomeViewModel(settings, pendingRepo, checker)
        advanceUntilIdle()
        assertEquals(UpdateInfo("v9.9.9", "https://example.com"), vm.updateInfo.value)

        vm.dismissUpdate()
        advanceUntilIdle()

        assertNull(vm.updateInfo.value)
        assertEquals("v9.9.9", dismissedUpdateTag.value)
    }
}
