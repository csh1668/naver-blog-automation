package com.csh.blogwriter.ui.publish

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.FailureLogRepository
import com.csh.blogwriter.data.repo.HistoryRepository
import com.csh.blogwriter.data.repo.PendingJob
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.PreparedImage
import com.csh.blogwriter.domain.publish.PublishEffect
import com.csh.blogwriter.domain.publish.PublishEvent
import com.csh.blogwriter.domain.publish.PublishStage
import com.csh.blogwriter.domain.publish.PublishState
import com.csh.blogwriter.domain.publish.PublishStateMachine
import com.csh.blogwriter.publish.DocumentModelConverter
import com.csh.blogwriter.publish.UploadedImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

data class PublishUiState(val state: PublishState = PublishState.Idle, val title: String = "")

sealed interface PublishNav {
    data class SessionExpired(val jobId: String) : PublishNav
    data class Failed(val jobId: String) : PublishNav
}

/**
 * 발행 흐름 조정자. 상태 결정은 [PublishStateMachine], 부수효과(WebView 명령, 저장, 타임아웃)는 여기서.
 * WebView 콜백(onUrlChanged, onReady, …)은 화면이 NaverEditorWebView.Listener 로 연결해 준다.
 */
@HiltViewModel
class PublishViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val pendingJobs: PendingJobRepository,
    private val history: HistoryRepository,
    private val failures: FailureLogRepository,
    private val settings: SettingsStore,
    private val preparer: ImagePreparing,
    private val converter: DocumentModelConverter,
) : ViewModel() {

    companion object {
        private const val TAG = "PublishViewModel"
        const val EDITOR_READY_TIMEOUT_MS = 30_000L
        const val READY_POLL_MS = 500L
        const val POPUP_TIMEOUT_MS = 5_000L
        const val UPLOAD_TIMEOUT_PER_IMAGE_MS = 60_000L
        const val INJECT_TIMEOUT_MS = 15_000L
    }

    private val jobId: String = checkNotNull(savedState["jobId"])
    private val _uiState = MutableStateFlow(PublishUiState())
    val uiState: StateFlow<PublishUiState> = _uiState
    private val _navigation = MutableSharedFlow<PublishNav>(extraBufferCapacity = 1)
    val navigation: SharedFlow<PublishNav> = _navigation

    private var controller: EditorController? = null
    private var job: PendingJob? = null
    private var machine: PublishStateMachine? = null
    private var blogId: String? = null
    private var images: List<PreparedImage> = emptyList()
    private val uploaded = mutableMapOf<String, UploadedImage>()
    private var expectedComponents: Int? = null
    /** 실패 로그에만 쓰는 진단용 마지막 주소 (발행 판정은 상태 기계가 URL 이벤트로 한다). */
    private var lastPageUrl: String = ""
    private var timeoutJob: Job? = null
    private var pollJob: Job? = null
    private var started = false
    /** WebView 가 없어진 뒤인지. 패널을 접었다 다시 펴면 새 WebView 라 처음부터 다시 해야 한다. */
    private var detached = false

    fun attach(controller: EditorController) {
        this.controller = controller
        when {
            !started -> { started = true; viewModelScope.launch { start() } }
            // 이전 WebView 는 파괴돼 화면이 비어 있다 — 발행까지 끝난 게 아니라면 다시 채워 넣는다.
            detached && _uiState.value.state !is PublishState.Published -> { uploaded.clear(); dispatch(PublishEvent.Retry) }
        }
        detached = false
    }

    fun detach() { controller = null; detached = true; pollJob?.cancel(); timeoutJob?.cancel() }

    /**
     * 채팅에서 글이 수정됐을 때 검토 중인 에디터에 새 내용을 다시 넣는다.
     * 사진은 이미 올라가 있는 것을 그대로 쓰므로 텍스트 변경만 반영된다.
     */
    fun reinject(content: PostContent) {
        viewModelScope.launch {
            val current = job ?: return@launch
            val updated = current.copy(content = content)
            job = updated
            pendingJobs.save(updated)
            val expected = DocumentModelConverter.expectedComponentCount(content)
            expectedComponents = expected
            // expectedComponents 는 생성자 값이라 기계를 새로 만든다.
            machine = PublishStateMachine(images.size, expected, blogId ?: "")
            // 새 기계는 "글쓰기 화면을 봤다"는 기록이 없어 그대로 두면 발행을 감지하지 못한다.
            if (lastPageUrl.isNotEmpty()) dispatch(PublishEvent.UrlChanged(lastPageUrl))
            dispatch(PublishEvent.Reinject(content))
        }
    }

    private suspend fun start() {
        blogId = settings.blogIdOnce()
        if (blogId == null) {
            machine = PublishStateMachine(0, 0, "")
            dispatch(PublishEvent.UrlChanged("https://nid.naver.com/nidlogin.login"))
            return
        }
        val loaded = pendingJobs.get(jobId) ?: run {
            machine = PublishStateMachine(0, 0, "")
            dispatch(PublishEvent.JsError(PublishStage.PREPARE, "작업을 찾을 수 없음: $jobId")); return
        }
        job = loaded
        val expected = DocumentModelConverter.expectedComponentCount(loaded.content)
        expectedComponents = expected
        machine = PublishStateMachine(
            totalImages = loaded.content.imageRefs().size,
            expectedComponents = expected,
            blogId = blogId ?: "",
        )
        _uiState.update { it.copy(title = loaded.content.title) }
        dispatch(PublishEvent.Start)
    }

    // ---- WebView 콜백 (NaverEditorWebView.Listener 가 위임) ----
    fun onUrlChanged(url: String) {
        lastPageUrl = url
        dispatch(PublishEvent.UrlChanged(url))
    }
    fun onPageFinished(url: String) { lastPageUrl = url; dispatch(PublishEvent.PageLoaded(url)) }
    fun onReady() { pollJob?.cancel(); dispatch(PublishEvent.EditorReady) }
    fun onPopupsDismissed(count: Int) { dispatch(PublishEvent.PopupsDismissed) }
    fun onImageUploaded(ref: String, response: JsonObject) {
        runCatching { UploadedImage.fromResponse(ref, response) }
            .onSuccess { uploaded[ref] = it; dispatch(PublishEvent.ImageUploaded(ref)) }
            .onFailure { dispatch(PublishEvent.ImageFailed(ref, "응답 해석 실패: ${it.message}")) }
    }
    fun onImageFailed(ref: String, message: String) = dispatch(PublishEvent.ImageFailed(ref, message))
    fun onInjected(componentCount: Int) {
        val expected = expectedComponents
        if (expected != null && componentCount != expected) {
            Log.w(TAG, "component count mismatch: expected=$expected actual=$componentCount")
        }
        dispatch(PublishEvent.Injected(componentCount))
    }
    fun onJsError(step: String, message: String) {
        val stage = when (step) {
            "ready", "fit" -> PublishStage.LOAD_EDITOR
            "popups" -> PublishStage.DISMISS_POPUPS
            "upload" -> PublishStage.UPLOAD
            else -> PublishStage.INJECT
        }
        Log.w(TAG, "js error at $step: $message")
        dispatch(PublishEvent.JsError(stage, message))
    }
    fun onRetry() { uploaded.clear(); dispatch(PublishEvent.Retry) }

    // ---- 상태 기계 구동 ----
    private fun dispatch(event: PublishEvent) {
        val m = machine ?: return
        val current = _uiState.value.state
        val (next, effects) = m.reduce(current, event)
        if (next != current) {
            _uiState.update { it.copy(state = next) }
            timeoutJob?.cancel()
            if (next !is PublishState.LoadingEditor) pollJob?.cancel()
            // 사진 한 장이 끝나도 남은 장수만큼 제한 시간을 다시 건다.
            // (첫 건은 UploadImages 효과가 건다 — 그것까지 중복해 걸지 않도록 done > 0 일 때만.)
            if (next is PublishState.UploadingImages && next.done > 0) {
                armTimeout(PublishStage.UPLOAD, UPLOAD_TIMEOUT_PER_IMAGE_MS * (next.total - next.done))
            }
        }
        effects.forEach { runEffect(it) }
    }

    private fun runEffect(effect: PublishEffect) {
        val c = controller
        when (effect) {
            PublishEffect.PrepareImages -> viewModelScope.launch { prepareImages() }
            PublishEffect.LoadEditor -> { c?.loadEditor(blogIdOrFail() ?: return); armTimeout(PublishStage.LOAD_EDITOR, EDITOR_READY_TIMEOUT_MS) }
            PublishEffect.StartReadyPolling -> startReadyPolling()
            PublishEffect.DismissPopups -> { c?.dismissPopups(); armTimeout(PublishStage.DISMISS_POPUPS, POPUP_TIMEOUT_MS) }
            PublishEffect.UploadImages -> { c?.uploadImages(images.map { it.ref }); armTimeout(PublishStage.UPLOAD, UPLOAD_TIMEOUT_PER_IMAGE_MS * images.size) }
            PublishEffect.Inject -> inject()
            PublishEffect.ShowEditor -> Unit
            is PublishEffect.SavePublished -> viewModelScope.launch {
                val j = job ?: return@launch
                history.add(j.content.title, effect.logNo, effect.url, images.size)
                pendingJobs.delete(j.id)
                preparer.clear(j.id)
            }
            PublishEffect.SavePending -> viewModelScope.launch { _navigation.emit(PublishNav.SessionExpired(jobId)) }
            is PublishEffect.LogFailure -> viewModelScope.launch {
                failures.add(effect.stage.name, effect.message, "url=$lastPageUrl")
                pendingJobs.setLastFailure(jobId, "${effect.stage.name}: ${effect.message}")
                _navigation.emit(PublishNav.Failed(jobId))
            }
        }
    }

    private fun blogIdOrFail(): String? = blogId

    private suspend fun prepareImages() {
        val j = job ?: return
        val reused = j.preparedPaths?.let { preparer.load(j.id, it) }
        images = try {
            reused ?: preparer.prepare(j.id, j.imageUris) { done -> dispatch(PublishEvent.ImagePrepared(done)) }
        } catch (e: Exception) {
            dispatch(PublishEvent.JsError(PublishStage.PREPARE, "사진 준비 실패: ${e.message}")); return
        }
        // 갤러리 Uri 권한은 프로세스가 죽으면 사라진다. 준비된 로컬 파일 경로를 바로 남겨야 나중에 이어서 올릴 수 있다.
        pendingJobs.setPreparedPaths(j.id, images.map { it.file.absolutePath }.takeIf { it.isNotEmpty() })
        controller?.setLocalImages(images)
        dispatch(PublishEvent.ImagesPrepared)
    }

    private fun startReadyPolling() {
        val c = controller ?: return
        c.installBridgeScript()
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) { c.checkReady(); delay(READY_POLL_MS) }
        }
    }

    private fun inject() {
        val j = job ?: return
        val c = controller ?: return
        val json = try {
            converter.convert(j.content, uploaded, documentId = "", version = "2.10.2").toString()
        } catch (e: IllegalArgumentException) {
            dispatch(PublishEvent.JsError(PublishStage.INJECT, e.message ?: "변환 실패")); return
        }
        c.setDocument(json)
        armTimeout(PublishStage.INJECT, INJECT_TIMEOUT_MS)
    }

    private fun armTimeout(stage: PublishStage, millis: Long) {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch { delay(millis); dispatch(PublishEvent.Timeout(stage)) }
    }
}
