package com.csh.blogwriter.ui.home

import app.cash.turbine.test
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.PendingJob
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.domain.model.PostContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val blogId = MutableStateFlow<String?>(null)
    private val pending = MutableStateFlow<PendingJob?>(null)

    private val settings = object : SettingsStore {
        override val blogId: Flow<String?> = this@HomeViewModelTest.blogId
        override suspend fun setBlogId(id: String?) { this@HomeViewModelTest.blogId.value = id }
    }
    private val pendingRepo = object : PendingJobRepository {
        override fun observeLatest(): Flow<PendingJob?> = pending
        override suspend fun get(id: String): PendingJob? = pending.value?.takeIf { it.id == id }
        override suspend fun save(job: PendingJob) { pending.value = job }
        override suspend fun setPreparedPaths(id: String, paths: List<String>?) {}
        override suspend fun setLastFailure(id: String, message: String?) {}
        override suspend fun delete(id: String) { pending.value = null }
    }

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun reflectsBlogIdAndPendingJob() = runTest {
        val vm = HomeViewModel(settings, pendingRepo)
        vm.uiState.test {
            assertEquals(HomeUiState(hasBlogId = false, pendingJobId = null, pendingTitle = null), awaitItem())
            blogId.value = "myblog"
            assertEquals(true, awaitItem().hasBlogId)
            pending.value = PendingJob("j1", PostContent("올리다 만 글", emptyList()), emptyList(), null, 1L, null)
            val s = awaitItem()
            assertEquals("j1", s.pendingJobId); assertEquals("올리다 만 글", s.pendingTitle)
        }
    }
}
