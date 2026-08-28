package com.csh.blogwriter.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.data.repo.ChatSession
import com.csh.blogwriter.data.repo.SessionStatus
import com.csh.blogwriter.ui.components.AppTextField
import com.csh.blogwriter.ui.components.ConfirmSheet
import com.csh.blogwriter.ui.components.WeakButton
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
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
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
                    SessionRow(
                        session, selected = session.id == currentId,
                        onClick = { onSelect(session.id) },
                        onDelete = onDelete, onRename = onRename,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: ChatSession,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
) {
    val c = AppTheme.colors
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().heightIn(min = AppSpacing.touchTarget)
            .clip(RoundedCornerShape(AppSpacing.radiusControl))
            .background(if (selected) c.fillBrandWeak else c.surface)
            .clickable(onClick = onClick)
            .padding(start = AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = AppSpacing.md)) {
            Text(
                session.title ?: "새 글",
                style = AppTheme.typography.body1,
                color = if (selected) c.fillBrand else c.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${relativeTime(session.updatedAt)} · ${statusLabel(session.status)}",
                style = AppTheme.typography.caption, color = c.textSecondary, maxLines = 1,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(AppSpacing.touchTarget)) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "대화 메뉴", tint = c.textSecondary)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("이름 바꾸기") }, onClick = { menuExpanded = false; renaming = true })
                DropdownMenuItem(text = { Text("지우기", color = c.fillDanger) }, onClick = { menuExpanded = false; confirmingDelete = true })
            }
        }
    }

    ConfirmSheet(
        visible = confirmingDelete,
        title = "이 대화를 지울까요?",
        message = "지운 대화는 되돌릴 수 없어요.",
        confirmText = "지우기",
        onConfirm = { confirmingDelete = false; onDelete(session.id) },
        dismissText = "취소",
        onDismiss = { confirmingDelete = false },
        danger = true,
    )
    if (renaming) {
        RenameSheet(
            initialTitle = session.title.orEmpty(),
            onSave = { renaming = false; onRename(session.id, it) },
            onDismiss = { renaming = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameSheet(initialTitle: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialTitle) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = AppSpacing.radiusSheet, topEnd = AppSpacing.radiusSheet),
        containerColor = AppTheme.colors.surface,
    ) {
        Column(Modifier.padding(horizontal = AppSpacing.screenHorizontal).padding(bottom = AppSpacing.section)) {
            Text("이름 바꾸기", style = AppTheme.typography.title2, color = AppTheme.colors.textPrimary)
            Spacer(Modifier.height(AppSpacing.md))
            AppTextField(value = text, onValueChange = { text = it }, label = "대화 이름")
            Spacer(Modifier.height(AppSpacing.section))
            WeakButton("저장", onClick = { val trimmed = text.trim(); if (trimmed.isNotEmpty()) onSave(trimmed) }, enabled = text.isNotBlank())
            Spacer(Modifier.height(AppSpacing.md))
            WeakButton("취소", onDismiss)
        }
    }
}

private fun statusLabel(status: SessionStatus) = when (status) {
    SessionStatus.DRAFTING -> "쓰는 중"
    SessionStatus.PUBLISHING -> "올리는 중"
    SessionStatus.PUBLISHED -> "올림"
    SessionStatus.ARCHIVED -> "보관"
}
