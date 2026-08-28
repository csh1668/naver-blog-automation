package com.csh.blogwriter.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.chat.AttachedPhoto
import com.csh.blogwriter.chat.ChatContext
import com.csh.blogwriter.chat.PhotoAttachments
import com.csh.blogwriter.chat.PublishedHook
import com.csh.blogwriter.chat.TurnListener
import com.csh.blogwriter.chat.TurnResponse
import com.csh.blogwriter.chat.TurnResult
import com.csh.blogwriter.chat.TurnRunner
import com.csh.blogwriter.data.repo.ChatMessage
import com.csh.blogwriter.data.repo.ChatRepository
import com.csh.blogwriter.data.repo.ChatSession
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import com.csh.blogwriter.data.repo.MessageKind
import com.csh.blogwriter.data.repo.MessageRole
import com.csh.blogwriter.data.repo.PendingJob
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.data.repo.SessionStatus
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.llm.ApiKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
) : ViewModel() {

    companion object {
        const val RETRY_CHIP = "다시 시도"
        const val DRAFT_CHIP = "이대로 초안 써 줘"
        const val NO_KEY = "글을 쓰려면 관리자가 열쇠를 등록해야 해요"
        const val NO_PHOTO_AFTER_DRAFT = "초안을 만든 뒤에는 사진을 더 붙일 수 없어요. 새 글에서 이어 가 주세요."
        private val DRAFT_WORDS = Regex("초안|써 줘|작성해")
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    val sessions: StateFlow<List<ChatSession>> =
        chatRepo.observeSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 패널이 이미 열려 있을 때 수정본을 에디터에 다시 넣어 달라는 요청 — 화면이 PublishViewModel 로 전달한다. */
    private val _reinject = MutableSharedFlow<PostContent>(extraBufferCapacity = 4)
    val reinject: SharedFlow<PostContent> = _reinject

    private var messagesJob: Job? = null
    /** 돌고 있는 턴. 대화를 바꾸면 취소한다 — 늦게 온 답이 엉뚱한 대화에 붙지 않도록. */
    private var turnJob: Job? = null
    private var readyToDraft = false
    private var lastDraftTurn = false

    init {
        viewModelScope.launch {
            keyStore.hasUsableKey.collect { has -> _uiState.update { it.copy(hasKey = has) } }
        }
    }

    /** [sessionId] 가 null 이면 새 대화를 시작한다 ("새 글 쓰기"). */
    fun open(sessionId: String?) {
        if (sessionId != null && _uiState.value.session?.id == sessionId) return
        // 이전 대화의 턴은 여기서 끝낸다. 취소된 턴은 아무것도 저장하지 않는다.
        turnJob?.cancel()
        turnJob = null
        readyToDraft = false
        lastDraftTurn = false
        viewModelScope.launch {
            val session = detachVanishedJob(sessionId?.let { chatRepo.getSession(it) } ?: chatRepo.createSession())
            val photos = restoreAttachments(session.id)
            // 새 상태로 통째로 갈아 끼운다 — thinking·streamingSay·toolStatus·칩이 함께 초기화된다.
            _uiState.value = ChatUiState(
                session = session,
                attachments = photos,
                // 이전에 붙였던 사진은 이미 대화에 반영돼 있으므로 사진판은 비운 채로 연다.
                trayFrom = photos.size,
                panelJobId = session.pendingJobId,
                hasKey = _uiState.value.hasKey,
            )
            messagesJob?.cancel()
            messagesJob = viewModelScope.launch {
                chatRepo.observeMessages(session.id).collect { list -> _uiState.update { it.copy(messages = list) } }
            }
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
        runTurn(trimmed, draftTurn = readyToDraft && DRAFT_WORDS.containsMatchIn(trimmed))
    }

    fun sendQuickReply(text: String) = when (text) {
        RETRY_CHIP -> runTurn(null, lastDraftTurn)
        DRAFT_CHIP -> requestDraft()
        else -> send(text)
    }

    fun requestDraft() = runTurn(DRAFT_CHIP, draftTurn = true)

    fun attachPhotos(uris: List<String>) {
        if (uris.isEmpty()) return
        val session = _uiState.value.session ?: return
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
        _uiState.update { state ->
            val kept = state.attachments.filterNot { it.ref == ref }
            state.copy(attachments = renumber(kept), trayFrom = state.trayFrom.coerceAtMost(kept.size))
        }
    }

    /**
     * 사진판의 앞/뒤 버튼. 위치는 **사진판 기준**(= 아직 안 보낸 사진들 안에서의 자리)이다.
     * 순서가 곧 글에 들어갈 순서라 ref 도 함께 다시 매긴다.
     */
    fun movePhoto(from: Int, to: Int) {
        _uiState.update { state ->
            val offset = state.trayFrom.coerceIn(0, state.attachments.size)
            val source = offset + from
            val target = offset + to
            if (source !in state.attachments.indices || target !in state.attachments.indices) return@update state
            val list = state.attachments.toMutableList()
            list.add(target, list.removeAt(source))
            state.copy(attachments = renumber(list))
        }
    }

    private fun renumber(photos: List<AttachedPhoto>) =
        photos.mapIndexed { index, photo -> photo.copy(ref = "img_%03d".format(index + 1)) }

    fun togglePanel() {
        val open = !_uiState.value.panelOpen
        _uiState.update { it.copy(panelOpen = open && it.panelJobId != null, listCollapsed = if (open) true else it.listCollapsed) }
    }

    fun toggleList() = _uiState.update { it.copy(listCollapsed = !it.listCollapsed) }

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
        val session = _uiState.value.session ?: return
        if (_uiState.value.thinking) return
        lastDraftTurn = draftTurn
        // 연타로 두 턴이 겹치지 않게 코루틴을 띄우기 전에 잠근다.
        _uiState.update { it.copy(thinking = true, streamingSay = null, toolStatus = null, quickReplies = emptyList(), error = null) }
        val sessionId = session.id
        turnJob = viewModelScope.launch {
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
                val result = try {
                    runner.runTurn(context(session, draftTurn), listener)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 어떤 이유로든 턴이 터져도 "생각 중" 에 갇히지 않게 실패로 바꿔서 이어 간다.
                    TurnResult.Failure(TurnResult.Reason.OTHER, detail = e.message.orEmpty())
                }
                // 답을 기다리는 사이 다른 대화로 옮겨 갔다면 이 답은 조용히 버린다 (아무 데도 저장하지 않는다).
                if (!isCurrent(sessionId)) return@launch
                when (result) {
                    is TurnResult.Success -> onSuccess(sessionId, result.response)
                    is TurnResult.Failure -> {
                        _uiState.update { it.copy(streamingSay = null) }
                        onFailure(sessionId, result)
                    }
                }
            } finally {
                if (isCurrent(sessionId)) _uiState.update { it.copy(thinking = false, streamingSay = null, toolStatus = null) }
            }
        }
    }

    /** 지금 화면에 떠 있는 대화가 [sessionId] 인가. 늦게 온 결과를 버릴 때 쓴다. */
    private fun isCurrent(sessionId: String) = _uiState.value.session?.id == sessionId

    private val listener = object : TurnListener {
        override fun onToolStatus(text: String) = _uiState.update { it.copy(toolStatus = text) }
        // 값은 늘 "지금까지의 전체 접두" 다 — 이어붙이지 않고 교체한다. 빈 문자열은 지우라는 뜻.
        override fun onPartialSay(text: String) = _uiState.update { it.copy(streamingSay = text.ifEmpty { null }) }
    }

    private suspend fun context(session: ChatSession, draftTurn: Boolean): ChatContext {
        val history = chatRepo.messagesOnce(session.id)
            .filterNot { it.role == MessageRole.SYSTEM || it.kind == MessageKind.SYSTEM }
        val style = memory.activeItems()
            .filter { it.kind == MemoryKind.STYLE }
            .joinToString("\n") { it.text }
            .ifEmpty { null }
        return ChatContext(
            history = history,
            attachments = photoAttachments.attachments(session.id, _uiState.value.attachments),
            style = style,
            draftTurn = draftTurn,
            // 재주입 조건과 같아야 한다 — 패널을 접어 두고 "문단 2를 더 짧게" 라고 해도 지금 초안을 함께 보낸다.
            currentPost = if (_uiState.value.panelJobId != null) lastPost(history) else null,
        )
    }

    private suspend fun onSuccess(sessionId: String, response: TurnResponse) {
        val session = sessionOf(sessionId) ?: return
        // post 는 사용자가 초안을 요청한 턴, 또는 이미 초안이 있어 고치는 턴에서만 받는다.
        // 모델이 계획 단계에서 성급하게 post 를 내면 버리고, 대신 "초안 써 줘" 칩만 띄운다.
        val hasDraft = session.pendingJobId != null
        val post = response.post?.takeIf { lastDraftTurn || hasDraft }
        readyToDraft = response.readyToDraft || (response.post != null && post == null)
        chatRepo.appendMessage(sessionId, MessageRole.ASSISTANT, MessageKind.TEXT, ChatPayloads.text(response.say))
        // 실제 말풍선이 생긴 뒤에 임시 말풍선을 지운다 — 중간에 빈 화면이 보이지 않게.
        _uiState.update { it.copy(streamingSay = null) }
        response.plan?.let { chatRepo.appendMessage(sessionId, MessageRole.ASSISTANT, MessageKind.PLAN, ChatPayloads.plan(it)) }
        post?.let { chatRepo.appendMessage(sessionId, MessageRole.ASSISTANT, MessageKind.POST, ChatPayloads.post(it)) }
        // 초안이 이미 있으면(이번 턴에 만들었든 전에 만들었든) "이대로 초안 써 줘" 칩은 더 이상 의미가 없다.
        val draftExists = hasDraft || post != null
        val chips = when {
            draftExists -> response.quickReplies.filterNot { it == DRAFT_CHIP }
            readyToDraft -> (response.quickReplies + DRAFT_CHIP).distinct()
            else -> response.quickReplies
        }
        _uiState.update { it.copy(quickReplies = chips) }
        // 제목은 한 번만 자동으로 붙는다 — session.title 이 이미 있으면(자동이든 사용자가 바꿨든) 건드리지 않는다.
        val title = session.title ?: post?.title ?: response.plan?.titleCandidates?.firstOrNull()
        if (title != session.title) updateSession(session.copy(title = title))
        post?.let { onPostRevised(sessionId, it) }
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

    /**
     * 사진 메시지는 붙일 때마다 새로 쌓이고 번호도 그때그때 다시 매긴 값이라, 그대로 이어 붙이면
     * 같은 사진이 두 번 들어오거나 ref 가 겹친다 — uri 로 한 번 걸러 내고 번호를 다시 매긴다.
     * (뺐던 사진이 되살아나는 것은 남는 문제 — 스펙 §14 참고.)
     */
    private suspend fun restoreAttachments(sessionId: String): List<AttachedPhoto> =
        renumber(
            chatRepo.messagesOnce(sessionId)
                .filter { it.kind == MessageKind.PHOTOS }
                .mapNotNull { ChatPayloads.readPhotos(it.payloadJson) }
                .flatMap { payload -> payload.refs.zip(payload.uris) { ref, uri -> AttachedPhoto(ref, uri, null) } }
                .distinctBy { it.uri }
        )
}
