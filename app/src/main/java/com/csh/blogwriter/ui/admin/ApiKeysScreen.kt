package com.csh.blogwriter.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.llm.ApiKey
import com.csh.blogwriter.ui.components.AppTextField
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.BottomCta
import com.csh.blogwriter.ui.components.ConfirmSheet
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.format.DateFormats
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/** 관리자용 API 키 등록·관리. 기술 용어 허용. */
@Composable
fun ApiKeysScreen(onBack: () -> Unit, viewModel: ApiKeysViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ApiKey?>(null) }

    ScreenScaffold(
        topBar = { AppTopBar(onBack = onBack, title = "API 키") },
        bottom = {
            BottomCta(
                text = "등록", onClick = viewModel::register,
                enabled = state.candidates.isNotEmpty() && !state.busy, loading = state.busy,
            )
        },
    ) {
        Spacer(Modifier.height(AppSpacing.section))
        AppTextField(
            value = text,
            onValueChange = { text = it; viewModel.onInput(it) },
            label = "키 붙여넣기 — 여러 개면 줄마다 하나씩",
            singleLine = false, minLines = 3,
        )
        Spacer(Modifier.height(AppSpacing.sm))
        Text("키는 서로 다른 프로젝트에서 발급해야 해요", style = AppTheme.typography.caption, color = AppTheme.colors.textTertiary)
        if (state.candidates.isNotEmpty()) {
            Spacer(Modifier.height(AppSpacing.lg))
            Column {
                state.candidates.forEach { candidate ->
                    CandidateChip(candidate)
                    Spacer(Modifier.height(AppSpacing.sm))
                }
            }
        }
        Spacer(Modifier.height(AppSpacing.section))
        Text("등록된 키", style = AppTheme.typography.title3, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(AppSpacing.md))
        LazyColumn {
            items(state.keys, key = { it.id }) { key ->
                ApiKeyRow(key, onDelete = { deleteTarget = key })
                Spacer(Modifier.height(AppSpacing.md))
            }
        }
    }

    ConfirmSheet(
        visible = deleteTarget != null,
        title = "키를 지울까요?",
        message = deleteTarget?.masked?.let { "$it 키를 등록에서 지워요" },
        confirmText = "지우기",
        onConfirm = { deleteTarget?.let { viewModel.remove(it.id) }; deleteTarget = null },
        dismissText = "취소",
        onDismiss = { deleteTarget = null },
        danger = true,
    )
}

@Composable
private fun CandidateChip(candidate: Candidate) {
    val c = AppTheme.colors
    val (bg, fg, label) = when (candidate.status) {
        Candidate.Status.PENDING -> Triple(c.surfaceWeak, c.textSecondary, "확인 전")
        Candidate.Status.VALID -> Triple(c.fillSuccessWeak, c.fillSuccess, "정상")
        Candidate.Status.INVALID -> Triple(c.fillDangerWeak, c.fillDanger, "무효")
        Candidate.Status.LIMITED -> Triple(c.surfaceWeak, c.fillWarning, "한도 초과")
        Candidate.Status.ERROR -> Triple(c.fillDangerWeak, c.fillDanger, "오류")
    }
    Row(
        Modifier.wrapContentWidth().clip(RoundedCornerShape(AppSpacing.radiusControl)).background(bg).padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(candidate.masked, style = AppTheme.typography.body2, color = fg)
        Spacer(Modifier.width(AppSpacing.sm))
        Text(label, style = AppTheme.typography.caption, color = fg)
    }
}

@Composable
private fun ApiKeyRow(key: ApiKey, onDelete: () -> Unit) {
    val subtitle = buildString {
        append(if (key.lastOkAt != null) "마지막 확인 ${DateFormats.relative(key.lastOkAt)}" else "확인된 적 없어요")
        if (key.lastLimitedAt != null) append(" · 최근 한도 초과")
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = AppSpacing.touchTarget)
            .clip(RoundedCornerShape(AppSpacing.radiusCard))
            .background(AppTheme.colors.surfaceWeak)
            .padding(AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(key.masked, style = AppTheme.typography.title3, color = AppTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = AppTheme.typography.body2, color = AppTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Close, contentDescription = "키 삭제", tint = AppTheme.colors.fillDanger)
        }
    }
}
