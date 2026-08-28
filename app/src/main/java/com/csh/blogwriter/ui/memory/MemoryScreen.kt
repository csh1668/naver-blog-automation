package com.csh.blogwriter.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.ui.components.AppTextField
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.ConfirmSheet
import com.csh.blogwriter.ui.components.DangerButton
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.components.WeakButton
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

private fun MemoryKind.label() = when (this) {
    MemoryKind.STYLE -> "말투"
    MemoryKind.PREFERENCE -> "취향"
    MemoryKind.FACT -> "사실"
    MemoryKind.EXPRESSION -> "표현"
}

/** 기억한 것들. 사용자용 화면 — "~해요"체, 쉬운 말. */
@Composable
fun MemoryScreen(onBack: () -> Unit, viewModel: MemoryViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    var newText by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var deleteTarget by remember { mutableStateOf<MemoryItem?>(null) }

    ScreenScaffold(topBar = { AppTopBar(onBack = onBack) }) {
        Spacer(Modifier.height(AppSpacing.lg))
        Text("기억한 것들", style = AppTheme.typography.title1, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(AppSpacing.sm))
        Text(
            "글을 쓸 때 참고해요. 눌러서 고치거나 지울 수 있어요.",
            style = AppTheme.typography.body2, color = AppTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(AppSpacing.xxl))
        AppTextField(value = newText, onValueChange = { newText = it }, label = "새로 기억할 내용")
        Spacer(Modifier.height(AppSpacing.sm))
        WeakButton(
            "추가",
            onClick = { viewModel.add(newText); newText = "" },
            enabled = newText.isNotBlank(),
        )
        Spacer(Modifier.height(AppSpacing.section))
        if (items.isEmpty()) {
            Text(
                "아직 기억한 게 없어요. 대화하면서 자동으로 기억해요.",
                style = AppTheme.typography.body1, color = AppTheme.colors.textSecondary,
            )
        } else {
            LazyColumn {
                items(items, key = { it.id }) { item ->
                    MemoryCard(
                        item = item,
                        editing = editingId == item.id,
                        onTap = { editingId = if (editingId == item.id) null else item.id },
                        onToggle = { enabled -> viewModel.toggle(item.id, enabled) },
                        onSave = { text -> viewModel.edit(item.id, text); editingId = null },
                        onCancel = { editingId = null },
                        onDeleteRequest = { deleteTarget = item },
                    )
                    Spacer(Modifier.height(AppSpacing.md))
                }
            }
        }
    }

    ConfirmSheet(
        visible = deleteTarget != null,
        title = "이 기억을 지울까요?",
        message = deleteTarget?.text,
        confirmText = "지우기",
        onConfirm = { deleteTarget?.let { viewModel.delete(it.id) }; deleteTarget = null; editingId = null },
        dismissText = "취소",
        onDismiss = { deleteTarget = null },
        danger = true,
    )
}

@Composable
private fun MemoryCard(
    item: MemoryItem,
    editing: Boolean,
    onTap: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.radiusCard))
            .background(AppTheme.colors.surface)
            .let { if (!editing) it.clickable(onClick = onTap) else it }
            .padding(AppSpacing.lg),
    ) {
        if (editing) {
            var editedText by remember(item.id) { mutableStateOf(item.text) }
            KindChip(item.kind)
            Spacer(Modifier.height(AppSpacing.sm))
            AppTextField(value = editedText, onValueChange = { editedText = it }, label = "내용", singleLine = false, minLines = 2)
            Spacer(Modifier.height(AppSpacing.sm))
            WeakButton("저장", onClick = { onSave(editedText) }, enabled = editedText.isNotBlank())
            Spacer(Modifier.height(AppSpacing.sm))
            WeakButton("취소", onClick = onCancel)
            Spacer(Modifier.height(AppSpacing.sm))
            DangerButton("지우기", onClick = onDeleteRequest)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    KindChip(item.kind)
                    Spacer(Modifier.height(AppSpacing.sm))
                    Text(item.text, style = AppTheme.typography.body1, color = AppTheme.colors.textPrimary)
                }
                Spacer(Modifier.width(AppSpacing.md))
                Switch(
                    checked = item.enabled, onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = AppTheme.colors.fillBrand),
                )
            }
        }
    }
}

@Composable
private fun KindChip(kind: MemoryKind) {
    Text(
        kind.label(), style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary,
        modifier = Modifier.wrapContentWidth()
            .clip(RoundedCornerShape(AppSpacing.radiusControl))
            .background(AppTheme.colors.surfaceWeak)
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
    )
}
