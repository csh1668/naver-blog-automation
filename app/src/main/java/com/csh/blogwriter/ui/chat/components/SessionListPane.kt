package com.csh.blogwriter.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.data.repo.ChatSession
import com.csh.blogwriter.data.repo.SessionStatus
import com.csh.blogwriter.ui.components.WeakButton
import com.csh.blogwriter.ui.format.DateFormats
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

val SessionListWidth = 280.dp
val SessionRailWidth = 72.dp

/** 왼쪽 대화 기록. 접으면 아이콘만 남는 72dp 바가 된다. */
@Composable
fun SessionListPane(
    sessions: List<ChatSession>,
    currentId: String?,
    collapsed: Boolean,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onToggle: () -> Unit,
) {
    val c = AppTheme.colors
    Column(
        Modifier.fillMaxSize().background(c.backgroundAlt).padding(AppSpacing.sm),
        horizontalAlignment = if (collapsed) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle, modifier = Modifier.size(AppSpacing.touchTarget)) {
                Icon(
                    Icons.AutoMirrored.Rounded.List,
                    contentDescription = if (collapsed) "대화 기록 펼치기" else "대화 기록 접기",
                    tint = c.textSecondary,
                )
            }
            if (!collapsed) {
                Text("대화 기록", style = AppTheme.typography.title3, color = c.textPrimary, modifier = Modifier.padding(start = AppSpacing.sm))
            }
        }
        Spacer(Modifier.height(AppSpacing.sm))
        if (collapsed) {
            IconButton(onClick = onNew, modifier = Modifier.size(AppSpacing.touchTarget)) {
                Icon(Icons.Rounded.Add, contentDescription = "새 글 쓰기", tint = c.fillBrand)
            }
        } else {
            WeakButton("새 글 쓰기", onClick = onNew)
            Spacer(Modifier.height(AppSpacing.md))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(session, selected = session.id == currentId, onClick = { onSelect(session.id) })
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: ChatSession, selected: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().heightIn(min = AppSpacing.touchTarget)
            .clip(RoundedCornerShape(AppSpacing.radiusControl))
            .background(if (selected) c.fillBrandWeak else c.surface)
            .clickable(onClick = onClick)
            .padding(AppSpacing.md),
    ) {
        Text(
            session.title ?: "새 글",
            style = AppTheme.typography.body1,
            color = if (selected) c.fillBrand else c.textPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${statusLabel(session.status)} · ${DateFormats.relative(session.updatedAt)}",
            style = AppTheme.typography.caption, color = c.textSecondary, maxLines = 1,
        )
    }
}

private fun statusLabel(status: SessionStatus) = when (status) {
    SessionStatus.DRAFTING -> "쓰는 중"
    SessionStatus.PUBLISHING -> "올리는 중"
    SessionStatus.PUBLISHED -> "올림"
    SessionStatus.ARCHIVED -> "보관"
}
