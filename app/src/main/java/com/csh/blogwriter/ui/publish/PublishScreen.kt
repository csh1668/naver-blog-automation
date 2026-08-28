// SP1 임시 전체 화면 래퍼. SP2 에서는 채팅 화면이 PublishPanel 을 직접 배치한다.
package com.csh.blogwriter.ui.publish

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.csh.blogwriter.ui.components.ConfirmSheet

@Composable
fun PublishScreen(
    onDone: () -> Unit,
    onSessionExpired: (jobId: String) -> Unit,
    onFailed: (jobId: String) -> Unit,
    onLeave: () -> Unit,
    viewModel: PublishViewModel = hiltViewModel(),
) {
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler { confirmLeave = true }
    PublishPanel(
        viewModel = viewModel,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        onDone = onDone, onSessionExpired = onSessionExpired, onFailed = onFailed,
        onCancelRequest = { confirmLeave = true },
    )
    ConfirmSheet(
        visible = confirmLeave,
        title = "작성 중인 글을 두고 나갈까요?",
        message = "나중에 홈 화면에서 이어서 올릴 수 있어요.",
        confirmText = "나가기", onConfirm = { confirmLeave = false; onLeave() },
        dismissText = "계속 진행", onDismiss = { confirmLeave = false },
    )
}
