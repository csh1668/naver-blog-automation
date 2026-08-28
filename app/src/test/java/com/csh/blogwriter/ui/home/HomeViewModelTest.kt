package com.csh.blogwriter.ui.home

import app.cash.turbine.test
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.data.repo.ChatRepository
import com.csh.blogwriter.data.repo.ChatSession
import com.csh.blogwriter.data.repo.MessageKind
import com.csh.blogwriter.data.repo.MessageRole
import com.csh.blogwriter.data.repo.PendingJob
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.data.repo.SessionStatus
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.llm.ApiKey
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.update.UpdateChecker
import com.csh.blogwriter.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    private val keys = MutableStateFlow<List<ApiKey>>(emptyList())
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
    private val chatRepo = object : ChatRepository {
        override fun observeSessions(): Flow<List<ChatSession>> = sessions
        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> = flowOf(emptyList())
        override suspend fun createSession() = throw NotImplementedError()
        override suspend fun getSession(id: String): ChatSession? = sessions.value.firstOrNull { it.id == id }
        override suspend fun updateSession(session: ChatSession) {}
        override suspend fun appendMessage(sessionId: String, role: MessageRole, kind: MessageKind, payloadJson: String): ChatMessage =
            ChatMessage(1, sessionId, 0, role, kind, payloadJson, 0)
        override suspend fun messagesOnce(sessionId: String): List<ChatMessage> = emptyList()
        override suspend fun deleteSession(id: String) {}
    }
    private val keyStore = object : ApiKeyStore {
        override val keys: Flow<List<ApiKey>> = this@HomeViewModelTest.keys
        override val hasUsableKey: Flow<Boolean> = keys.map { l -> l.any { it.usable } }
        override suspend fun add(secrets: List<String>) = emptyList<ApiKey>()
        override suspend fun remove(id: String) {}
        override suspend fun markOk(id: String) {}
        override suspend fun markLimited(id: String) {}
        override suspend fun markInvalid(id: String) {}
        override suspend fun resetAll() {}
    }

    private class FakeUpdateChecker(private val result: UpdateInfo?) : UpdateChecker {
        var callCount = 0
            private set
        override suspend fun checkForUpdate(repo: String, currentVersion: String): UpdateInfo? {
            callCount++
            return result
        }
    }

    private fun session(id: String, pendingJobId: String? = null, status: SessionStatus = SessionStatus.DRAFTING) =
        ChatSession(id, "제목-$id", 0, 0, status, pendingJobId, null)

    private fun vm(checker: UpdateChecker = FakeUpdateChecker(null)) = HomeViewModel(settings, pendingRepo, chatRepo, keyStore, checker)

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun reflectsBlogIdSessionsAndKey() = runTest {
        val vmInstance = vm()
        vmInstance.uiState.test {
            assertEquals(HomeUiState(hasBlogId = false, hasKey = true, sessions = emptyList(), legacyPendingJobId = null, legacyPendingTitle = null), awaitItem())
            blogId.value = "myblog"
            assertEquals(true, awaitItem().hasBlogId)
            keys.value = listOf(ApiKey("k1", "s", 0, lastOkAt = 1))
            assertEquals(true, awaitItem().hasKey)
            sessions.value = listOf(session("s1"))
            assertEquals(listOf(session("s1")), awaitItem().sessions)
        }
    }

    @Test
    fun pendingJobLinkedToSessionIsNotLegacy() = runTest {
        sessions.value = listOf(session("s1", pendingJobId = "j1", status = SessionStatus.PUBLISHING))
        val vmInstance = vm()
        vmInstance.uiState.test {
            awaitItem() // 구독 시점의 초기 상태
            pending.value = PendingJob("j1", PostContent("올리다 만 글", emptyList()), emptyList(), null, 1L, null)
            val s = awaitItem()
            assertNull(s.legacyPendingJobId)
        }
    }

    @Test
    fun pendingJobWithoutSessionIsLegacy() = runTest {
        val vmInstance = vm()
        vmInstance.uiState.test {
            awaitItem() // 구독 시점의 초기 상태
            pending.value = PendingJob("j1", PostContent("올리다 만 글", emptyList()), emptyList(), null, 1L, null)
            val s = awaitItem()
            assertEquals("j1", s.legacyPendingJobId)
            assertEquals("올리다 만 글", s.legacyPendingTitle)
        }
    }

    @Test
    fun throttledWithinSixHoursSkipsCheck() = runTest {
        lastUpdateCheckAt.value = System.currentTimeMillis()
        val checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com"))

        val vmInstance = vm(checker)
        advanceUntilIdle()

        assertEquals(0, checker.callCount)
        assertNull(vmInstance.updateInfo.value)
    }

    @Test
    fun dismissedTagHidesBanner() = runTest {
        dismissedUpdateTag.value = "v9.9.9"
        val checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com"))

        val vmInstance = vm(checker)
        advanceUntilIdle()

        assertEquals(1, checker.callCount)
        assertNull(vmInstance.updateInfo.value)
    }

    @Test
    fun newTagShowsBanner() = runTest {
        val checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com"))

        val vmInstance = vm(checker)
        advanceUntilIdle()

        assertEquals(UpdateInfo("v9.9.9", "https://example.com"), vmInstance.updateInfo.value)
    }

    @Test
    fun dismissUpdateClearsBannerAndRemembersTag() = runTest {
        val checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com"))
        val vmInstance = vm(checker)
        advanceUntilIdle()
        assertEquals(UpdateInfo("v9.9.9", "https://example.com"), vmInstance.updateInfo.value)

        vmInstance.dismissUpdate()
        advanceUntilIdle()

        assertNull(vmInstance.updateInfo.value)
        assertEquals("v9.9.9", dismissedUpdateTag.value)
    }
}
