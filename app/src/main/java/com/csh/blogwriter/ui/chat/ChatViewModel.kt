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
        viewModelScope.launch {
            val session = sessionId?.let { chatRepo.getSession(it) } ?: chatRepo.createSession()
            val photos = restoreAttachments(session.id)
            _uiState.value = ChatUiState(
                session = session,
                attachments = photos,
                panelJobId = session.pendingJobId,
                hasKey = _uiState.value.hasKey,
            )
            messagesJob?.cancel()
            messagesJob = viewModelScope.launch {
                chatRepo.observeMessages(session.id).collect { list -> _uiState.update { it.copy(messages = list) } }
            }
        }
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
        viewModelScope.launch {
            val added = photoAttachments.prepare(session.id, _uiState.value.attachments.size, uris)
            val unreadable = added.count { it.thumb == null }
            _uiState.update {
                it.copy(
                    attachments = it.attachments + added,
                    error = if (unreadable > 0) "사진 ${unreadable}장은 읽지 못했어요. 다시 골라 주세요." else null,
                )
            }
            chatRepo.appendMessage(session.id, MessageRole.USER, MessageKind.PHOTOS, ChatPayloads.photos(added))
        }
    }

    /** 기록에 남은 사진 메시지는 그대로 두고 이번 글에 쓸 목록에서만 뺀다 (ref 는 다시 매기지 않는다). */
    fun removePhoto(ref: String) {
        _uiState.update { it.copy(attachments = it.attachments.filterNot { photo -> photo.ref == ref }) }
    }

    fun togglePanel() {
        val open = !_uiState.value.panelOpen
        _uiState.update { it.copy(panelOpen = open && it.panelJobId != null, listCollapsed = if (open) true else it.listCollapsed) }
    }

    fun toggleList() = _uiState.update { it.copy(listCollapsed = !it.listCollapsed) }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun onPublished(url: String) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            // 발행이 끝나면 PublishViewModel 이 작업을 지우므로 세션에서도 연결을 끊는다.
            val updated = session.copy(status = SessionStatus.PUBLISHED, publishedUrl = url, pendingJobId = null)
            chatRepo.updateSession(updated)
            chatRepo.appendMessage(session.id, MessageRole.SYSTEM, MessageKind.SYSTEM, ChatPayloads.text("발행했어요 🎉 $url"))
            _uiState.update { it.copy(session = updated, panelOpen = false, panelJobId = null) }
            publishedHook.onPublished(session.id, url)
        }
    }

    // ---- 턴 ----

    private fun runTurn(userText: String?, draftTurn: Boolean) {
        val session = _uiState.value.session ?: return
        if (_uiState.value.thinking) return
        lastDraftTurn = draftTurn
        viewModelScope.launch {
            if (!keyStore.hasUsableKey.first()) {
                system(session.id, NO_KEY)
                return@launch
            }
            if (userText != null) {
                chatRepo.appendMessage(session.id, MessageRole.USER, MessageKind.TEXT, ChatPayloads.text(userText))
            }
            _uiState.update { it.copy(thinking = true, streamingSay = null, toolStatus = null, quickReplies = emptyList(), error = null) }
            val result = try {
                runner.runTurn(context(session, draftTurn), listener)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 어떤 이유로든 턴이 터져도 "생각 중" 에 갇히지 않게 실패로 바꿔서 이어 간다.
                TurnResult.Failure(TurnResult.Reason.OTHER, detail = e.message.orEmpty())
            }
            _uiState.update { it.copy(thinking = false, streamingSay = null, toolStatus = null) }
            when (result) {
                is TurnResult.Success -> onSuccess(result.response)
                is TurnResult.Failure -> onFailure(session.id, result)
            }
        }
    }

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
            currentPost = if (_uiState.value.panelOpen) lastPost(history) else null,
        )
    }

    private suspend fun onSuccess(response: TurnResponse) {
        val session = _uiState.value.session ?: return
        readyToDraft = response.readyToDraft
        chatRepo.appendMessage(session.id, MessageRole.ASSISTANT, MessageKind.TEXT, ChatPayloads.text(response.say))
        response.plan?.let { chatRepo.appendMessage(session.id, MessageRole.ASSISTANT, MessageKind.PLAN, ChatPayloads.plan(it)) }
        response.post?.let { chatRepo.appendMessage(session.id, MessageRole.ASSISTANT, MessageKind.POST, ChatPayloads.post(it)) }
        val chips = if (response.readyToDraft) (response.quickReplies + DRAFT_CHIP).distinct() else response.quickReplies
        _uiState.update { it.copy(quickReplies = chips) }
        val title = response.post?.title ?: session.title ?: response.plan?.titleCandidates?.firstOrNull()
        if (title != session.title) updateSession(session.copy(title = title))
        response.post?.let { onPostRevised(it) }
    }

    /** 엔진이 post 를 낸 턴의 공통 처리: 발행 작업을 만들거나 갱신하고 패널을 연다. */
    suspend fun onPostRevised(post: PostContent) {
        val session = _uiState.value.session ?: return
        val jobId = session.pendingJobId ?: UUID.randomUUID().toString()
        val existing = pendingJobs.get(jobId)
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
        if (session.pendingJobId != jobId || session.status == SessionStatus.DRAFTING) {
            updateSession(_uiState.value.session!!.copy(pendingJobId = jobId, status = SessionStatus.PUBLISHING))
        }
        if (_uiState.value.panelOpen) _reinject.tryEmit(post)
        _uiState.update { it.copy(panelJobId = jobId, panelOpen = true, listCollapsed = true) }
    }

    private suspend fun onFailure(sessionId: String, failure: TurnResult.Failure) {
        system(sessionId, failureCopy(failure))
        if (failure.reason == TurnResult.Reason.RATE_LIMITED) {
            _uiState.update { it.copy(quickReplies = listOf(RETRY_CHIP)) }
        }
    }

    private fun failureCopy(failure: TurnResult.Failure): String = when (failure.reason) {
        TurnResult.Reason.NO_KEY -> NO_KEY
        TurnResult.Reason.RATE_LIMITED -> "지금은 잠깐 쉬어야 해요. ${minutesUntil(failure.retryAt)}분 뒤에 다시 시도할게요."
        TurnResult.Reason.NETWORK -> "인터넷이 연결되어 있지 않아요. 연결되면 다시 보내 주세요."
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

    private fun lastPost(history: List<ChatMessage>): PostContent? =
        history.lastOrNull { it.kind == MessageKind.POST }?.let { ChatPayloads.readPost(it.payloadJson) }

    private suspend fun restoreAttachments(sessionId: String): List<AttachedPhoto> =
        chatRepo.messagesOnce(sessionId)
            .filter { it.kind == MessageKind.PHOTOS }
            .mapNotNull { ChatPayloads.readPhotos(it.payloadJson) }
            .flatMap { payload -> payload.refs.zip(payload.uris) { ref, uri -> AttachedPhoto(ref, uri, null) } }
}
