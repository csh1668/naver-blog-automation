package com.csh.blogwriter.ui.home

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.data.repo.SessionStatus
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.BannerKind
import com.csh.blogwriter.ui.components.BottomCta
import com.csh.blogwriter.ui.components.InlineBanner
import com.csh.blogwriter.ui.components.ListRow
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.components.WeakButton
import com.csh.blogwriter.ui.format.DateFormats
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

private const val VISIBLE_SESSION_COUNT = 3

@Composable
fun HomeScreen(
    onNewPost: () -> Unit,
    onLogin: (returnTo: String) -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    onResumePending: (jobId: String) -> Unit,
    onHistory: () -> Unit,
    onAdmin: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAllSessions by remember { mutableStateOf(false) }
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
        Column(Modifier.verticalScroll(rememberScrollState())) {
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
            if (state.legacyPendingJobId != null) {
                InlineBanner("올리다 만 글이 있어요: ${state.legacyPendingTitle ?: ""}", BannerKind.Info) { onResumePending(state.legacyPendingJobId!!) }
                Spacer(Modifier.height(AppSpacing.lg))
            }
            if (!state.hasBlogId) {
                InlineBanner("네이버 로그인이 필요해요", BannerKind.Warning) { onLogin("home") }
                Spacer(Modifier.height(AppSpacing.lg))
            }
            if (!state.hasKey) {
                InlineBanner("글을 쓰려면 관리자가 열쇠를 등록해야 해요", BannerKind.Warning, onClick = onAdmin)
                Spacer(Modifier.height(AppSpacing.lg))
            }
            if (state.sessions.isNotEmpty()) {
                Text("이어서 쓰기", style = AppTheme.typography.title3, color = AppTheme.colors.textPrimary)
                Spacer(Modifier.height(AppSpacing.md))
                val visible = if (showAllSessions) state.sessions else state.sessions.take(VISIBLE_SESSION_COUNT)
                visible.forEach { session ->
                    ListRow(
                        title = session.title ?: "새 글",
                        subtitle = "${sessionStatusLabel(session.status)} · ${DateFormats.relative(session.updatedAt)}",
                        onClick = { onOpenSession(session.id) },
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                }
                if (!showAllSessions && state.sessions.size > VISIBLE_SESSION_COUNT) {
                    WeakButton("더보기", onClick = { showAllSessions = true })
                }
                Spacer(Modifier.height(AppSpacing.lg))
            }
            ListRow(title = "발행한 글", subtitle = "지금까지 올린 글을 볼 수 있어요", onClick = onHistory)
        }
    }
}

private fun sessionStatusLabel(status: SessionStatus) = when (status) {
    SessionStatus.DRAFTING -> "쓰는 중"
    SessionStatus.PUBLISHING -> "올리는 중"
    SessionStatus.PUBLISHED -> "올림"
    SessionStatus.ARCHIVED -> "보관"
}
