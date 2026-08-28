package com.csh.blogwriter.ui.fallback

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.publish.FallbackTextRenderer
import com.csh.blogwriter.ui.publish.ImagePreparing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FallbackUiState(val title: String, val reason: String, val clipboardText: String, val shareText: String)

object FallbackReason {
    /** 기술 메시지 → 사용자 문장. stageHint 는 PublishStage.name 또는 null. */
    fun userMessage(technical: String, stageHint: String?): String = when {
        stageHint == "LOAD_EDITOR" || technical.contains("editor missing") -> "네이버 글쓰기 화면이 열리지 않았어요."
        technical.contains("사진") || stageHint == "UPLOAD" -> "사진을 올리다가 멈췄어요."
        else -> "글을 자동으로 채우지 못했어요."
    }
}

@HiltViewModel
class FallbackViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val pendingJobs: PendingJobRepository,
    private val preparer: ImagePreparing,
) : ViewModel() {
    private val jobId: String = checkNotNull(savedState["jobId"])
    private val _uiState = MutableStateFlow<FallbackUiState?>(null)
    val uiState: StateFlow<FallbackUiState?> = _uiState

    /** 올리다 만 글을 그만 쓴다: 대기 작업과 준비해 둔 사진 캐시를 지운다. 갤러리 원본은 건드리지 않는다.
     * suspend 로 두어 호출자(화면)가 완료를 기다린 뒤 화면을 나가게 한다 — fire-and-forget 으로 두면
     * 화면 이동이 먼저 일어나 ViewModelStore 가 정리되면서 이 작업이 중간에 취소될 수 있다. */
    suspend fun discard() {
        pendingJobs.delete(jobId)
        preparer.clear(jobId)
    }

    init {
        viewModelScope.launch {
            val job = pendingJobs.get(jobId) ?: return@launch
            val lastFailure = job.lastFailure ?: "알 수 없음"
            val stagePrefix = Regex("^([A-Z_]+): (.*)$").matchEntire(lastFailure)
            val stageHint = stagePrefix?.groupValues?.get(1)
            val technical = lastFailure
            _uiState.value = FallbackUiState(
                title = job.content.title,
                reason = FallbackReason.userMessage(technical, stageHint),
                clipboardText = FallbackTextRenderer.render(job.content),
                shareText = "[블로그 도우미 오류]\n글: ${job.content.title}\n원인: $technical",
            )
        }
    }
}
