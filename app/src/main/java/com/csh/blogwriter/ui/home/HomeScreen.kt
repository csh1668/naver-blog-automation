package com.csh.blogwriter.ui.home

import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.BannerKind
import com.csh.blogwriter.ui.components.BottomCta
import com.csh.blogwriter.ui.components.InlineBanner
import com.csh.blogwriter.ui.components.ListRow
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.components.WeakButton
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun HomeScreen(
    onNewPost: () -> Unit,
    onLogin: (returnTo: String) -> Unit,
    onResumePending: (jobId: String) -> Unit,
    onHistory: () -> Unit,
    onAdmin: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ScreenScaffold(
        topBar = {
            AppTopBar(actions = {
                IconButton(onClick = onAdmin, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Settings, contentDescription = "관리자 설정", tint = AppTheme.colors.textTertiary)
                }
            })
        },
        bottom = { BottomCta("새 글 쓰기", onClick = { if (state.hasBlogId) onNewPost() else onLogin("compose") }) },
    ) {
        Spacer(Modifier.height(AppSpacing.section))
        updateInfo?.let { info ->
            InlineBanner("새 버전(${info.tag})이 나왔어요 — 받으러 가기", BannerKind.Info) {
                context.startActivity(Intent(Intent.ACTION_VIEW, info.htmlUrl.toUri()))
            }
            Spacer(Modifier.height(AppSpacing.md))
            WeakButton("닫기", onClick = viewModel::dismissUpdate)
            Spacer(Modifier.height(AppSpacing.lg))
        }
        Text("오늘은 어떤 이야기를\n올릴까요?", style = AppTheme.typography.title1, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(AppSpacing.section))
        if (state.pendingJobId != null) {
            InlineBanner("올리다 만 글이 있어요: ${state.pendingTitle ?: ""}", BannerKind.Info) { onResumePending(state.pendingJobId!!) }
            Spacer(Modifier.height(AppSpacing.lg))
        }
        if (!state.hasBlogId) {
            InlineBanner("네이버 로그인이 필요해요", BannerKind.Warning) { onLogin("home") }
            Spacer(Modifier.height(AppSpacing.lg))
        }
        ListRow(title = "발행한 글", subtitle = "지금까지 올린 글을 볼 수 있어요", onClick = onHistory)
    }
}
