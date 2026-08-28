package com.csh.blogwriter.ui.chat

import com.csh.blogwriter.chat.AttachedPhoto
import com.csh.blogwriter.chat.Attachment
import com.csh.blogwriter.chat.ChatContext
import com.csh.blogwriter.chat.NoOpPublishedHook
import com.csh.blogwriter.chat.PhotoAttachments
import com.csh.blogwriter.chat.Plan
import com.csh.blogwriter.chat.TurnListener
import com.csh.blogwriter.chat.TurnResponse
import com.csh.blogwriter.chat.TurnResult
import com.csh.blogwriter.chat.TurnRunner
import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.data.repo.ChatRepository
import com.csh.blogwriter.data.repo.ChatSession
import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.data.repo.MessageKind
import com.csh.blogwriter.data.repo.MessageRole
import com.csh.blogwriter.data.repo.PendingJob
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.data.repo.SessionStatus
import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import com.csh.blogwriter.llm.ApiKey
import com.csh.blogwriter.llm.ApiKeyStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    // ---- 가짜 저장소들 ----

    private class FakeChatRepository : ChatRepository {
        val sessions = MutableStateFlow<List<ChatSession>>(emptyList())
        val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        private var nextId = 1L

        override fun observeSessions(): Flow<List<ChatSession>> = sessions
        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
            messages.map { list -> list.filter { it.sessionId == sessionId } }

        override suspend fun createSession(): ChatSession {
            val session = ChatSession("s${sessions.value.size + 1}", null, 0, 0, SessionStatus.DRAFTING, null, null)
            sessions.value = sessions.value + session
            return session
        }

        override suspend fun getSession(id: String): ChatSession? = sessions.value.firstOrNull { it.id == id }

        override suspend fun updateSession(session: ChatSession) {
            sessions.value = sessions.value.map { if (it.id == session.id) session else it }
        }

        override suspend fun appendMessage(sessionId: String, role: MessageRole, kind: MessageKind, payloadJson: String): ChatMessage {
            val seq = messages.value.count { it.sessionId == sessionId }
            val message = ChatMessage(nextId++, sessionId, seq, role, kind, payloadJson, 0)
            messages.value = messages.value + message
            return message
        }

        override suspend fun messagesOnce(sessionId: String): List<ChatMessage> = messages.value.filter { it.sessionId == sessionId }

        override suspend fun deleteSession(id: String) {
            sessions.value = sessions.value.filterNot { it.id == id }
            messages.value = messages.value.filterNot { it.sessionId == id }
        }
    }

    private class FakePendingJobRepository : PendingJobRepository {
        val jobs = MutableStateFlow<List<PendingJob>>(emptyList())
        override fun observeLatest(): Flow<PendingJob?> = jobs.map { it.lastOrNull() }
        override suspend fun get(id: String): PendingJob? = jobs.value.firstOrNull { it.id == id }
        override suspend fun save(job: PendingJob) {
            jobs.value = if (jobs.value.any { it.id == job.id }) jobs.value.map { if (it.id == job.id) job else it } else jobs.value + job
        }
        override suspend fun setPreparedPaths(id: String, paths: List<String>?) {
            jobs.value = jobs.value.map { if (it.id == id) it.copy(preparedPaths = paths) else it }
        }
        override suspend fun setLastFailure(id: String, message: String?) {
            jobs.value = jobs.value.map { if (it.id == id) it.copy(lastFailure = message) else it }
        }
        override suspend fun delete(id: String) { jobs.value = jobs.value.filterNot { it.id == id } }
    }

    private class FakeApiKeyStore(usable: Boolean) : ApiKeyStore {
        private val stored = MutableStateFlow(if (usable) listOf(ApiKey("k1", "SECRET", 0, lastOkAt = 1)) else emptyList())
        override val keys: Flow<List<ApiKey>> = stored
        override val hasUsableKey: Flow<Boolean> = stored.map { list -> list.any { it.usable } }
        override suspend fun add(secrets: List<String>): List<ApiKey> = emptyList()
        override suspend fun remove(id: String) {}
        override suspend fun markOk(id: String) {}
        override suspend fun markLimited(id: String) {}
        override suspend fun markInvalid(id: String) {}
        override suspend fun resetAll() {}
    }

    private class FakeMemoryRepository(private val items: List<MemoryItem> = emptyList()) : MemoryRepository {
        override fun observeAll(): Flow<List<MemoryItem>> = flowOf(items)
        override suspend fun activeItems(limit: Int): List<MemoryItem> = items
        override suspend fun add(kind: MemoryKind, text: String, source: String) = MemoryItem(1, kind, text, source, 0, true, null)
        override suspend fun update(id: Long, text: String) {}
        override suspend fun setEnabled(id: Long, enabled: Boolean) {}
        override suspend fun delete(id: Long) {}
        override suspend fun touch(ids: List<Long>) {}
    }

    private class FakePhotoAttachments : PhotoAttachments {
        override suspend fun prepare(sessionId: String, startIndex: Int, uris: List<String>): List<AttachedPhoto> =
            uris.mapIndexed { i, uri -> AttachedPhoto("img_%03d".format(startIndex + i + 1), uri, null) }
        override suspend fun attachments(sessionId: String, photos: List<AttachedPhoto>): List<Attachment> =
            photos.map { Attachment(it.ref, "BASE64") }
        override fun clear(sessionId: String) {}
    }

    // ---- 가짜 엔진 ----

    private val turns = ArrayDeque<TurnResult>()
    private val contexts = mutableListOf<ChatContext>()
    /** onPartialSay 직후 화면에 실제로 보인 값 — 스트리밍이 반영되는지 본다. */
    private val observedStreaming = mutableListOf<String?>()
    private var viewModel: ChatViewModel? = null

    private val runner = object : TurnRunner {
        override suspend fun runTurn(ctx: ChatContext, listener: TurnListener): TurnResult {
            contexts += ctx
            listener.onPartialSay("이렇게"); observedStreaming += viewModel?.uiState?.value?.streamingSay
            listener.onPartialSay("이렇게 써 볼까요?"); observedStreaming += viewModel?.uiState?.value?.streamingSay
            listener.onToolStatus("네이버에서 찾고 있어요…")
            return turns.removeFirst()
        }
    }

    private val chatRepo = FakeChatRepository()
    private val pendingJobs = FakePendingJobRepository()
    private val memory = FakeMemoryRepository(listOf(MemoryItem(1, MemoryKind.STYLE, "존댓말로 써요", "chat", 0, true, null)))

    private fun newViewModel(hasKey: Boolean = true): ChatViewModel =
        ChatViewModel(chatRepo, runner, pendingJobs, FakePhotoAttachments(), FakeApiKeyStore(hasKey), memory, NoOpPublishedHook())
            .also { viewModel = it }

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun sendStoresUserAndAssistantMessagesAndQuickReplies() = runTest {
        turns += TurnResult.Success(
            TurnResponse("이렇게 써 볼까요?", plan = Plan(listOf("a", "b", "c"), emptyList(), "t"), quickReplies = listOf("1번 제목으로")),
            emptyList(), "flash",
        )
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.send("원주 한우 다녀왔어요"); advanceUntilIdle()

        val kinds = vm.uiState.value.messages.map { it.role to it.kind }
        assertEquals(
            listOf(
                MessageRole.USER to MessageKind.TEXT,
                MessageRole.ASSISTANT to MessageKind.TEXT,
                MessageRole.ASSISTANT to MessageKind.PLAN,
            ),
            kinds,
        )
        assertEquals(listOf("1번 제목으로"), vm.uiState.value.quickReplies)
        assertEquals(false, vm.uiState.value.thinking)
        assertNull(vm.uiState.value.streamingSay)
        assertNull(vm.uiState.value.toolStatus)
        // 스트리밍 중에는 임시 말풍선이 부분 텍스트로 계속 교체된다.
        assertEquals(listOf("이렇게", "이렇게 써 볼까요?"), observedStreaming)
        // 스타일 기억은 컨텍스트로 전달된다.
        assertEquals("존댓말로 써요", contexts.single().style)
        assertFalse(contexts.single().draftTurn)
    }

    @Test
    fun draftTurnCreatesPendingJobAndOpensPanel() = runTest {
        turns += TurnResult.Success(
            TurnResponse("초안이에요", readyToDraft = true, post = PostContent("제목", listOf(Block.Paragraph(listOf(Run("본문")))))),
            emptyList(), "flash",
        )
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()

        assertTrue(contexts.single().draftTurn)
        assertNotNull(vm.uiState.value.panelJobId)
        assertTrue(vm.uiState.value.panelOpen)
        assertEquals("제목", pendingJobs.jobs.value.single().content.title)
        assertEquals(SessionStatus.PUBLISHING, vm.uiState.value.session!!.status)
        assertEquals(vm.uiState.value.panelJobId, vm.uiState.value.session!!.pendingJobId)
    }

    @Test
    fun failureShowsSystemMessageInUserLanguage() = runTest {
        turns += TurnResult.Failure(TurnResult.Reason.RATE_LIMITED, retryAt = 60_000)
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.send("안녕"); advanceUntilIdle()

        val last = vm.uiState.value.messages.last()
        assertEquals(MessageKind.SYSTEM, last.kind)
        assertTrue(last.payloadJson.contains("잠깐 쉬어야"))
        assertEquals(listOf(ChatViewModel.RETRY_CHIP), vm.uiState.value.quickReplies)
        assertEquals(false, vm.uiState.value.thinking)
    }

    @Test
    fun withoutUsableKeyNoTurnRunsAndBannerCopyIsShown() = runTest {
        val vm = newViewModel(hasKey = false); vm.open(null); advanceUntilIdle()
        vm.send("안녕"); advanceUntilIdle()

        assertFalse(vm.uiState.value.hasKey)
        assertTrue(contexts.isEmpty())
        val messages = vm.uiState.value.messages
        assertEquals(listOf(MessageKind.SYSTEM), messages.map { it.kind })
        assertTrue(messages.single().payloadJson.contains("관리자가 열쇠를 등록해야 해요"))
    }
}
