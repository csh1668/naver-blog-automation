package com.csh.blogwriter.ui.fallback

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.BottomCta
import com.csh.blogwriter.ui.components.ConfirmSheet
import com.csh.blogwriter.ui.components.DangerButton
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.components.WeakButton
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme
import kotlinx.coroutines.launch

private const val NAVER_BLOG_PACKAGE = "com.nhn.android.blog"

@Composable
fun FallbackScreen(onRetry: () -> Unit, onHome: () -> Unit, viewModel: FallbackViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val s = state ?: return
    var confirmDiscard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ScreenScaffold(
        topBar = { AppTopBar(onBack = onHome) },
        bottom = {
            BottomCta("글 복사하고 블로그 앱 열기", onClick = {
                context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("post", s.clipboardText))
                val launch = context.packageManager.getLaunchIntentForPackage(NAVER_BLOG_PACKAGE)
                context.startActivity(launch ?: Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$NAVER_BLOG_PACKAGE".toUri()))
            })
            Spacer(Modifier.height(AppSpacing.md))
            WeakButton("다시 시도", onClick = onRetry)
            Spacer(Modifier.height(AppSpacing.md))
            WeakButton("관리자에게 알리기", onClick = {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, s.shareText) }
                context.startActivity(Intent.createChooser(send, "관리자에게 알리기"))
            })
            Spacer(Modifier.height(AppSpacing.md))
            DangerButton("이 글은 그만 쓰기", onClick = { confirmDiscard = true })
        },
    ) {
        Spacer(Modifier.height(AppSpacing.section))
        Text(s.reason, style = AppTheme.typography.title1, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(AppSpacing.lg))
        Text(
            "글을 복사해 두었다가 네이버 블로그 앱에서 붙여넣으면 돼요. 사진은 앱에서 갤러리로 직접 넣어 주세요.\n\n그대로 두면 홈 화면에서 나중에 다시 시도할 수 있어요.",
            style = AppTheme.typography.body1, color = AppTheme.colors.textSecondary,
        )
    }
    ConfirmSheet(
        visible = confirmDiscard,
        title = "이 글을 지울까요?",
        message = "지운 글은 되돌릴 수 없어요. 사진은 갤러리에 그대로 있어요.",
        confirmText = "지우기", onConfirm = { confirmDiscard = false; scope.launch { viewModel.discard(); onHome() } },
        dismissText = "그대로 두기", onDismiss = { confirmDiscard = false },
        danger = true,
    )
}
