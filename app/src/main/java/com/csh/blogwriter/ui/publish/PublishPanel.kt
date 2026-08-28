package com.csh.blogwriter.ui.publish

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.domain.publish.PublishState
import com.csh.blogwriter.publish.NaverEditorWebView
import com.csh.blogwriter.ui.components.BannerKind
import com.csh.blogwriter.ui.components.InlineBanner
import com.csh.blogwriter.ui.components.ProgressScreen
import com.csh.blogwriter.ui.components.ResultScreen
import com.csh.blogwriter.ui.theme.AppSpacing
import kotlinx.serialization.json.JsonObject

/**
 * 발행 패널: 진행 오버레이 → 에디터 WebView(검토) → 결과. 부모가 준 [modifier] 크기를 채운다.
 * SP1: 전체 화면. SP2: 채팅 화면 오른쪽 사이드 패널.
 */
@Composable
fun PublishPanel(
    viewModel: PublishViewModel,
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
    onSessionExpired: (jobId: String) -> Unit,
    onFailed: (jobId: String) -> Unit,
    onCancelRequest: () -> Unit,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val editor = remember {
        NaverEditorWebView(context, object : NaverEditorWebView.Listener {
            override fun onUrlChanged(url: String) = viewModel.onUrlChanged(url)
            override fun onPageFinished(url: String) = viewModel.onPageFinished(url)
            override fun onReady() = viewModel.onReady()
            override fun onPopupsDismissed(count: Int) = viewModel.onPopupsDismissed(count)
            override fun onImageUploaded(ref: String, response: JsonObject) = viewModel.onImageUploaded(ref, response)
            override fun onImageFailed(ref: String, message: String) = viewModel.onImageFailed(ref, message)
            override fun onInjected(componentCount: Int) = viewModel.onInjected(componentCount)
            override fun onError(step: String, message: String) = viewModel.onJsError(step, message)
            override fun onLog(message: String) = Unit
        })
    }
    DisposableEffect(editor) {
        viewModel.attach(editor)
        onDispose { viewModel.detach(); editor.destroy() }
    }
    LaunchedEffect(Unit) {
        viewModel.navigation.collect { nav ->
            when (nav) {
                is PublishNav.SessionExpired -> onSessionExpired(nav.jobId)
                is PublishNav.Failed -> onFailed(nav.jobId)
            }
        }
    }

    val state = ui.state
    Box(modifier) {
        // WebView 는 항상 살아 있어야 하므로 먼저 배치하고, 필요할 때만 오버레이로 가린다.
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (state is PublishState.Reviewing) {
                Box(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)) {
                    InlineBanner("내용을 확인하고 오른쪽 위 '발행'을 눌러 주세요", BannerKind.Info)
                }
            }
            AndroidView(factory = { editor.view }, modifier = Modifier.fillMaxSize())
        }
        // 오버레이가 떠 있는 동안에는 아래 에디터로 터치가 새지 않도록 모두 삼킨다 (주입 중 오작동 방지).
        if (state !is PublishState.Reviewing) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } }
            }) {
                when (state) {
                    is PublishState.Idle, is PublishState.PreparingImages ->
                        ProgressScreen(
                            "사진을 준비하고 있어요",
                            (state as? PublishState.PreparingImages)?.let { "${it.total}장 중 ${it.done}장" },
                            (state as? PublishState.PreparingImages)?.let { if (it.total == 0) null else it.done.toFloat() / it.total },
                            onCancel = onCancelRequest,
                        )
                    is PublishState.LoadingEditor -> ProgressScreen("네이버 글쓰기 화면을 여는 중이에요", null, null, onCancel = onCancelRequest)
                    is PublishState.DismissingPopups -> ProgressScreen("네이버 글쓰기 화면을 여는 중이에요", null, null)
                    is PublishState.UploadingImages -> ProgressScreen("사진을 올리고 있어요", "${state.total}장 중 ${state.done}장", state.done.toFloat() / state.total)
                    is PublishState.Injecting -> ProgressScreen("글을 채워 넣고 있어요", null, null)
                    is PublishState.Published -> ResultScreen(success = true, title = "발행했어요", message = "발행한 글 목록에서 다시 볼 수 있어요.", primaryText = "확인", onPrimary = onDone)
                    is PublishState.SessionExpired, is PublishState.Failed -> ProgressScreen("잠시만요", null, null)
                    is PublishState.Reviewing -> Unit
                }
            }
        }
    }
}
