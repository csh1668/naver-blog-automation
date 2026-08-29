package com.csh.blogwriter.ui.chat

import com.csh.blogwriter.blog.BlogReader
import com.csh.blogwriter.blog.PostSummary
import com.csh.blogwriter.blog.PostText
import com.csh.blogwriter.chat.AttachedPhoto
import com.csh.blogwriter.chat.Attachment
import com.csh.blogwriter.chat.ChatContext
import com.csh.blogwriter.chat.PhotoAttachments
import com.csh.blogwriter.chat.PublishedHook
import com.csh.blogwriter.chat.TurnListener
import com.csh.blogwriter.chat.TurnResponse
import com.csh.blogwriter.chat.TurnResult
import com.csh.blogwriter.chat.TurnRunner
import com.csh.blogwriter.data.prefs.SettingsStore
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
import com.csh.blogwriter.data.repo.SessionMode
import com.csh.blogwriter.data.repo.SessionStatus
import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import com.csh.blogwriter.llm.ApiKey
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.update.UpdateChecker
import com.csh.blogwriter.update.UpdateInfo
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

        override suspend fun createSession(mode: SessionMode): ChatSession {
            val session = ChatSession("s${sessions.value.size + 1}", null, 0, 0, SessionStatus.DRAFTING, null, null, mode)
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

        override suspend fun setTitle(id: String, title: String) {
            sessions.value = sessions.value.map { if (it.id == id) it.copy(title = title) else it }
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

    private class FakeSettingsStore : SettingsStore {
        val lastCheckAt = MutableStateFlow(0L)
        val dismissedTag = MutableStateFlow<String?>(null)

        val blogIdFlow = MutableStateFlow<String?>("sampleblog")
        override val blogId: Flow<String?> = blogIdFlow
        // 게이트 테스트의 글 길이(1000/1100/1300/1500자)는 이 범위를 기준으로 짜여 있다 — 앱 기본값이 바뀌어도 테스트는 고정.
        override val modelPolicy: Flow<com.csh.blogwriter.llm.ModelPolicy> = flowOf(com.csh.blogwriter.llm.ModelPolicy.DEFAULT.copy(targetLength = 1200..1800))
        override suspend fun setBlogId(id: String?) { blogIdFlow.value = id }

        override val lastUpdateCheckAt: Flow<Long> = lastCheckAt
        override suspend fun setLastUpdateCheckAt(timestamp: Long) { lastCheckAt.value = timestamp }

        override val dismissedUpdateTag: Flow<String?> = dismissedTag
        override suspend fun setDismissedUpdateTag(tag: String?) { dismissedTag.value = tag }
    }

    /** 새 버전 확인기. [result] 를 돌려주고 몇 번 불렸는지 센다. */
    private class FakeUpdateChecker(private val result: UpdateInfo?) : UpdateChecker {
        var callCount = 0
            private set
        override suspend fun checkForUpdate(repo: String, currentVersion: String): UpdateInfo? {
            callCount++
            return result
        }
    }

    private class FakeBlogReader(var posts: List<PostSummary>? = listOf(PostSummary("100000000001", "원주 카페 늘봄", 0, 1, 2, "요약", 3))) : BlogReader {
        var listCalls = 0
        override suspend fun listPosts(blogId: String, count: Int): List<PostSummary>? { listCalls++; return posts }
        override suspend fun readPost(blogId: String, logNo: String): PostText? = null
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
    /** 턴이 도는 동안 엔진이 리스너를 부르는 상황을 흉내 낸다(도구 호출 등). */
    private var onTurn: ((TurnListener) -> Unit)? = null
    private var viewModel: ChatViewModel? = null

    private val runner = object : TurnRunner {
        override suspend fun runTurn(ctx: ChatContext, listener: TurnListener): TurnResult {
            contexts += ctx
            partials.forEach { partial ->
                listener.onPartialSay(partial)
                observedStreaming += viewModel?.uiState?.value?.streamingSay
            }
            listener.onToolStatus("네이버에서 찾고 있어요…")
            onTurn?.invoke(listener)
            gate?.await()
            return turns.removeFirst()
        }
    }

    private val chatRepo = FakeChatRepository()
    private val pendingJobs = FakePendingJobRepository()
    private val memory = FakeMemoryRepository(listOf(MemoryItem(1, MemoryKind.STYLE, "존댓말로 써요", "chat", 0, true, null)))
    private val hook = RecordingPublishedHook()
    private var photos = FakePhotoAttachments()
    private val settings = FakeSettingsStore()

    private fun newViewModel(
        hasKey: Boolean = true,
        checker: UpdateChecker = FakeUpdateChecker(null),
        blog: BlogReader = FakeBlogReader(),
    ): ChatViewModel =
        ChatViewModel(chatRepo, runner, pendingJobs, photos, FakeApiKeyStore(hasKey), memory, hook, settings, checker, blog)
            .also { viewModel = it }

    private fun say(text: String) = TurnResult.Success(TurnResponse(say = text), emptyList(), "m")

    private fun planTurn() = TurnResult.Success(TurnResponse(say = "계획이에요", plan = PLAN, readyToDraft = true), emptyList(), "m")

    /** 품질 게이트를 그냥 통과하는 초안 — 게이트 자체를 보는 테스트는 [longPost] 로 길이를 정한다. */
    private fun post(title: String = "제목") = longPost(1300, title)

    /** 본문 [length] 자(공백 없음)짜리 초안. */
    private fun longPost(length: Int, title: String = "제목") =
        PostContent(title, listOf(Block.Paragraph(listOf(Run("가".repeat(length))))))

    private companion object {
        const val PLAN = "# 원주 한우 후기\n다른 제목: A / B\n\n## 글 구성\n1. 도입 — 왜 갔는지 (사진 img_001)"
        const val PLAN2 = "# 짧게 쓴 원주 한우\n다른 제목: A / B\n\n## 글 구성\n1. 도입"
    }

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ---- 새 글: 대화는 첫 메시지 때 만든다 ----

    /** 시작 화면(빈 채팅)이나 목록의 "새 글" 은 빈 대화를 만들지 않는다 — 회전으로 다시 열려도 마찬가지. */
    @Test
    fun openingANewPostDoesNotCreateASession() = runTest {
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()

        assertNull(vm.uiState.value.session)
        assertTrue(chatRepo.sessions.value.isEmpty())

        vm.open(null); advanceUntilIdle()
        assertTrue(chatRepo.sessions.value.isEmpty())
    }

    /** 첫 메시지를 보내면 그때 대화가 만들어지고, 메시지는 그 대화에 저장된다. */
    @Test
    fun theFirstSendCreatesTheSessionAndStoresTheMessageThere() = runTest {
        turns += TurnResult.Success(TurnResponse("좋아요"), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()

        vm.send("원주 한우 다녀왔어요"); advanceUntilIdle()

        val session = vm.uiState.value.session
        assertNotNull(session)
        assertEquals(listOf(session!!.id), chatRepo.sessions.value.map { it.id })
        assertEquals(
            listOf(MessageRole.USER to MessageKind.TEXT, MessageRole.ASSISTANT to MessageKind.TEXT),
            chatRepo.of(session.id).map { it.role to it.kind },
        )
        // 화면도 그 대화의 메시지를 보고 있어야 한다.
        assertEquals(chatRepo.of(session.id), vm.uiState.value.messages)
    }

    /** 말보다 사진을 먼저 붙여도 그때 대화가 만들어진다. */
    @Test
    fun theFirstAttachPhotosCreatesTheSession() = runTest {
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()

        vm.attachPhotos(listOf("a", "b")); advanceUntilIdle()

        val session = vm.uiState.value.session
        assertNotNull(session)
        assertEquals(listOf(session!!.id), chatRepo.sessions.value.map { it.id })
        assertEquals(listOf(MessageKind.PHOTOS), chatRepo.of(session.id).map { it.kind })
        assertEquals(listOf("a", "b"), vm.uiState.value.attachments.map { it.uri })
    }

    /** 화면이 다시 만들어져도(회전) 열려 있던 대화는 그대로다 — 첫 진입에만 연다. */
    @Test
    fun openInitialOnlyOpensOnce() = runTest {
        turns += TurnResult.Success(TurnResponse("좋아요"), emptyList(), "flash")
        val vm = newViewModel(); vm.openInitial(null); advanceUntilIdle()
        vm.send("원주 한우 다녀왔어요"); advanceUntilIdle()
        val sessionId = vm.uiState.value.session!!.id

        vm.openInitial(null); advanceUntilIdle()

        assertEquals(sessionId, vm.uiState.value.session?.id)
        assertEquals(1, chatRepo.sessions.value.size)
    }

    // ---- 새 버전 배너 (FR-12) ----

    @Test
    fun checkedAnHourAgoChecksAgain() = runTest {
        settings.lastCheckAt.value = System.currentTimeMillis() - 60 * 60 * 1000L
        val checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com"))

        val vm = newViewModel(checker = checker)
        advanceUntilIdle()

        assertEquals(1, checker.callCount)
        assertEquals(UpdateInfo("v9.9.9", "https://example.com"), vm.updateInfo.value)
    }

    @Test
    fun checkedMomentsAgoSkipsCheck() = runTest {
        settings.lastCheckAt.value = System.currentTimeMillis()
        val checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com"))

        val vm = newViewModel(checker = checker)
        advanceUntilIdle()

        assertEquals(0, checker.callCount)
        assertNull(vm.updateInfo.value)
    }

    @Test
    fun dismissedTagHidesBanner() = runTest {
        settings.dismissedTag.value = "v9.9.9"
        val checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com"))

        val vm = newViewModel(checker = checker)
        advanceUntilIdle()

        assertEquals(1, checker.callCount)
        assertNull(vm.updateInfo.value)
    }

    @Test
    fun newTagShowsBanner() = runTest {
        val checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com"))

        val vm = newViewModel(checker = checker)
        advanceUntilIdle()

        assertEquals(UpdateInfo("v9.9.9", "https://example.com"), vm.updateInfo.value)
    }

    @Test
    fun dismissUpdateClearsBannerAndRemembersTag() = runTest {
        val checker = FakeUpdateChecker(UpdateInfo("v9.9.9", "https://example.com"))
        val vm = newViewModel(checker = checker)
        advanceUntilIdle()
        assertEquals(UpdateInfo("v9.9.9", "https://example.com"), vm.updateInfo.value)

        vm.dismissUpdate()
        advanceUntilIdle()

        assertNull(vm.updateInfo.value)
        assertEquals("v9.9.9", settings.dismissedTag.value)
    }

    @Test
    fun sendStoresUserAndAssistantMessagesAndQuickReplies() = runTest {
        turns += TurnResult.Success(
            TurnResponse("오른쪽에 계획을 정리했어요.", plan = PLAN, quickReplies = listOf("더 짧게")),
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
        assertEquals(listOf("더 짧게"), vm.uiState.value.quickReplies)
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
        turns += TurnResult.Success(TurnResponse("늦게 온 답", plan = PLAN), emptyList(), "flash")
        gate = CompletableDeferred()
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()

        vm.send("첫 대화 이야기"); advanceUntilIdle()
        val first = vm.uiState.value.session!!.id
        assertTrue(vm.uiState.value.thinking)

        // "새 글" 로 옮긴다 — 빈 대화는 만들지 않으므로 목록에는 여전히 하나뿐이다.
        vm.open(null); advanceUntilIdle()
        assertNull(vm.uiState.value.session)
        assertEquals(listOf(first), chatRepo.sessions.value.map { it.id })

        gate!!.complete(Unit); advanceUntilIdle()

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

    /** 첫 턴의 계획은 오른쪽 패널로 가고, 계획 첫 줄이 대화 이름이 된다. */
    @Test
    fun theFirstPlanOpensThePanelAndNamesTheSession() = runTest {
        turns += TurnResult.Success(TurnResponse("오른쪽에 계획을 정리했어요.", plan = PLAN, readyToDraft = true), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.send("원주 한우 다녀왔어요"); advanceUntilIdle()

        assertEquals(PLAN, vm.uiState.value.plan)
        assertTrue(vm.uiState.value.panelOpen)
        assertTrue(vm.uiState.value.listCollapsed)
        assertNull(vm.uiState.value.panelJobId)
        assertEquals("원주 한우 후기", vm.uiState.value.session!!.title)
    }

    /** 피드백 턴에서는 계획 전체가 새로 오고, 모델에는 고치기 전 계획이 함께 실려 나간다. */
    @Test
    fun aFeedbackTurnReplacesThePlanAndSendsTheOldOneAlong() = runTest {
        turns += TurnResult.Success(TurnResponse("계획이에요", plan = PLAN, readyToDraft = true), emptyList(), "flash")
        turns += TurnResult.Success(TurnResponse("짧게 고쳤어요", plan = PLAN2, readyToDraft = true), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.send("원주 한우 다녀왔어요"); advanceUntilIdle()

        vm.send("더 짧게"); advanceUntilIdle()

        assertEquals(PLAN, contexts.last().currentPlan)
        assertEquals(PLAN2, vm.uiState.value.plan)
        // 계획 메시지는 갈아 끼우는 게 아니라 쌓이고, 패널은 늘 마지막 것을 그린다.
        assertEquals(2, vm.uiState.value.messages.count { it.kind == MessageKind.PLAN })
        // 이름은 처음 한 번만 붙는다 — 계획을 고쳐도 바뀌지 않는다.
        assertEquals("원주 한우 후기", vm.uiState.value.session!!.title)
    }

    /** 버튼을 누르면 초안 턴이 돌고 패널이 에디터로 넘어간다. 그때 계획도 함께 실려 나간다. */
    @Test
    fun theDraftButtonRunsADraftTurnAndHandsThePanelToTheEditor() = runTest {
        turns += TurnResult.Success(TurnResponse("계획이에요", plan = PLAN, readyToDraft = true), emptyList(), "flash")
        turns += TurnResult.Success(TurnResponse("초안이에요", post = post("제목")), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.send("원주 한우 다녀왔어요"); advanceUntilIdle()

        vm.requestDraft(); advanceUntilIdle()

        assertTrue(contexts.last().draftTurn)
        assertEquals(PLAN, contexts.last().currentPlan)
        assertNotNull(vm.uiState.value.panelJobId)
        assertTrue(vm.uiState.value.panelOpen)
        // 계획은 기록에 남지만, 초안이 생긴 뒤 오른쪽 자리는 에디터가 가져간다.
        assertEquals(PLAN, vm.uiState.value.plan)
    }

    /** 계획 단계에서 모델이 성급하게 post 를 내면 버린다 — 초안은 입력창 위 고정 버튼으로만 시작한다. */
    @Test
    fun earlyPostOnPlanningTurnIsIgnoredAndTheDraftButtonTakesOver() = runTest {
        turns += TurnResult.Success(TurnResponse("계획을 고쳤어요", plan = PLAN, quickReplies = listOf("다른 제목"), post = post("성급한 초안")), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.send("더 짧게"); advanceUntilIdle()

        assertFalse(contexts.single().draftTurn)
        assertNull(vm.uiState.value.panelJobId)
        assertTrue(pendingJobs.jobs.value.isEmpty())
        assertTrue(vm.uiState.value.messages.none { it.kind == MessageKind.POST })
        // 칩은 모델이 준 것 그대로 — 초안 칩을 끼워 넣지 않는다.
        assertEquals(listOf("다른 제목"), vm.uiState.value.quickReplies)
        // 초안 버튼이 보일 조건: 계획이 있고 아직 초안이 없다.
        assertNotNull(vm.uiState.value.plan)
        assertNull(vm.uiState.value.panelJobId)
    }

    /** 초안이 생긴 뒤에도 칩은 모델이 준 그대로이고, 같은 내용이면 에디터를 다시 채우지 않는다. */
    @Test
    fun quickRepliesArePassedThroughAndIdenticalPostDoesNotReinject() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", readyToDraft = true, quickReplies = listOf("더 짧게"), post = post("제목")), emptyList(), "flash")
        turns += TurnResult.Success(TurnResponse("같은 초안이에요", readyToDraft = true, post = post("제목")), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        val reinjects = mutableListOf<PostContent>()
        val collector = launch { vm.reinject.collect { reinjects += it } }

        vm.requestDraft(); advanceUntilIdle()
        assertEquals(listOf("더 짧게"), vm.uiState.value.quickReplies)

        vm.requestDraft(); advanceUntilIdle()
        assertTrue(reinjects.isEmpty())
        assertEquals(1, pendingJobs.jobs.value.size)
        assertTrue(vm.uiState.value.panelOpen)
        collector.cancel()
    }

    /** 초안이 이미 있으면 초안 버튼(또는 모델이 잘못 보낸 칩)으로 초안 턴을 다시 열지 않는다. */
    @Test
    fun requestDraftIsIgnoredOnceADraftExists() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", post = post("제목")), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()
        assertNotNull(vm.uiState.value.panelJobId)
        vm.sendQuickReply(ChatViewModel.DRAFT_CHIP); advanceUntilIdle()
        assertEquals(1, contexts.size)
    }

    /** 패널에서 계획을 직접 고치면 그것이 새 계획이 되고, 다음 턴에 모델에게도 그대로 실려 나간다. */
    @Test
    fun editingThePlanDirectlyReplacesItAndReachesTheModel() = runTest {
        turns += TurnResult.Success(TurnResponse("계획이에요", plan = PLAN, readyToDraft = true), emptyList(), "flash")
        turns += TurnResult.Success(TurnResponse("알겠어요", readyToDraft = true), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.send("원주 한우 다녀왔어요"); advanceUntilIdle()

        val edited = "# 원주 한우 후기\n\n## 글 구성\n\n1. 도입 — 사용자가 직접 고친 줄"
        vm.savePlanEdit(edited); advanceUntilIdle()

        assertEquals(edited, vm.uiState.value.plan)
        // 고친 계획은 사용자 몫의 PLAN 메시지로 쌓인다 — 원래 계획도 기록에 남는다.
        val plans = vm.uiState.value.messages.filter { it.kind == MessageKind.PLAN }
        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER), plans.map { it.role })
        // 이름은 처음 한 번만 붙는다 — 고쳐도 그대로다.
        assertEquals("원주 한우 후기", vm.uiState.value.session!!.title)

        vm.send("이대로 가요"); advanceUntilIdle()
        assertEquals(edited, contexts.last().currentPlan)
    }

    /** 계획을 세워 둔 대화를 다시 열면 오른쪽 패널이 바로 펼쳐진다 — "보기"를 다시 누르지 않게. */
    @Test
    fun openingASessionWithAPlanOpensThePanel() = runTest {
        turns += TurnResult.Success(TurnResponse("계획이에요", plan = PLAN, readyToDraft = true), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.send("원주 한우 다녀왔어요"); advanceUntilIdle()
        val sessionId = vm.uiState.value.session!!.id

        val reopened = newViewModel(); reopened.open(sessionId); advanceUntilIdle()

        assertTrue(reopened.uiState.value.panelOpen)
        assertTrue(reopened.uiState.value.listCollapsed)
        assertEquals(PLAN, reopened.uiState.value.plan)
    }

    /** 이어 쓰던 초안이 있는 대화도 마찬가지 — 열자마자 에디터가 오른쪽에 뜬다. */
    @Test
    fun openingASessionWithADraftOpensThePanel() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", post = post()), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()
        val sessionId = vm.uiState.value.session!!.id
        val jobId = vm.uiState.value.panelJobId

        val reopened = newViewModel(); reopened.open(sessionId); advanceUntilIdle()

        assertEquals(jobId, reopened.uiState.value.panelJobId)
        assertTrue(reopened.uiState.value.panelOpen)
        assertTrue(reopened.uiState.value.listCollapsed)
    }

    /** 아무것도 세워 두지 않은 대화는 전처럼 채팅만 보인다. */
    @Test
    fun openingASessionWithoutAPlanOrDraftLeavesThePanelClosed() = runTest {
        turns += TurnResult.Success(TurnResponse("좋아요"), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.send("원주 한우 다녀왔어요"); advanceUntilIdle()
        val sessionId = vm.uiState.value.session!!.id

        val reopened = newViewModel(); reopened.open(sessionId); advanceUntilIdle()

        assertFalse(reopened.uiState.value.panelOpen)
    }

    /** 계획 패널 이전 형식({titleCandidates, outline, tone})으로 저장된 계획도 마크다운으로 읽힌다. */
    @Test
    fun legacyStructuredPlanPayloadIsReadAsMarkdown() {
        val md = ChatPayloads.readPlan("""{"titleCandidates":["첫 제목","둘째"],"outline":[{"heading":"가는 길","summary":"주차 팁"}],"tone":"다정하게"}""")
        val expected = listOf("# 첫 제목", "다른 제목: 둘째", "", "## 글 구성", "1. 가는 길 — 주차 팁", "", "## 말투와 분위기", "다정하게").joinToString("\n") + "\n"
        assertEquals(expected, md)
    }

    /** 네이버 로그인 여부(blogId)가 화면 상태에 반영돼 입력창 아래 안내가 뜬다. */
    @Test
    fun loggedInFollowsStoredBlogId() = runTest {
        settings.blogIdFlow.value = null
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        assertFalse(vm.uiState.value.loggedIn)
        settings.setBlogId("myblog"); advanceUntilIdle()
        assertTrue(vm.uiState.value.loggedIn)
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

    /** 초안이 나온 뒤 붙인 사진은 에디터에 올라가 있지 않아 다음 주입이 통째로 깨진다 — SP2 에서는 막는다. */
    @Test
    fun attachingPhotosAfterADraftIsRefused() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", post = post()), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.attachPhotos(listOf("a")); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()
        assertNotNull(vm.uiState.value.panelJobId)

        vm.attachPhotos(listOf("b")); advanceUntilIdle()

        assertEquals(listOf("a"), vm.uiState.value.attachments.map { it.uri })
        assertEquals(ChatViewModel.NO_PHOTO_AFTER_DRAFT, vm.uiState.value.error)
    }

    /** 사진 메시지를 그대로 이어 붙이면 뺀 사진이 돌아오고 ref 가 겹친다 — uri 로 걸러 번호를 다시 매긴다. */
    @Test
    fun reopeningASessionRestoresUniqueRefsAndUris() = runTest {
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.attachPhotos(listOf("u1", "u2")); advanceUntilIdle()
        val sessionId = vm.uiState.value.session!!.id
        vm.removePhoto("img_001")
        vm.attachPhotos(listOf("u3")); advanceUntilIdle()

        val reopened = newViewModel(); reopened.open(sessionId); advanceUntilIdle()

        val restored = reopened.uiState.value.attachments
        val refs = restored.map { it.ref }
        val uris = restored.map { it.uri }
        assertEquals(refs.size, refs.distinct().size)
        assertEquals(uris.size, uris.distinct().size)
        assertEquals((1..refs.size).map { "img_%03d".format(it) }, refs)
    }

    /** 세션이 가리키던 작업이 사라졌으면(완료·삭제) 연결을 끊는다 — 안 그러면 "초안 열기"가 매번 실패한다. */
    @Test
    fun openingASessionWhoseJobVanishedDetachesIt() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", post = post()), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()
        val sessionId = vm.uiState.value.session!!.id
        val jobId = vm.uiState.value.panelJobId!!
        pendingJobs.delete(jobId)

        val reopened = newViewModel(); reopened.open(sessionId); advanceUntilIdle()

        assertNull(reopened.uiState.value.panelJobId)
        assertNull(reopened.uiState.value.session!!.pendingJobId)
        assertEquals(SessionStatus.DRAFTING, reopened.uiState.value.session!!.status)
        assertNull(chatRepo.getSession(sessionId)!!.pendingJobId)
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

    /** 지금 열려 있는 대화를 지우면 돌던 턴이 취소되고, 딸린 것들(메시지·발행 작업·사진 캐시)이 함께 사라지며,
     *  남은 대화 중 가장 최근 것으로 옮겨 간다. */
    @Test
    fun deletingASessionRemovesItsMessagesJobAndSwitchesAway() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", post = post()), emptyList(), "flash")
        turns += TurnResult.Success(TurnResponse("다른 이야기네요"), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()
        val sessionId = vm.uiState.value.session!!.id
        val jobId = vm.uiState.value.panelJobId!!
        assertTrue(chatRepo.of(sessionId).isNotEmpty())

        // 다른 대화를 하나 더 만든다 — 지운 뒤 이리로 옮겨 가야 한다.
        vm.open(null); advanceUntilIdle()
        vm.send("다른 이야기예요"); advanceUntilIdle()
        val otherId = vm.uiState.value.session!!.id
        assertNotEquals(sessionId, otherId)
        vm.open(sessionId); advanceUntilIdle()     // 지울 대화를 다시 연다.

        // 지우는 순간 다른 턴이 돌고 있었으면 그 턴은 취소되어야 한다 — 안 그러면 늦게 온 답이
        // 이미 사라진 대화 id로 다시 메시지를 만들어 낸다.
        gate = CompletableDeferred()
        vm.send("문단 2를 더 짧게")
        assertTrue(vm.uiState.value.thinking)

        vm.deleteSession(sessionId); advanceUntilIdle()
        gate!!.complete(Unit); advanceUntilIdle()

        assertNull(chatRepo.getSession(sessionId))
        assertTrue(chatRepo.of(sessionId).isEmpty())
        assertNull(pendingJobs.get(jobId))
        assertEquals(listOf(sessionId), photos.cleared)
        assertEquals(otherId, vm.uiState.value.session!!.id)
        assertFalse(vm.uiState.value.thinking)
    }

    /** 이름을 바꾸면 그대로 남아야 한다 — 다음 초안이 다른 제목을 내도 자동 제목이 덮어써서는 안 된다. */
    @Test
    fun renamingKeepsOrderAndIsNotOverwrittenByAutoTitle() = runTest {
        turns += TurnResult.Success(TurnResponse("좋아요", post = post("첫 제목")), emptyList(), "flash")
        turns += TurnResult.Success(TurnResponse("고쳤어요", post = post("둘째 제목")), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()

        vm.requestDraft(); advanceUntilIdle()
        val sessionId = vm.uiState.value.session!!.id
        assertEquals("첫 제목", vm.uiState.value.session!!.title)

        vm.renameSession(sessionId, "  내가 지은 이름  "); advanceUntilIdle()
        assertEquals("내가 지은 이름", vm.uiState.value.session!!.title)
        assertEquals("내가 지은 이름", chatRepo.getSession(sessionId)!!.title)

        vm.send("문단 2를 더 짧게"); advanceUntilIdle()
        assertEquals("내가 지은 이름", vm.uiState.value.session!!.title)
        assertEquals("내가 지은 이름", chatRepo.getSession(sessionId)!!.title)

        // 빈 이름은 무시한다.
        vm.renameSession(sessionId, "   "); advanceUntilIdle()
        assertEquals("내가 지은 이름", vm.uiState.value.session!!.title)
    }
    // ---- 사실 확인 질문 (계획 전, 최대 4회) ----

    /** 질문 턴이 이어지는 동안 questionRounds 가 오르고, 계획이 나온 뒤로는 초안 버튼 조건이 살아난다. */
    @Test
    fun questionTurnsCountUpAndKeepTheDraftButtonHidden() = runTest {
        turns += TurnResult.Success(TurnResponse("어디 다녀오셨어요?", question = "상호가 어떻게 되나요?", readyToDraft = true), emptyList(), "flash")
        turns += TurnResult.Success(TurnResponse("하나만 더요.", question = "얼마였나요?", quickReplies = listOf("잘 모르겠어요")), emptyList(), "flash")
        turns += TurnResult.Success(TurnResponse("계획이에요", plan = PLAN, readyToDraft = true), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()

        vm.send("원주 한우 다녀왔어요"); advanceUntilIdle()
        // 질문 턴에서는 계획도 초안 버튼 조건도 없다. 모델이 readyToDraft 를 보내도 무시한다.
        assertNull(vm.uiState.value.plan)
        assertTrue(ChatPayloads.readText(vm.uiState.value.messages.last().payloadJson).contains("상호가 어떻게 되나요?"))

        vm.send("봄들식당이에요"); advanceUntilIdle()
        vm.send("2만원쯤이요"); advanceUntilIdle()

        assertEquals(listOf(0, 1, 2), contexts.map { it.questionRounds })
        assertEquals(4, contexts.first().maxQuestionRounds)
        assertEquals(PLAN, vm.uiState.value.plan)
    }

    /** 질문 턴 뒤 초안을 요청해도 초안 턴은 열린다 — readyToDraft 는 send() 의 문구 판정에만 쓰인다. */
    @Test
    fun aQuestionTurnDoesNotMarkTheSessionReadyToDraft() = runTest {
        turns += TurnResult.Success(TurnResponse("여쭤볼게요", question = "어디였나요?", readyToDraft = true), emptyList(), "flash")
        turns += TurnResult.Success(TurnResponse("네", plan = PLAN), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.send("다녀왔어요"); advanceUntilIdle()

        // "초안 써 줘" 라고 해도 아직 초안 턴이 아니다.
        vm.send("초안 써 줘"); advanceUntilIdle()
        assertFalse(contexts.last().draftTurn)
    }

    // ---- 품질 게이트 ----

    /** 본문이 짧으면 에디터로 넘기지 않고 카드를 띄운다. */
    @Test
    fun aShortDraftIsHeldAtTheGate() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", post = longPost(1000)), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()

        val gate = vm.uiState.value.draftGate
        assertNotNull(gate)
        assertEquals(listOf("본문이 1,000자예요. 목표는 1,200~1,800자예요."), gate!!.issues)
        assertEquals("본문을 1,200~1,800자로 맞춰 주세요.", gate.request)
        assertNull(vm.uiState.value.panelJobId)
        assertTrue(pendingJobs.jobs.value.isEmpty())
        assertTrue(vm.uiState.value.messages.none { it.kind == MessageKind.POST })
    }

    /** 허용 오차(±10%) 안이면 게이트 없이 바로 에디터로 간다. */
    @Test
    fun aDraftInsideTheToleranceGoesStraightThrough() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", post = longPost(1100)), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()

        assertNull(vm.uiState.value.draftGate)
        assertNotNull(vm.uiState.value.panelJobId)
        assertEquals(1, pendingJobs.jobs.value.size)
    }

    /** 엔진이 사진 자리를 손댔으면(누락·중복) 길이가 맞아도 한 번 물어본다. */
    @Test
    fun photoRepairsAlsoHoldTheDraft() = runTest {
        turns += TurnResult.Success(
            TurnResponse("초안이에요", post = longPost(1300)),
            listOf("누락 사진 추가: img_003", "중복 사진 제거: img_002", "제목 보정"),
            "flash",
        )
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()

        val gate = vm.uiState.value.draftGate!!
        assertEquals(
            listOf("사진 img_003 은 글에 없어서 맨 끝에 붙였어요.", "사진 img_002 이 두 번 나와서 한 번만 남겼어요."),
            gate.issues,
        )
        assertEquals("사진 img_003 은 어울리는 자리에 넣어 주세요. 사진 img_002 은 한 번만 써 주세요.", gate.request)
    }

    /** "이대로 넣기" — 잡아 뒀던 초안을 그대로 저장하고 패널을 연다. */
    @Test
    fun acceptingTheGateSavesTheJobAndOpensThePanel() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", post = longPost(1000, "짧은 초안")), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()
        assertNotNull(vm.uiState.value.draftGate)

        vm.acceptDraftGate(); advanceUntilIdle()

        assertNull(vm.uiState.value.draftGate)
        assertNotNull(vm.uiState.value.panelJobId)
        assertTrue(vm.uiState.value.panelOpen)
        assertEquals("짧은 초안", pendingJobs.jobs.value.single().content.title)
        assertEquals(1, vm.uiState.value.messages.count { it.kind == MessageKind.POST })
    }

    /** "고쳐 달라고 하기" — 고쳐 달라는 말이 사용자 메시지로 나가고, 초안이 없으니 초안 턴이 다시 돈다. */
    @Test
    fun askingForAFixSendsTheRequestAsTheNextTurn() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", post = longPost(1000)), emptyList(), "flash")
        turns += TurnResult.Success(TurnResponse("길게 고쳤어요", post = longPost(1500)), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()

        vm.fixDraftGate(); advanceUntilIdle()

        assertNull(vm.uiState.value.draftGate)
        assertTrue(contexts.last().draftTurn)
        val sent = vm.uiState.value.messages.filter { it.role == MessageRole.USER }.map { ChatPayloads.readText(it.payloadJson) }
        assertTrue(sent.toString(), sent.contains("본문을 1,200~1,800자로 맞춰 주세요."))
        assertNotNull(vm.uiState.value.panelJobId)
    }

    /** 초안이 이미 있으면 고쳐 달라는 턴은 수정 턴이다 — 지금 초안이 함께 실려 나간다. */
    @Test
    fun askingForAFixOnAnExistingDraftIsARevisionTurn() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", post = longPost(1500, "첫 제목")), emptyList(), "flash")
        turns += TurnResult.Success(TurnResponse("고쳤어요", post = longPost(1000, "둘째 제목")), emptyList(), "flash")
        turns += TurnResult.Success(TurnResponse("다시 고쳤어요", post = longPost(1500, "셋째 제목")), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()
        vm.send("더 짧게"); advanceUntilIdle()
        assertNotNull(vm.uiState.value.draftGate)

        vm.fixDraftGate(); advanceUntilIdle()

        assertFalse(contexts.last().draftTurn)
        assertEquals("첫 제목", contexts.last().currentPost?.title)
        assertEquals("셋째 제목", pendingJobs.jobs.value.single().content.title)
    }

    /** 다음 턴이 시작되면 잡아 뒀던 게이트는 버린다 — 턴마다 새로 계산한다. */
    @Test
    fun aNewTurnClearsThePendingGate() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", post = longPost(1000)), emptyList(), "flash")
        turns += TurnResult.Success(TurnResponse("계획이에요", plan = PLAN), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.requestDraft(); advanceUntilIdle()
        assertNotNull(vm.uiState.value.draftGate)

        vm.send("아니요 다시 얘기해요"); advanceUntilIdle()
        assertNull(vm.uiState.value.draftGate)
    }

    // ---- 사진 묶기 ----

    /** 두 장을 골라 묶으면 화면과 대화에 남고, 다시 열어도 그대로다. */
    @Test
    fun groupingTwoPhotosIsKeptAndRestored() = runTest {
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.attachPhotos(listOf("a", "b", "c")); advanceUntilIdle()

        vm.startGrouping()
        vm.toggleGroupPick("img_001")
        vm.toggleGroupPick("img_003")
        assertEquals(listOf("img_001", "img_003"), vm.uiState.value.groupPicks)

        vm.finishGrouping(); advanceUntilIdle()

        assertEquals(listOf(listOf("img_001", "img_003")), vm.uiState.value.photoGroups)
        assertNull(vm.uiState.value.groupPicks)
        val sessionId = vm.uiState.value.session!!.id
        assertEquals(listOf(MessageKind.PHOTOS, MessageKind.PHOTO_GROUPS), chatRepo.of(sessionId).map { it.kind })

        val reopened = newViewModel(); reopened.open(sessionId); advanceUntilIdle()
        assertEquals(listOf(listOf("img_001", "img_003")), reopened.uiState.value.photoGroups)
    }

    /** 묶음은 2~4장 — 다섯째 사진은 골라지지 않고, 이미 묶인 사진도 다시 골라지지 않는다. */
    @Test
    fun aGroupTakesTwoToFourPhotos() = runTest {
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.attachPhotos(listOf("a", "b", "c", "d", "e")); advanceUntilIdle()

        vm.startGrouping()
        (1..5).forEach { vm.toggleGroupPick("img_%03d".format(it)) }
        assertEquals(listOf("img_001", "img_002", "img_003", "img_004"), vm.uiState.value.groupPicks)
        vm.finishGrouping(); advanceUntilIdle()

        // 이미 묶인 사진은 다음 묶기에서 고를 수 없다.
        vm.startGrouping()
        vm.toggleGroupPick("img_001")
        vm.toggleGroupPick("img_005")
        assertEquals(listOf("img_005"), vm.uiState.value.groupPicks)
        // 한 장뿐이면 묶음이 생기지 않는다.
        vm.finishGrouping(); advanceUntilIdle()
        assertEquals(1, vm.uiState.value.photoGroups.size)
    }

    /** 묶은 사진을 빼면 묶음에서도 빠지고(번호는 다시 매겨진다), 한 장만 남으면 묶음이 풀린다. */
    @Test
    fun removingAPhotoShrinksThenDissolvesTheGroup() = runTest {
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.attachPhotos(listOf("a", "b", "c")); advanceUntilIdle()
        vm.startGrouping()
        listOf("img_001", "img_002", "img_003").forEach { vm.toggleGroupPick(it) }
        vm.finishGrouping(); advanceUntilIdle()

        vm.removePhoto("img_002"); advanceUntilIdle()
        // a·c 가 남아 img_001·img_002 로 다시 매겨진다.
        assertEquals(listOf(listOf("img_001", "img_002")), vm.uiState.value.photoGroups)

        vm.removePhoto("img_001"); advanceUntilIdle()
        assertEquals(emptyList<List<String>>(), vm.uiState.value.photoGroups)
    }

    /** 묶음을 풀면 화면에서도 대화에서도 사라진다. */
    @Test
    fun ungroupingRemovesTheGroup() = runTest {
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.attachPhotos(listOf("a", "b")); advanceUntilIdle()
        vm.startGrouping()
        vm.toggleGroupPick("img_001"); vm.toggleGroupPick("img_002")
        vm.finishGrouping(); advanceUntilIdle()

        vm.ungroup(0); advanceUntilIdle()

        assertEquals(emptyList<List<String>>(), vm.uiState.value.photoGroups)
        val sessionId = vm.uiState.value.session!!.id
        val last = chatRepo.of(sessionId).last { it.kind == MessageKind.PHOTO_GROUPS }
        assertEquals(emptyList<List<String>>(), ChatPayloads.readPhotoGroups(last.payloadJson))
    }

    /** 초안 턴에는 사용자가 묶어 둔 사진이 함께 실려 나간다. */
    @Test
    fun theDraftContextCarriesThePhotoGroups() = runTest {
        turns += TurnResult.Success(TurnResponse("초안이에요", post = post()), emptyList(), "flash")
        val vm = newViewModel(); vm.open(null); advanceUntilIdle()
        vm.attachPhotos(listOf("a", "b")); advanceUntilIdle()
        vm.startGrouping()
        vm.toggleGroupPick("img_001"); vm.toggleGroupPick("img_002")
        vm.finishGrouping(); advanceUntilIdle()

        vm.requestDraft(); advanceUntilIdle()

        assertEquals(listOf(listOf("img_001", "img_002")), contexts.single().photoGroups)
    }

    // ---- 조언 모드 ----

    @Test
    fun adviceSessionIsCreatedWithModeAndReadsPostListOnce() = runTest {
        turns += say("어떤 글을 볼까요?"); turns += say("읽어 볼게요")
        val blog = FakeBlogReader()
        val vm = newViewModel(blog = blog)
        vm.openInitial(null); advanceUntilIdle()
        vm.setMode(SessionMode.ADVICE)
        vm.send("최근 글 봐 줘"); advanceUntilIdle()

        val session = chatRepo.sessions.value.single()
        assertEquals(SessionMode.ADVICE, session.mode)
        assertEquals(1, blog.listCalls)
        val kinds = chatRepo.of(session.id).map { it.kind }
        assertEquals(MessageKind.BLOG_POSTS, kinds.first())                       // 목록이 첫 메시지보다 먼저 저장된다
        assertEquals(SessionMode.ADVICE, contexts.last().mode)
        assertEquals("원주 카페 늘봄", contexts.last().blogPosts!!.single().title)
        assertEquals("최근 글 봐 줘", session.title)                             // 조언 세션 제목 = 첫 말

        vm.send("다른 글도"); advanceUntilIdle()
        assertEquals(1, blog.listCalls)                                        // 둘째 턴은 저장된 목록을 쓴다
        assertEquals("원주 카페 늘봄", contexts.last().blogPosts!!.single().title)
    }

    @Test
    fun adviceFirstTurnSurvivesPostListFailure() = runTest {
        turns += say("무슨 글인지 알려 주세요")
        val vm = newViewModel(blog = FakeBlogReader(posts = null))
        vm.openInitial(null); advanceUntilIdle()
        vm.setMode(SessionMode.ADVICE)
        vm.send("칼국수 글 어때?"); advanceUntilIdle()

        val session = chatRepo.sessions.value.single()
        assertTrue(chatRepo.of(session.id).any { it.kind == MessageKind.SYSTEM && ChatPayloads.readText(it.payloadJson) == ChatViewModel.POSTS_FAILED })
        assertNull(contexts.last().blogPosts)
        assertEquals("무슨 글인지 알려 주세요", ChatPayloads.readText(chatRepo.of(session.id).last { it.kind == MessageKind.TEXT }.payloadJson))
    }

    @Test
    fun adviceNeedsLoginToSend() = runTest {
        settings.blogIdFlow.value = null
        val vm = newViewModel()
        vm.openInitial(null); advanceUntilIdle()
        vm.setMode(SessionMode.ADVICE)
        vm.send("봐 줘"); advanceUntilIdle()
        assertTrue(chatRepo.sessions.value.isEmpty())
        assertEquals(ChatViewModel.ADVICE_NEEDS_LOGIN, vm.uiState.value.error)
        assertTrue(contexts.isEmpty())
    }

    @Test
    fun postReadOpensPanelAndIsRestoredOnReopen() = runTest {
        turns += say("읽었어요")
        partials = emptyList()
        val vm = newViewModel()
        vm.openInitial(null); advanceUntilIdle()
        vm.setMode(SessionMode.ADVICE)
        // 엔진이 도구를 돌리다 onPostRead 를 부르는 상황을 흉내 낸다.
        onTurn = { listener -> listener.onPostRead("100000000001", "원주 카페 늘봄") }
        vm.send("늘봄 글 봐 줘"); advanceUntilIdle()

        assertEquals(PostView("100000000001", "원주 카페 늘봄"), vm.uiState.value.focusedPost)
        assertTrue(vm.uiState.value.panelOpen); assertTrue(vm.uiState.value.hasPanel)
        val session = chatRepo.sessions.value.single()
        assertTrue(chatRepo.of(session.id).any { it.kind == MessageKind.POST_VIEW })

        vm.open(null); advanceUntilIdle()
        vm.open(session.id); advanceUntilIdle()
        assertEquals(SessionMode.ADVICE, vm.uiState.value.mode)
        assertEquals("원주 카페 늘봄", vm.uiState.value.focusedPost?.title)
        assertTrue(vm.uiState.value.panelOpen)
    }

    @Test
    fun adviceSessionIgnoresWriteOnlyFeatures() = runTest {
        turns += say("네")
        val vm = newViewModel()
        vm.openInitial(null); advanceUntilIdle()
        vm.setMode(SessionMode.ADVICE)
        vm.send("안녕"); advanceUntilIdle()
        vm.attachPhotos(listOf("content://a")); advanceUntilIdle()
        assertTrue(vm.uiState.value.attachments.isEmpty())
        vm.requestDraft(); advanceUntilIdle()
        assertEquals(1, contexts.size)                                         // 초안 턴이 돌지 않았다
        assertNull(vm.uiState.value.plan); assertNull(vm.uiState.value.draftGate)
        // 모드는 세션이 생긴 뒤 바뀌지 않는다.
        vm.setMode(SessionMode.WRITE)
        assertEquals(SessionMode.ADVICE, vm.uiState.value.mode)
    }

    @Test
    fun writeSessionKeepsPostListUntouched() = runTest {
        turns += planTurn()
        val blog = FakeBlogReader()
        val vm = newViewModel(blog = blog)
        vm.openInitial(null); advanceUntilIdle()
        vm.send("원주 한우 다녀왔어"); advanceUntilIdle()
        assertEquals(0, blog.listCalls)
        assertEquals(SessionMode.WRITE, contexts.last().mode)
    }
}
