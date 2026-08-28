package com.csh.blogwriter.ui.chat

import com.csh.blogwriter.chat.AttachedPhoto
import com.csh.blogwriter.chat.Attachment
import com.csh.blogwriter.chat.ChatContext
import com.csh.blogwriter.chat.PhotoAttachments
import com.csh.blogwriter.chat.Plan
import com.csh.blogwriter.chat.PublishedHook
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertNotEquals
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

        fun of(sessionId: String) = messages.value.filter { it.sessionId == sessionId }
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

    /** [unreadable] 에 든 uri 는 준비에 실패한 사진처럼 thumb 없이 돌려준다. */
    private class FakePhotoAttachments(private val unreadable: Set<String> = emptySet()) : PhotoAttachments {
        val cleared = mutableListOf<String>()
        override suspend fun prepare(sessionId: String, startIndex: Int, uris: List<String>): List<AttachedPhoto> =
            uris.mapIndexed { i, uri ->
                AttachedPhoto("img_%03d".format(startIndex + i + 1), uri, if (uri in unreadable) null else "/cache/$sessionId/$i.jpg")
            }
        override suspend fun attachments(sessionId: String, photos: List<AttachedPhoto>): List<Attachment> =
            photos.map { Attachment(it.ref, "BASE64:${it.uri}") }
        override fun clear(sessionId: String) { cleared += sessionId }
    }

    private class RecordingPublishedHook : PublishedHook {
        val published = mutableListOf<Pair<String, String>>()
        override suspend fun onPublished(sessionId: String, url: String) { published += sessionId to url }
    }

    // ---- 가짜 엔진 ----

    private val turns = ArrayDeque<TurnResult>()
    private val contexts = mutableListOf<ChatContext>()
    /** onPartialSay 직후 화면에 실제로 보인 값 — 스트리밍이 그대로 반영되는지 본다. */
    private val observedStreaming = mutableListOf<String?>()
    private var partials: List<String> = listOf("이렇게", "이렇게 써 볼까요?")
    /** 넣어 두면 턴이 여기서 멈춘다 — 턴이 도는 중에 다른 일을 시켜 볼 때 쓴다. */
    private var gate: CompletableDeferred<Unit>? = null
    private var viewModel: ChatViewModel? = null

    private val runner = object : TurnRunner {
        override suspend fun runTurn(ctx: ChatContext, listener: TurnListener): TurnResult {
            contexts += ctx
            partials.forEach { partial ->
                listener.onPartialSay(partial)
                observedStreaming += viewModel?.uiState?.value?.streamingSay
            }
            listener.onToolStatus("네이버에서 찾고 있어요…")
            gate?.await()
            return turns.removeFirst()
        }
    }

    private val chatRepo = FakeChatRepository()
    private val pendingJobs = FakePendingJobRepository()
    private val memory = FakeMemoryRepository(listOf(MemoryItem(1, MemoryKind.STYLE, "존댓말로 써요", "chat", 0, true, null)))
    private val hook = RecordingPublishedHook()
    private var photos = FakePhotoAttachments()

    private fun newViewModel(hasKey: Boolean = true): ChatViewModel =
        ChatViewModel(chatRepo, runner, pendingJobs, photos, FakeApiKeyStore(hasKey), memory, hook)
            .also { viewModel = it }

    private fun post(title: String = "제목") = PostContent(title, listOf(Block.Paragraph(listOf(Run("본문")))))

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
        assertEquals("존댓말로 써요", contexts.single().style)
        assertFalse(contexts.single().draftTurn)
    }

    /** 새 스트림이 시작되면 엔진이 ""(지우라는 뜻)를 보낸다 — 이어붙이지 않고 교체해야 한다. */
    @Test
    fun partialSayReplacesAndEmptyStringClearsTheTemporaryBubble() = runTest {
        partials = listOf("이렇게", "이렇게 써", "", "다시 씁니다", "다시 씁니다요")
        turns += TurnResult.Success(TurnResponse("다시 씁니다요"), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.send("안녕"); advanceUntilIdle()

        assertEquals(listOf("이렇게", "이렇게 써", null, "다시 씁니다", "다시 씁니다요"), observedStreaming)
        assertNull(vm.uiState.value.streamingSay)
        assertEquals("다시 씁니다요", ChatPayloads.readText(vm.uiState.value.messages.last().payloadJson))
    }

    @Test
    fun draftTurnCreatesPendingJobWithOriginalPhotoUrisAndOpensPanel() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", readyToDraft = true, post = post()), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.attachPhotos(listOf("content://photos/1", "content://photos/2")); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()

        assertTrue(contexts.single().draftTurn)
        // 모델에는 축소본이, 발행 작업에는 원본 uri 가 간다.
        assertEquals(listOf("img_001", "img_002"), contexts.single().attachments.map { it.ref })
        assertEquals(listOf("content://photos/1", "content://photos/2"), pendingJobs.jobs.value.single().imageUris)

        assertNotNull(vm.uiState.value.panelJobId)
        assertTrue(vm.uiState.value.panelOpen)
        assertEquals("제목", pendingJobs.jobs.value.single().content.title)
        assertEquals(SessionStatus.PUBLISHING, vm.uiState.value.session!!.status)
        assertEquals(vm.uiState.value.panelJobId, vm.uiState.value.session!!.pendingJobId)
        // 보내고 나면 입력창 위 사진판은 비어 있고, 대화의 사진 목록에는 남는다.
        assertTrue(vm.uiState.value.tray.isEmpty())
        assertEquals(2, vm.uiState.value.attachments.size)
    }

    @Test
    fun failureShowsSystemMessageInUserLanguage() = runTest {
        turns += TurnResult.Failure(TurnResult.Reason.RATE_LIMITED, retryAt = System.currentTimeMillis() + 5 * 60_000)
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.send("안녕"); advanceUntilIdle()

        val last = vm.uiState.value.messages.last()
        assertEquals(MessageKind.SYSTEM, last.kind)
        assertEquals("지금은 잠깐 쉬어야 해요. 5분 뒤에 다시 시도할게요.", ChatPayloads.readText(last.payloadJson))
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
        assertEquals(false, vm.uiState.value.thinking)
    }

    /** 답을 기다리는 사이 다른 대화로 옮기면 그 답은 조용히 버린다 — 남의 대화에 붙으면 안 된다. */
    @Test
    fun switchingSessionMidTurnDropsTheLateAnswer() = runTest {
        turns += TurnResult.Success(TurnResponse("늦게 온 답", plan = Plan(listOf("a"), emptyList(), "t")), emptyList(), "flash")
        gate = CompletableDeferred()
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        val first = vm.uiState.value.session!!.id

        vm.send("첫 대화 이야기"); advanceUntilIdle()
        assertTrue(vm.uiState.value.thinking)

        vm.open(null); advanceUntilIdle()
        val second = vm.uiState.value.session!!.id
        assertNotEquals(first, second)

        gate!!.complete(Unit); advanceUntilIdle()

        // 새 대화에는 아무것도 들어오지 않는다.
        assertTrue(chatRepo.of(second).isEmpty())
        // 원래 대화에는 보낸 말만 남고 반쪽짜리 답장은 저장되지 않는다.
        assertEquals(listOf(MessageRole.USER to MessageKind.TEXT), chatRepo.of(first).map { it.role to it.kind })
        assertFalse(vm.uiState.value.thinking)
        assertNull(vm.uiState.value.streamingSay)
        assertTrue(vm.uiState.value.quickReplies.isEmpty())
    }

    @Test
    fun unreadablePhotosAreDroppedSoThePublishJobNeverSeesThem() = runTest {
        photos = FakePhotoAttachments(unreadable = setOf("content://photos/2"))
        turns += TurnResult.Success(TurnResponse("초안이에요", post = post()), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.attachPhotos(listOf("content://photos/1", "content://photos/2", "content://photos/3")); advanceUntilIdle()

        assertEquals(listOf("content://photos/1", "content://photos/3"), vm.uiState.value.attachments.map { it.uri })
        assertTrue(vm.uiState.value.error!!.contains("1장은 읽지 못했어요"))

        vm.requestDraft(); advanceUntilIdle()
        assertEquals(listOf("content://photos/1", "content://photos/3"), pendingJobs.jobs.value.single().imageUris)
    }

    /** 사진을 빼면 남은 사진의 ref 가 다시 매겨져야 한다 — 발행 쪽은 순서대로 img_001.. 을 붙인다. */
    @Test
    fun removingAPhotoRenumbersTheRest() = runTest {
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.attachPhotos(listOf("a", "b", "c")); advanceUntilIdle()

        vm.removePhoto("img_002")
        assertEquals(listOf("img_001" to "a", "img_002" to "c"), vm.uiState.value.attachments.map { it.ref to it.uri })

        vm.movePhoto(1, 0)
        assertEquals(listOf("img_001" to "c", "img_002" to "a"), vm.uiState.value.attachments.map { it.ref to it.uri })
    }

    /** 두 번째 초안은 새 작업을 만들지 않고, 이미 붙어 있는 패널에 다시 넣어 달라고 한다(접혀 있어도). */
    @Test
    fun aSecondDraftReinjectsIntoTheSameJob() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", post = post("첫 제목")), emptyList(), "flash")
        turns += TurnResult.Success(TurnResponse("고쳤어요", post = post("둘째 제목")), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        val reinjects = mutableListOf<PostContent>()
        val collector = launch { vm.reinject.collect { reinjects += it } }

        vm.requestDraft(); advanceUntilIdle()
        val jobId = vm.uiState.value.panelJobId
        assertNotNull(jobId)
        assertTrue(reinjects.isEmpty())

        vm.togglePanel()               // 패널을 접어도 붙어 있는 상태는 그대로다
        assertFalse(vm.uiState.value.panelOpen)
        vm.send("문단 2를 더 짧게"); advanceUntilIdle()

        // 접어 둔 채로 고쳐 달라고 해도 지금 초안이 함께 실려 나가야 한다.
        assertEquals("첫 제목", contexts.last().currentPost?.title)
        assertEquals(listOf("둘째 제목"), reinjects.map { it.title })
        assertEquals(jobId, vm.uiState.value.panelJobId)
        assertEquals(1, pendingJobs.jobs.value.size)
        assertEquals("둘째 제목", pendingJobs.jobs.value.single().content.title)
        collector.cancel()
    }

    /** 못 읽은 사진 자리를 비워 두면 다음 첨부에서 번호가 겹친다 — 붙일 때마다 전체를 다시 매긴다. */
    @Test
    fun refsStayContiguousWhenAnUnreadablePhotoIsDropped() = runTest {
        photos = FakePhotoAttachments(unreadable = setOf("b"))
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()

        vm.attachPhotos(listOf("a", "b", "c")); advanceUntilIdle()
        assertEquals(listOf("img_001" to "a", "img_002" to "c"), vm.uiState.value.attachments.map { it.ref to it.uri })

        vm.attachPhotos(listOf("d")); advanceUntilIdle()
        val refs = vm.uiState.value.attachments.map { it.ref }
        assertEquals(listOf("img_001", "img_002", "img_003"), refs)
        assertEquals(refs.size, refs.distinct().size)

        // 대화에 남는 사진 메시지의 ref 도 최종 번호와 같아야 한다.
        val messaged = chatRepo.messages.value.filter { it.kind == MessageKind.PHOTOS }
            .mapNotNull { ChatPayloads.readPhotos(it.payloadJson) }
        assertEquals(listOf(listOf("img_001", "img_002"), listOf("img_003")), messaged.map { it.refs })
    }

    @Test
    fun attachingTheSameUriTwiceIsIgnored() = runTest {
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.attachPhotos(listOf("a", "a", "b")); advanceUntilIdle()
        vm.attachPhotos(listOf("b", "c")); advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), vm.uiState.value.attachments.map { it.uri })
        assertEquals(listOf("img_001", "img_002", "img_003"), vm.uiState.value.attachments.map { it.ref })
    }

    /** 사진판의 앞/뒤 버튼은 사진판 기준 위치를 준다 — 이미 보낸 사진 개수만큼 밀려 있으면 안 된다. */
    @Test
    fun movePhotoUsesTrayPositionsAfterEarlierPhotosWereSent() = runTest {
        turns += TurnResult.Success(TurnResponse("네"), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.attachPhotos(listOf("a", "b")); advanceUntilIdle()
        vm.send("먼저 이거요"); advanceUntilIdle()
        vm.attachPhotos(listOf("c", "d")); advanceUntilIdle()

        assertEquals(2, vm.uiState.value.trayFrom)
        assertEquals(listOf("c", "d"), vm.uiState.value.tray.map { it.uri })

        vm.movePhoto(1, 0)   // 사진판에서 d 를 앞으로
        assertEquals(listOf("a", "b", "d", "c"), vm.uiState.value.attachments.map { it.uri })
        assertEquals(listOf("img_001", "img_002", "img_003", "img_004"), vm.uiState.value.attachments.map { it.ref })
    }

    /** 열쇠가 없어 턴이 아예 시작되지 않았으면 고른 사진이 사진판에서 사라지면 안 된다. */
    @Test
    fun withoutUsableKeyThePhotoTrayIsKept() = runTest {
        val vm = newViewModel(hasKey = false); vm.open(null); advanceUntilIdle()
        vm.attachPhotos(listOf("a", "b")); advanceUntilIdle()
        vm.send("안녕"); advanceUntilIdle()

        assertEquals(0, vm.uiState.value.trayFrom)
        assertEquals(listOf("a", "b"), vm.uiState.value.tray.map { it.uri })
    }

    @Test
    fun publishingClosesThePanelClearsThePhotoCacheAndCallsTheHook() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", post = post()), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()
        val sessionId = vm.uiState.value.session!!.id

        vm.onPublished("https://blog.naver.com/b/1"); advanceUntilIdle()

        val session = vm.uiState.value.session!!
        assertEquals(SessionStatus.PUBLISHED, session.status)
        assertEquals("https://blog.naver.com/b/1", session.publishedUrl)
        assertNull(session.pendingJobId)
        assertFalse(vm.uiState.value.panelOpen)
        assertNull(vm.uiState.value.panelJobId)
        assertEquals(listOf(sessionId), photos.cleared)
        assertEquals(listOf(sessionId to "https://blog.naver.com/b/1"), hook.published)
        assertTrue(ChatPayloads.readText(vm.uiState.value.messages.last().payloadJson).contains("발행했어요"))
    }
}
