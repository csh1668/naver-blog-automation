package com.csh.blogwriter.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.blog.BlogReader
import com.csh.blogwriter.chat.AttachedPhoto
import com.csh.blogwriter.chat.ChatContext
import com.csh.blogwriter.chat.PhotoAttachments
import com.csh.blogwriter.chat.PostContentRepair
import com.csh.blogwriter.chat.PublishedHook
import com.csh.blogwriter.chat.TurnListener
import com.csh.blogwriter.chat.TurnResponse
import com.csh.blogwriter.chat.TurnResult
import com.csh.blogwriter.chat.TurnRunner
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.data.repo.ChatRepository
import com.csh.blogwriter.data.repo.ChatSession
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
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.update.UpdateChecker
import com.csh.blogwriter.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.ceil

/**
 * 채팅형 글쓰기의 상태. 한 번에 한 턴만 돌리고(엔진의 키 로테이터가 직렬 실행을 전제한다),
 * 어시스턴트가 `post` 를 내면 발행 작업을 만들어 오른쪽 패널을 연다. 발행 버튼은 사용자가 직접 누른다.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    private val runner: TurnRunner,
    private val pendingJobs: PendingJobRepository,
    private val photoAttachments: PhotoAttachments,
    private val keyStore: ApiKeyStore,
    private val memory: MemoryRepository,
    private val publishedHook: PublishedHook,
    private val settings: SettingsStore,
    private val updateChecker: UpdateChecker,
    private val blog: BlogReader,
) : ViewModel() {

    companion object {
        const val RETRY_CHIP = "다시 시도"
        /** 입력창 위 고정 버튼의 이름이자, 눌렀을 때 대화에 남는 말. */
        const val DRAFT_CHIP = "이대로 초안 써 줘"
        const val NO_KEY = "글을 쓰려면 관리자가 열쇠를 등록해야 해요"
        const val NO_PHOTO_AFTER_DRAFT = "초안을 만든 뒤에는 사진을 더 붙일 수 없어요. 새 글에서 이어 가 주세요."
        /** 조언은 내 블로그 글을 읽어야 하므로 로그인(blogId)이 먼저다. */
        const val ADVICE_NEEDS_LOGIN = "조언은 네이버 로그인 후에 받을 수 있어요"
        const val POSTS_FAILED = "글 목록을 읽지 못했어요. 네이버 로그인 상태를 확인해 주세요."
        const val READING_POSTS = "최근 글을 읽고 있어요"
        /** 조언 세션 이름은 첫 말을 이만큼만 잘라 쓴다. */
        const val ADVICE_TITLE_MAX = 24
        /** 품질 게이트 카드의 제목과 버튼. */
        const val GATE_TITLE = "초안을 넣기 전에 확인해 주세요"
        const val GATE_ACCEPT = "이대로 넣기"
        const val GATE_FIX = "고쳐 달라고 하기"
        /** 목표 글자 수에서 이만큼은 봐준다. */
        private const val LENGTH_TOLERANCE = 0.1
        /** 한 묶음에 넣을 수 있는 사진 수. */
        const val MIN_GROUP = 2
        const val MAX_GROUP = 4
        /** 새 버전 확인은 이 간격 안에서는 건너뛴다 (FR-12). */
        /** 앱을 켤 때마다 확인하되, 설정 화면을 오가며 ViewModel 이 다시 만들어질 때의 연속 호출만 막는다. */
        private const val UPDATE_CHECK_INTERVAL_MS = 10 * 60 * 1000L
        private val DRAFT_WORDS = Regex("초안|써 줘|작성해")
        /** 사진을 붙일 수 있는 모드 — 글쓰기와 자유. */
        val PHOTO_MODES = setOf(SessionMode.WRITE, SessionMode.FREE)
        /** 말투 기억을 함께 보내는 모드 — 조언도 "출발점"으로 쓴다. 자유 대화는 말투에 매이지 않는다. */
        val STYLE_MODES = setOf(SessionMode.WRITE, SessionMode.ADVICE)
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    val sessions: StateFlow<List<ChatSession>> =
        chatRepo.observeSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 패널이 이미 열려 있을 때 수정본을 에디터에 다시 넣어 달라는 요청 — 화면이 PublishViewModel 로 전달한다. */
    private val _reinject = MutableSharedFlow<PostContent>(extraBufferCapacity = 4)
    val reinject: SharedFlow<PostContent> = _reinject

    /** 새 버전 배너 (FR-12). 켤 때마다 확인한다(10분 안의 연속 호출만 생략). */
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private var messagesJob: Job? = null
    /** 돌고 있는 턴. 대화를 바꾸면 취소한다 — 늦게 온 답이 엉뚱한 대화에 붙지 않도록. */
    private var turnJob: Job? = null
    private var readyToDraft = false
    private var lastDraftTurn = false
    /** 화면이 처음 붙을 때만 대화를 연다 — 회전으로 다시 만들어져도 보던 대화를 잃지 않게. */
    private var openedOnce = false

    init {
        viewModelScope.launch {
            keyStore.hasUsableKey.collect { has -> _uiState.update { it.copy(hasKey = has) } }
        }
        viewModelScope.launch {
            settings.blogId.collect { id -> _uiState.update { it.copy(loggedIn = id != null, blogId = id) } }
        }
        checkUpdateIfDue()
    }

    /** 화면이 붙을 때마다 부른다 — 설정에서 새 버전을 찾고 돌아와도 배너가 뜨게. 10분 안의 연속 호출은 건너뛴다. */
    fun checkUpdateIfDue() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (now - settings.lastUpdateCheckAtOnce() < UPDATE_CHECK_INTERVAL_MS) return@launch
            settings.setLastUpdateCheckAt(now)
            val info = updateChecker.checkForUpdate() ?: return@launch
            if (info.tag != settings.dismissedUpdateTagOnce()) _updateInfo.value = info
        }
    }

    fun dismissUpdate() {
        val tag = _updateInfo.value?.tag ?: return
        _updateInfo.value = null
        viewModelScope.launch { settings.setDismissedUpdateTag(tag) }
    }

    /** 화면이 처음 붙을 때 한 번. 회전으로 화면이 다시 만들어져도 열려 있던 대화를 그대로 둔다. */
    fun openInitial(sessionId: String?) {
        if (openedOnce) return
        open(sessionId)
    }

    /**
     * [sessionId] 가 null 이면 "새 글" 빈 상태로 둔다 — 대화는 첫 메시지(또는 첫 사진)를 보낼 때 만든다.
     * 미리 만들면 아무것도 쓰지 않은 빈 대화가 목록에 쌓인다.
     */
    fun open(sessionId: String?) {
        if (sessionId != null && _uiState.value.session?.id == sessionId) return
        // 이전 대화의 턴은 여기서 끝낸다. 취소된 턴은 아무것도 저장하지 않는다.
        turnJob?.cancel()
        turnJob = null
        readyToDraft = false
        lastDraftTurn = false
        openedOnce = true
        messagesJob?.cancel()
        messagesJob = null
        if (sessionId == null) {
            _uiState.value = emptyState()
            return
        }
        viewModelScope.launch {
            // 없는 대화를 가리키면(지워졌거나 잘못된 id) "새 글" 로 돌아간다 — 반쯤 죽은 화면을 남기지 않게.
            val stored = chatRepo.getSession(sessionId) ?: run {
                _uiState.value = emptyState()
                return@launch
            }
            val session = detachVanishedJob(stored)
            val history = chatRepo.messagesOnce(session.id)
            val photos = restoreAttachments(history)
            val groups = restoreGroups(history, photos.map { it.ref })
            // 이어 쓰던 글이나 세워 둔 계획이 있으면 오른쪽을 바로 펼쳐 준다 — "보기"를 다시 누르지 않게.
            val hasSomethingToShow = session.pendingJobId != null || history.any { it.kind == MessageKind.PLAN }
            // 조언 대화는 마지막으로 열어 본 글을 그 자리에 다시 건다.
            val focused = restoreFocusedPost(history)
            // 새 상태로 통째로 갈아 끼운다 — thinking·streamingSay·toolStatus·칩이 함께 초기화된다.
            _uiState.value = ChatUiState(
                session = session,
                attachments = photos,
                photoGroups = groups,
                // 이전에 붙였던 사진은 이미 대화에 반영돼 있으므로 사진판은 비운 채로 연다.
                trayFrom = photos.size,
                panelJobId = session.pendingJobId,
                panelOpen = hasSomethingToShow || focused != null,
                listCollapsed = hasSomethingToShow || focused != null,
                hasKey = _uiState.value.hasKey,
                loggedIn = _uiState.value.loggedIn,
                blogId = _uiState.value.blogId,
                mode = session.mode,
                focusedPost = focused,
            )
            observeMessages(session.id)
        }
    }

    /** "새 글" 의 빈 상태. 대화에 딸리지 않는 것(열쇠·로그인)만 이어 간다 — 모드는 글쓰기로 돌아간다. */
    private fun emptyState() =
        ChatUiState(hasKey = _uiState.value.hasKey, loggedIn = _uiState.value.loggedIn, blogId = _uiState.value.blogId)

    /** 새 대화의 모드를 고른다. 대화가 이미 생겼으면 바꾸지 않는다 — 기록과 어긋나지 않게. */
    fun setMode(mode: SessionMode) {
        if (_uiState.value.session != null) return
        _uiState.update { it.copy(mode = mode) }
    }

    /** 사진 첨부와 보내기가 거의 동시에 들어와도 대화는 하나만 만들도록 직렬화한다. */
    private val sessionLock = Mutex()

    /** 지금 열려 있는 대화. 없으면(= "새 글") 여기서 만든다 — 첫 메시지·첫 사진 때만 생긴다. */
    private suspend fun ensureSession(): ChatSession = sessionLock.withLock {
        _uiState.value.session?.let { return@withLock it }
        val session = chatRepo.createSession(_uiState.value.mode)
        _uiState.update { it.copy(session = session) }
        observeMessages(session.id)
        session
    }

    private fun observeMessages(sessionId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            chatRepo.observeMessages(sessionId).collect { list -> _uiState.update { it.copy(messages = list) } }
        }
    }

    /**
     * 세션이 가리키는 발행 작업이 사라졌으면(발행 완료·채팅 밖에서 삭제) 연결을 끊는다.
     * 그대로 두면 "초안 열기"가 매번 "작업을 찾을 수 없음" → 대체 화면으로 떨어져 대화가 갇힌다.
     */
    private suspend fun detachVanishedJob(session: ChatSession): ChatSession {
        val jobId = session.pendingJobId ?: return session
        if (pendingJobs.get(jobId) != null) return session
        val fixed = session.copy(
            pendingJobId = null,
            status = if (session.publishedUrl != null) SessionStatus.PUBLISHED else SessionStatus.DRAFTING,
        )
        chatRepo.updateSession(fixed)
        return fixed
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val state = _uiState.value
        // 조언은 내 글을 읽어야 시작할 수 있다 — 대화도 만들지 않고 안내만 띄운다.
        if (state.mode == SessionMode.ADVICE && state.blogId == null) {
            _uiState.update { it.copy(error = ADVICE_NEEDS_LOGIN) }
            return
        }
        runTurn(trimmed, draftTurn = state.mode == SessionMode.WRITE && readyToDraft && DRAFT_WORDS.containsMatchIn(trimmed))
    }

    fun sendQuickReply(text: String) = when (text) {
        RETRY_CHIP -> runTurn(null, lastDraftTurn)
        DRAFT_CHIP -> requestDraft()
        else -> send(text)
    }

    /** 초안이 이미 있거나 답을 기다리는 중이면 초안 턴을 열지 않는다(모델이 잘못 보낸 칩으로도). */
    fun requestDraft() {
        val s = _uiState.value
        if (s.mode != SessionMode.WRITE) return
        if (s.panelJobId != null || s.thinking) return
        runTurn(DRAFT_CHIP, draftTurn = true)
    }

    fun attachPhotos(uris: List<String>) {
        if (_uiState.value.mode !in PHOTO_MODES) return
        if (uris.isEmpty()) return
        // 초안이 나온 뒤에 붙인 사진은 아직 에디터에 올라가 있지 않다 — 다음 수정본이 그 ref 를 쓰면
        // 주입이 통째로 실패한다. 증분 업로드는 SP3, SP2 에서는 아예 막는다.
        if (_uiState.value.panelJobId != null) {
            _uiState.update { it.copy(error = NO_PHOTO_AFTER_DRAFT) }
            return
        }
        // 같은 사진을 두 번 고르면 ref 도 사진판 키도 겹친다 — 이미 붙인 것은 건너뛴다.
        val already = _uiState.value.attachments.map { it.uri }.toSet()
        val fresh = uris.distinct().filterNot { it in already }
        if (fresh.isEmpty()) return
        viewModelScope.launch {
            // 말보다 사진을 먼저 붙였으면 여기서 대화가 생긴다.
            val session = ensureSession()
            val prepared = photoAttachments.prepare(session.id, _uiState.value.attachments.size, fresh)
            // 읽지 못한 사진을 들고 있으면 나중에 발행 단계에서 통째로 실패한다 — 여기서 뺀다.
            val (usable, unreadable) = prepared.partition { it.thumb != null }
            // 빠진 자리를 남기지 않으려면 목록 전체를 다시 매겨야 한다 (안 그러면 다음 첨부에서 ref 가 겹친다).
            _uiState.update {
                it.copy(
                    attachments = renumber(it.attachments + usable),
                    error = if (unreadable.isNotEmpty()) "사진 ${unreadable.size}장은 읽지 못했어요. 다시 골라 주세요." else null,
                )
            }
            if (usable.isNotEmpty()) {
                // 메시지에 남기는 ref 는 다시 매긴 뒤의 번호여야 한다.
                val added = _uiState.value.attachments.takeLast(usable.size)
                chatRepo.appendMessage(session.id, MessageRole.USER, MessageKind.PHOTOS, ChatPayloads.photos(added))
            }
        }
    }

    /**
     * 기록에 남은 사진 메시지는 그대로 두고 이번 글에 쓸 목록에서만 뺀다.
     * 남은 사진의 ref 는 `img_001` 부터 다시 매긴다 — 발행 파이프라인이 `imageUris` 순서로 ref 를 붙이므로
     * 빈 번호가 생기면 초안의 사진 자리를 찾지 못한다. (캐시는 uri 로 잡혀 있어 번호가 바뀌어도 안전하다.)
     */
    fun removePhoto(ref: String) {
        updateWithGroups { state ->
            val kept = state.attachments.filterNot { it.ref == ref }
            val renumbered = renumber(kept)
            state.copy(
                attachments = renumbered,
                trayFrom = state.trayFrom.coerceAtMost(kept.size),
                photoGroups = remapGroups(state.photoGroups, state.attachments, renumbered),
                groupPicks = state.groupPicks?.let { remapRefs(it, state.attachments, renumbered) },
            )
        }
    }

    // ---- 사진 묶기 ----

    /** 사진 2~4장을 한 묶음으로 고르기 시작한다. 초안이 나온 뒤에는 묶음을 바꿀 수 없다. */
    fun startGrouping() {
        if (_uiState.value.mode != SessionMode.WRITE) return
        if (_uiState.value.panelJobId != null) return
        _uiState.update { it.copy(groupPicks = emptyList()) }
    }

    /** 묶기 모드에서 사진 하나를 고르거나 뺀다. 이미 다른 묶음에 든 사진과 다섯 번째 사진은 받지 않는다. */
    fun toggleGroupPick(ref: String) {
        _uiState.update { state ->
            val picks = state.groupPicks ?: return@update state
            when {
                ref in picks -> state.copy(groupPicks = picks - ref)
                state.photoGroups.any { ref in it } -> state
                picks.size >= MAX_GROUP -> state
                else -> state.copy(groupPicks = picks + ref)
            }
        }
    }

    /** 고른 사진이 2~4장이면 묶음으로 만든다. 아니면 그냥 묶기 모드를 닫는다. */
    fun finishGrouping() {
        updateWithGroups { state ->
            val picks = state.groupPicks ?: return@updateWithGroups state
            val made = if (picks.size in MIN_GROUP..MAX_GROUP) state.photoGroups + listOf(picks) else state.photoGroups
            state.copy(photoGroups = made, groupPicks = null)
        }
    }

    fun cancelGrouping() = _uiState.update { it.copy(groupPicks = null) }

    /** [groupIndex] 번째 묶음을 푼다. */
    fun ungroup(groupIndex: Int) {
        updateWithGroups { state ->
            if (groupIndex !in state.photoGroups.indices) state
            else state.copy(photoGroups = state.photoGroups.filterIndexed { i, _ -> i != groupIndex })
        }
    }

    /** 상태를 바꾸고, 묶음이 달라졌으면 대화에 남긴다 — 다시 열 때 마지막 것으로 되살린다. */
    private fun updateWithGroups(transform: (ChatUiState) -> ChatUiState) {
        val before = _uiState.value.photoGroups
        val after = _uiState.updateAndGet(transform).photoGroups
        if (after == before) return
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            chatRepo.appendMessage(session.id, MessageRole.USER, MessageKind.PHOTO_GROUPS, ChatPayloads.photoGroups(after))
        }
    }

    /** 번호를 다시 매기거나 사진을 뺀 뒤의 묶음. uri 로 따라가고, 한 장 이하로 줄어든 묶음은 없앤다. */
    private fun remapGroups(groups: List<List<String>>, old: List<AttachedPhoto>, new: List<AttachedPhoto>) =
        groups.map { remapRefs(it, old, new) }.filter { it.size >= MIN_GROUP }

    private fun remapRefs(refs: List<String>, old: List<AttachedPhoto>, new: List<AttachedPhoto>): List<String> {
        val uriByRef = old.associate { it.ref to it.uri }
        val refByUri = new.associate { it.uri to it.ref }
        return refs.mapNotNull { refByUri[uriByRef[it]] }
    }

    /**
     * 사진판의 앞/뒤 버튼. 위치는 **사진판 기준**(= 아직 안 보낸 사진들 안에서의 자리)이다.
     * 순서가 곧 글에 들어갈 순서라 ref 도 함께 다시 매긴다.
     */
    fun movePhoto(from: Int, to: Int) {
        updateWithGroups { state ->
            val offset = state.trayFrom.coerceIn(0, state.attachments.size)
            val source = offset + from
            val target = offset + to
            if (source !in state.attachments.indices || target !in state.attachments.indices) return@updateWithGroups state
            val list = state.attachments.toMutableList()
            list.add(target, list.removeAt(source))
            val renumbered = renumber(list)
            state.copy(
                attachments = renumbered,
                photoGroups = remapGroups(state.photoGroups, state.attachments, renumbered),
                groupPicks = state.groupPicks?.let { remapRefs(it, state.attachments, renumbered) },
            )
        }
    }

    private fun renumber(photos: List<AttachedPhoto>) =
        photos.mapIndexed { index, photo -> photo.copy(ref = "img_%03d".format(index + 1)) }

    fun togglePanel() {
        val open = !_uiState.value.panelOpen
        _uiState.update { it.copy(panelOpen = open && it.hasPanel, listCollapsed = if (open) true else it.listCollapsed) }
    }

    /** 조언 대화의 "…글을 읽었어요 · 보기" 를 누르면 그 글을 오른쪽에 건다 — 마지막으로 읽은 글이 아니라. */
    fun focusPost(view: PostView) {
        if (_uiState.value.mode != SessionMode.ADVICE) return
        _uiState.update { it.copy(focusedPost = view, panelOpen = true, listCollapsed = true) }
    }

    /** 스트리밍 중인 생각을 사용자가 직접 펴거나 접는다. */
    fun toggleStreamingThought() = _uiState.update { it.copy(thoughtCollapsed = !it.thoughtCollapsed) }

    /** 계획 줄이나 "초안 열기"에서 부른다 — 이미 열려 있으면 그대로 둔다(토글과 다르다). */
    fun openPanel() = _uiState.update { if (it.hasPanel) it.copy(panelOpen = true, listCollapsed = true) else it }

    fun toggleList() = _uiState.update { it.copy(listCollapsed = !it.listCollapsed) }

    /**
     * 계획 패널에서 사용자가 직접 고친 계획. 기존 계획을 지우지 않고 사용자 몫의 PLAN 메시지로 쌓는다 —
     * 패널(`plan`)도 모델에 실려 나가는 `currentPlan` 도 늘 마지막 계획을 본다.
     */
    fun savePlanEdit(markdown: String) {
        val edited = markdown.trim()
        if (edited.isEmpty()) return
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            chatRepo.appendMessage(session.id, MessageRole.USER, MessageKind.PLAN, ChatPayloads.plan(edited))
            // 이름은 아직 없을 때만 붙인다 (계획이 처음 나왔을 때와 같은 규칙).
            val latest = sessionOf(session.id) ?: return@launch
            if (latest.title == null) planTitle(edited)?.let { updateSession(latest.copy(title = it)) }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    /**
     * 대화를 지운다. 지금 열려 있는 대화면 돌던 턴을 취소하고(늦게 온 답이 사라진 대화에 붙지 않게),
     * 남은 대화 중 가장 최근 것으로 옮긴다 — 없으면 새 대화를 연다.
     */
    fun deleteSession(id: String) {
        viewModelScope.launch {
            val target = chatRepo.getSession(id)
            val isOpenSession = _uiState.value.session?.id == id
            if (isOpenSession) { turnJob?.cancel(); turnJob = null }
            chatRepo.deleteSession(id)
            target?.pendingJobId?.let { pendingJobs.delete(it) }
            photoAttachments.clear(id)
            if (isOpenSession) {
                val next = chatRepo.observeSessions().first().firstOrNull()
                open(next?.id)
            }
        }
    }

    /** 대화 이름을 바꾼다. updatedAt 은 건드리지 않아 목록 순서(최근 대화순)는 그대로다. */
    fun renameSession(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            chatRepo.setTitle(id, trimmed)
            if (_uiState.value.session?.id == id) {
                _uiState.update { it.copy(session = it.session?.copy(title = trimmed)) }
            }
        }
    }

    fun onPublished(url: String) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            // 발행이 끝나면 PublishViewModel 이 작업을 지우므로 세션에서도 연결을 끊는다.
            val updated = session.copy(status = SessionStatus.PUBLISHED, publishedUrl = url, pendingJobId = null)
            chatRepo.updateSession(updated)
            chatRepo.appendMessage(session.id, MessageRole.SYSTEM, MessageKind.SYSTEM, ChatPayloads.text("발행했어요 🎉 $url"))
            _uiState.update { it.copy(session = updated, panelOpen = false, panelJobId = null) }
            photoAttachments.clear(session.id)
            publishedHook.onPublished(session.id, url)
        }
    }

    // ---- 턴 ----

    private fun runTurn(userText: String?, draftTurn: Boolean) {
        if (_uiState.value.thinking) return
        lastDraftTurn = draftTurn
        // 연타로 두 턴이 겹치지 않게 코루틴을 띄우기 전에 잠근다.
        _uiState.update {
            it.copy(thinking = true, streamingSay = null, streamingThought = null, thoughtCollapsed = false, toolStatus = null, quickReplies = emptyList(), error = null, draftGate = null)
        }
        turnJob = viewModelScope.launch {
            // "새 글" 의 첫 턴이면 여기서 대화가 생긴다.
            val session = ensureSession()
            val sessionId = session.id
            try {
                if (!keyStore.hasUsableKey.first()) {
                    system(sessionId, NO_KEY)
                    return@launch
                }
                // 턴이 실제로 시작될 때만 사진판을 비운다 (대화의 사진 목록에는 그대로 남는다).
                _uiState.update { it.copy(trayFrom = it.attachments.size) }
                if (userText != null) {
                    chatRepo.appendMessage(sessionId, MessageRole.USER, MessageKind.TEXT, ChatPayloads.text(userText))
                }
                // 사용자 말을 남긴 뒤에 읽는다 — "글 목록을 읽었어요" 줄이 질문 앞에 끼어들지 않게.
                if (session.mode == SessionMode.ADVICE) ensurePostList(sessionId)
                val result = try {
                    runner.runTurn(context(session, draftTurn), listenerFor(sessionId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 어떤 이유로든 턴이 터져도 "생각 중" 에 갇히지 않게 실패로 바꿔서 이어 간다.
                    TurnResult.Failure(TurnResult.Reason.OTHER, detail = e.message.orEmpty())
                }
                // 답을 기다리는 사이 다른 대화로 옮겨 갔다면 이 답은 조용히 버린다 (아무 데도 저장하지 않는다).
                if (!isCurrent(sessionId)) return@launch
                when (result) {
                    is TurnResult.Success -> onSuccess(sessionId, result.response, result.repairs, result.thought)
                    is TurnResult.Failure -> {
                        _uiState.update { it.copy(streamingSay = null) }
                        onFailure(sessionId, result)
                    }
                }
            } finally {
                if (isCurrent(sessionId)) _uiState.update { it.copy(thinking = false, streamingSay = null, streamingThought = null, thoughtCollapsed = false, toolStatus = null) }
            }
        }
    }

    /** 조언 세션의 첫 턴에 최근 글 목록을 한 번 읽어 둔다. 이미 있으면 그대로 쓴다(사용자 메시지 1회당 목록 1회 원칙은 도구 쪽 한도가 지킨다). */
    private suspend fun ensurePostList(sessionId: String) {
        val history = chatRepo.messagesOnce(sessionId)
        if (history.any { it.kind == MessageKind.BLOG_POSTS || (it.kind == MessageKind.SYSTEM && ChatPayloads.readText(it.payloadJson) == POSTS_FAILED) }) return
        val blogId = _uiState.value.blogId ?: return
        _uiState.update { it.copy(toolStatus = READING_POSTS) }
        // 목록 읽기가 터져도 앱을 죽이지 않는다(viewModelScope 로 새어 나간다) — 실패 줄로 이어 간다.
        val posts = try { blog.listPosts(blogId) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (posts == null) system(sessionId, POSTS_FAILED)
        else chatRepo.appendMessage(sessionId, MessageRole.SYSTEM, MessageKind.BLOG_POSTS, ChatPayloads.blogPosts(posts))
        _uiState.update { it.copy(toolStatus = null) }
    }

    /** 지금 화면에 떠 있는 대화가 [sessionId] 인가. 늦게 온 결과를 버릴 때 쓴다. */
    private fun isCurrent(sessionId: String) = _uiState.value.session?.id == sessionId

    /** 턴 하나가 쓰는 리스너. 자기 대화 [sessionId] 를 들고 있어야 늦게 온 신호가 남의 대화에 붙지 않는다. */
    private fun listenerFor(sessionId: String) = object : TurnListener {
        override fun onToolStatus(text: String) = _uiState.update { it.copy(toolStatus = text) }
        // 값은 늘 "지금까지의 전체 접두" 다 — 이어붙이지 않고 교체한다. 빈 문자열은 지우라는 뜻.
        // 빈 값은 새 attempt 라는 뜻이라 접힘도 함께 푼다 — 안 그러면 새 생각이 한 줄로 갇힌다.
        override fun onPartialThought(text: String) { if (isCurrent(sessionId)) _uiState.update { it.copy(streamingThought = text.ifEmpty { null }, thoughtCollapsed = if (text.isEmpty()) false else it.thoughtCollapsed) } }
        // 답이 오기 시작하면 생각은 접는다 — 한 번 접히면 턴이 끝날 때까지 다시 펴지지 않는다(사용자가 탭하면 예외).
        override fun onPartialSay(text: String) { if (isCurrent(sessionId)) _uiState.update { it.copy(streamingSay = text.ifEmpty { null }, thoughtCollapsed = it.thoughtCollapsed || text.isNotEmpty()) } }
        // 조언 도구가 글을 읽으면 오른쪽을 그 글로 연다. 대화에도 남겨 다시 열 때 되살린다.
        override fun onPostRead(logNo: String, title: String) {
            if (!isCurrent(sessionId)) return
            val view = PostView(logNo, title)
            _uiState.update { it.copy(focusedPost = view, panelOpen = true, listCollapsed = true) }
            viewModelScope.launch { if (isCurrent(sessionId)) chatRepo.appendMessage(sessionId, MessageRole.SYSTEM, MessageKind.POST_VIEW, ChatPayloads.postView(view)) }
        }
    }

    private suspend fun context(session: ChatSession, draftTurn: Boolean): ChatContext {
        val all = chatRepo.messagesOnce(session.id)
        val history = all.filterNot { it.role == MessageRole.SYSTEM || it.kind == MessageKind.SYSTEM }
        // 조언·자유 세션은 계획·초안을 쓰지 않는다 — 프롬프트에도 실어 보내지 않는다(자유는 사진만 받는다).
        val write = session.mode == SessionMode.WRITE
        val style = memory.activeItems()
            .filter { it.kind == MemoryKind.STYLE }
            .joinToString("\n") { it.text }
            .ifEmpty { null }
        return ChatContext(
            history = history,
            attachments = if (session.mode in PHOTO_MODES) photoAttachments.attachments(session.id, _uiState.value.attachments) else emptyList(),
            photoGroups = if (write) _uiState.value.photoGroups else emptyList(),
            style = if (session.mode in STYLE_MODES) style else null,
            draftTurn = draftTurn,
            // 초안이 나오기 전까지는 계획을 함께 보낸다 — 모델이 "이 계획을 고쳐라"로 읽는다.
            currentPlan = if (write && _uiState.value.panelJobId == null) lastPlan(history) else null,
            // 재주입 조건과 같아야 한다 — 패널을 접어 두고 "문단 2를 더 짧게" 라고 해도 지금 초안을 함께 보낸다.
            currentPost = if (write && _uiState.value.panelJobId != null) lastPost(history) else null,
            questionRounds = questionRounds(history),
            mode = session.mode,
            blogPosts = all.lastOrNull { it.kind == MessageKind.BLOG_POSTS }?.let { ChatPayloads.readBlogPosts(it.payloadJson) },
        )
    }

    private suspend fun onSuccess(sessionId: String, response: TurnResponse, repairs: List<String>, thought: String?) {
        val session = sessionOf(sessionId) ?: return
        // 조언·자유는 말만 남긴다 — 계획·초안·품질 게이트는 글쓰기 몫이다.
        if (session.mode == SessionMode.ADVICE || session.mode == SessionMode.FREE) {
            chatRepo.appendMessage(sessionId, MessageRole.ASSISTANT, MessageKind.TEXT, ChatPayloads.assistantText(response.say, thought))
            _uiState.update { it.copy(streamingSay = null) }
            if (session.title == null) {
                val first = chatRepo.messagesOnce(sessionId).firstOrNull { it.role == MessageRole.USER && it.kind == MessageKind.TEXT }
                first?.let { updateSession(session.copy(title = ChatPayloads.readText(it.payloadJson).take(ADVICE_TITLE_MAX))) }
            }
            return
        }
        // post 는 사용자가 초안을 요청한 턴, 또는 이미 초안이 있어 고치는 턴에서만 받는다.
        // 모델이 계획 단계에서 성급하게 post 를 내면 버린다 — 초안은 입력창 위 버튼으로만 시작한다.
        val hasDraft = session.pendingJobId != null
        val post = response.post?.takeIf { lastDraftTurn || hasDraft }
        // 되묻기만 한 턴에서는 아직 쓸 준비가 안 된 것이다 — 모델이 true 를 보내도 막는다.
        val questionTurn = response.plan == null && response.question != null
        readyToDraft = !questionTurn && (response.readyToDraft || (response.post != null && post == null))
        // 질문은 말풍선 하나 안에서 say 다음 줄에 붙인다.
        val say = if (response.question.isNullOrBlank()) response.say else response.say + "\n" + response.question
        chatRepo.appendMessage(sessionId, MessageRole.ASSISTANT, MessageKind.TEXT, ChatPayloads.assistantText(say, thought))
        // 실제 말풍선이 생긴 뒤에 임시 말풍선을 지운다 — 중간에 빈 화면이 보이지 않게.
        _uiState.update { it.copy(streamingSay = null) }
        response.plan?.let {
            chatRepo.appendMessage(sessionId, MessageRole.ASSISTANT, MessageKind.PLAN, ChatPayloads.plan(it))
            // 계획이 나오면 오른쪽에 바로 펼쳐 준다 (초안이 이미 있으면 그 자리는 에디터가 지킨다).
            _uiState.update { state -> state.copy(panelOpen = true, listCollapsed = true) }
        }
        _uiState.update { it.copy(quickReplies = response.quickReplies) }
        // 제목은 한 번만 자동으로 붙는다 — session.title 이 이미 있으면(자동이든 사용자가 바꿨든) 건드리지 않는다.
        val title = session.title ?: response.plan?.let(::planTitle) ?: post?.title
        if (title != session.title) updateSession(session.copy(title = title))
        if (post == null) return
        // 에디터에 넣기 전 마지막 점검 — 걸리면 사용자가 넣을지 고쳐 달라고 할지 고른다.
        val gate = draftGate(post, repairs)
        if (gate == null) acceptDraft(sessionId, post)
        else _uiState.update { it.copy(draftGate = gate) }
    }

    /** 점검을 통과했거나 사용자가 "이대로 넣기"를 고른 초안. */
    private suspend fun acceptDraft(sessionId: String, post: PostContent) {
        chatRepo.appendMessage(sessionId, MessageRole.ASSISTANT, MessageKind.POST, ChatPayloads.post(post))
        onPostRevised(sessionId, post)
    }

    fun acceptDraftGate() {
        val gate = _uiState.value.draftGate ?: return
        val sessionId = _uiState.value.session?.id ?: return
        _uiState.update { it.copy(draftGate = null) }
        viewModelScope.launch { if (isCurrent(sessionId)) acceptDraft(sessionId, gate.post) }
    }

    /** 고쳐 달라는 말을 사용자 메시지로 대신 보낸다. 초안이 이미 있으면 수정 턴, 없으면 초안 턴이다. */
    fun fixDraftGate() {
        val gate = _uiState.value.draftGate ?: return
        _uiState.update { it.copy(draftGate = null) }
        runTurn(gate.request, draftTurn = _uiState.value.panelJobId == null)
    }

    /**
     * 모델을 부르지 않는 로컬 점검 — 본문 글자 수(허용 오차 ±10%)와 엔진이 손댄 사진 자리.
     * 문제가 없으면 null.
     */
    private suspend fun draftGate(post: PostContent, repairs: List<String>): DraftGate? {
        val range = settings.modelPolicyOnce().targetLength
        val issues = mutableListOf<String>()
        val asks = mutableListOf<String>()
        val length = bodyLength(post)
        if (length < (range.first * (1 - LENGTH_TOLERANCE)).toInt() || length > (range.last * (1 + LENGTH_TOLERANCE)).toInt()) {
            issues += "본문이 ${withCommas(length)}자예요. 목표는 ${withCommas(range.first)}~${withCommas(range.last)}자예요."
            asks += "본문을 ${withCommas(range.first)}~${withCommas(range.last)}자로 맞춰 주세요."
        }
        refs(repairs, PostContentRepair.MISSING).takeIf { it.isNotEmpty() }?.let {
            issues += "사진 $it 은 글에 없어서 맨 끝에 붙였어요."
            asks += "사진 $it 은 어울리는 자리에 넣어 주세요."
        }
        refs(repairs, PostContentRepair.DUPLICATE).takeIf { it.isNotEmpty() }?.let {
            issues += "사진 $it 이 두 번 나와서 한 번만 남겼어요."
            asks += "사진 $it 은 한 번만 써 주세요."
        }
        return if (issues.isEmpty()) null else DraftGate(post, issues, asks.joinToString(" "))
    }

    /** 본문 글자 수 — 제목과 공백은 빼고 문단·인용구(소제목) 글자를 센다. */
    private fun bodyLength(post: PostContent): Int = post.blocks.sumOf { block ->
        when (block) {
            is Block.Paragraph -> block.runs.sumOf { run -> run.text.count { !it.isWhitespace() } }
            is Block.Quote -> block.text.count { !it.isWhitespace() }
            is Block.Table -> block.rows.sumOf { row -> row.sumOf { cell -> cell.count { !it.isWhitespace() } } }
            else -> 0
        }
    }

    private fun refs(repairs: List<String>, prefix: String): String =
        repairs.filter { it.startsWith(prefix) }.joinToString(", ") { it.removePrefix(prefix) }

    private fun withCommas(value: Int) = String.format(Locale.KOREA, "%,d", value)

    /**
     * 첫 계획이 나오기 전까지 모델이 되물은 횟수. 메시지에서 세므로 대화를 다시 열어도 맞는다.
     * 계획이 이미 있으면 그 앞까지만 센다 — 그 뒤로는 엔진이 이 값을 보지 않는다.
     */
    private fun questionRounds(history: List<ChatMessage>): Int {
        val firstPlan = history.indexOfFirst { it.kind == MessageKind.PLAN }
        val before = if (firstPlan < 0) history else history.subList(0, firstPlan)
        return before.count { it.role == MessageRole.ASSISTANT && it.kind == MessageKind.TEXT }
    }

    /** 엔진이 post 를 낸 턴의 공통 처리: 발행 작업을 만들거나 갱신하고 패널을 연다. */
    suspend fun onPostRevised(sessionId: String, post: PostContent) {
        val session = sessionOf(sessionId) ?: return
        val jobId = session.pendingJobId ?: UUID.randomUUID().toString()
        val alreadyOpened = _uiState.value.panelJobId == jobId
        val existing = pendingJobs.get(jobId)
        // 내용이 그대로면 에디터를 다시 채우지 않는다(같은 글이 다시 로딩되는 것처럼 보이던 문제).
        if (alreadyOpened && existing?.content == post) {
            _uiState.update { it.copy(panelOpen = true, listCollapsed = true) }
            return
        }
        pendingJobs.save(
            PendingJob(
                id = jobId,
                content = post,
                // 발행 파이프라인은 원본 uri 를 스스로 준비한다 (채팅용 1024px 축소본이 아니라).
                imageUris = _uiState.value.attachments.map { it.uri },
                preparedPaths = existing?.preparedPaths,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                lastFailure = null,
            )
        )
        val latest = sessionOf(sessionId) ?: return
        if (latest.pendingJobId != jobId || latest.status == SessionStatus.DRAFTING) {
            updateSession(latest.copy(pendingJobId = jobId, status = SessionStatus.PUBLISHING))
        }
        // 이 작업의 패널이 이미 붙어 있으면(접혀 있어도 살아 있다) 에디터에 새 내용을 다시 넣어 달라고 한다.
        // 아직 안 붙었으면 아무도 안 듣고 흘려보내지고, 패널이 처음 뜰 때 방금 저장한 내용으로 시작한다.
        if (alreadyOpened) _reinject.tryEmit(post)
        _uiState.update { it.copy(panelJobId = jobId, panelOpen = true, listCollapsed = true) }
    }

    private suspend fun onFailure(sessionId: String, failure: TurnResult.Failure) {
        system(sessionId, failureCopy(failure))
        if (failure.reason == TurnResult.Reason.RATE_LIMITED || failure.reason == TurnResult.Reason.SERVER) {
            _uiState.update { it.copy(quickReplies = listOf(RETRY_CHIP)) }
        }
    }

    private fun failureCopy(failure: TurnResult.Failure): String = when (failure.reason) {
        TurnResult.Reason.NO_KEY -> NO_KEY
        TurnResult.Reason.RATE_LIMITED -> "지금은 잠깐 쉬어야 해요. ${minutesUntil(failure.retryAt)}분 뒤에 다시 시도할게요."
        TurnResult.Reason.NETWORK -> "인터넷이 연결되어 있지 않아요. 연결되면 다시 보내 주세요."
        TurnResult.Reason.SERVER -> "AI 서버가 지금 붐벼서 답을 못 받았어요. 잠시 뒤에 다시 시도해 주세요."
        TurnResult.Reason.BAD_RESPONSE -> "잘 못 알아들었어요. 다시 말해 주세요."
        TurnResult.Reason.OTHER -> "문제가 생겼어요. 관리자에게 알려 주세요."
    }

    private fun minutesUntil(retryAt: Long?): Int {
        val remain = (retryAt ?: 0L) - System.currentTimeMillis()
        return ceil(remain / 60_000.0).toInt().coerceAtLeast(1)
    }

    private suspend fun system(sessionId: String, text: String) {
        chatRepo.appendMessage(sessionId, MessageRole.SYSTEM, MessageKind.SYSTEM, ChatPayloads.text(text))
    }

    private suspend fun updateSession(session: ChatSession) {
        chatRepo.updateSession(session)
        _uiState.update { it.copy(session = session) }
    }

    /** 화면에 떠 있는 대화가 [sessionId] 일 때만 그 세션을 돌려준다. 늦게 온 턴이 남의 대화를 건드리지 못하게. */
    private fun sessionOf(sessionId: String): ChatSession? = _uiState.value.session?.takeIf { it.id == sessionId }

    private fun lastPost(history: List<ChatMessage>): PostContent? =
        history.lastOrNull { it.kind == MessageKind.POST }?.let { ChatPayloads.readPost(it.payloadJson) }

    private fun lastPlan(history: List<ChatMessage>): String? =
        history.lastOrNull { it.kind == MessageKind.PLAN }?.let { ChatPayloads.readPlan(it.payloadJson) }

    /** 조언 대화를 다시 열 때 마지막으로 보던 글. */
    private fun restoreFocusedPost(history: List<ChatMessage>): PostView? =
        history.lastOrNull { it.kind == MessageKind.POST_VIEW }?.let { ChatPayloads.readPostView(it.payloadJson) }

    /** 계획 첫 줄의 `# 제목` — 세션 이름으로 쓴다. */
    private fun planTitle(markdown: String): String? =
        markdown.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()?.ifEmpty { null }

    /**
     * 사진 메시지는 붙일 때마다 새로 쌓이고 번호도 그때그때 다시 매긴 값이라, 그대로 이어 붙이면
     * 같은 사진이 두 번 들어오거나 ref 가 겹친다 — uri 로 한 번 걸러 내고 번호를 다시 매긴다.
     * (뺐던 사진이 되살아나는 것은 남는 문제 — 스펙 §14 참고.)
     */
    private fun restoreAttachments(history: List<ChatMessage>): List<AttachedPhoto> =
        renumber(
            history
                .filter { it.kind == MessageKind.PHOTOS }
                .mapNotNull { ChatPayloads.readPhotos(it.payloadJson) }
                .flatMap { payload -> payload.refs.zip(payload.uris) { ref, uri -> AttachedPhoto(ref, uri, null) } }
                .distinctBy { it.uri }
        )

    /**
     * 마지막 PHOTO_GROUPS 메시지가 지금 남아 있는 사진에 대해 뜻이 통하는 만큼만 되살린다
     * (사진이 사라져 한 장 이하로 줄어든 묶음은 버린다).
     */
    private fun restoreGroups(history: List<ChatMessage>, refs: List<String>): List<List<String>> =
        history.lastOrNull { it.kind == MessageKind.PHOTO_GROUPS }
            ?.let { ChatPayloads.readPhotoGroups(it.payloadJson) }
            .orEmpty()
            .map { group -> group.filter { it in refs } }
            .filter { it.size >= MIN_GROUP }
}
