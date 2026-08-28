package com.csh.blogwriter.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.csh.blogwriter.ui.components.BottomCta
import com.csh.blogwriter.ui.components.WeakButton
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/**
 * 오른쪽 패널의 글 계획. 읽다가 연필을 누르면 그 자리에서 고칠 수 있다 —
 * 마크다운 기호는 보이지 않고, 제목은 제목답게 불릿은 불릿답게 생긴 칸으로 바뀐다.
 */
@Composable
fun PlanPanel(markdown: String, modifier: Modifier = Modifier, onSave: ((String) -> Unit)? = null) {
    val c = AppTheme.colors
    // 계획이 새로 오면(모델이 고쳐 주면) 편집을 접고 새 계획을 보여 준다.
    var editing by remember(markdown) { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(c.backgroundAlt)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AppSpacing.xl, vertical = AppSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (editing) "계획 고치기" else "글 계획",
                style = AppTheme.typography.title3, color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (!editing && onSave != null) {
                IconButton(onClick = { editing = true }, modifier = Modifier.size(AppSpacing.touchTarget)) {
                    Icon(Icons.Rounded.Edit, contentDescription = "계획 고치기", tint = c.fillBrand)
                }
            }
        }
        if (editing && onSave != null) {
            PlanEditor(
                markdown = markdown,
                onCancel = { editing = false },
                onSave = { edited -> editing = false; onSave(edited) },
            )
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.xl)) {
                MarkdownLite(markdown, Modifier.fillMaxWidth().padding(bottom = AppSpacing.section))
            }
        }
    }
}

/** 계획을 블록마다 한 칸씩 놓고 그 자리에서 고친다. 비운 칸은 저장할 때 그 줄째로 빠진다. */
@Composable
private fun ColumnScope.PlanEditor(markdown: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    val blocks = remember(markdown) { parseMarkdownLite(markdown) }
    val texts: SnapshotStateList<String> = remember(markdown) { blocks.map { spansToText(it.spans) }.toMutableStateList() }

    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.xl, vertical = AppSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        blocks.forEachIndexed { index, block ->
            val t = AppTheme.typography
            when (block) {
                is MdBlock.Heading -> BlockField(
                    value = texts[index],
                    onValueChange = { texts[index] = it },
                    style = when (block.level) { 1 -> t.title2; 2 -> t.title3; else -> t.body1 },
                    singleLine = true,
                )
                is MdBlock.Bullet -> MarkerField("•", texts[index]) { texts[index] = it }
                is MdBlock.Numbered -> MarkerField("${block.number}.", texts[index]) { texts[index] = it }
                is MdBlock.Paragraph -> BlockField(
                    value = texts[index],
                    onValueChange = { texts[index] = it },
                    style = t.body1,
                    singleLine = false,
                )
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = AppSpacing.xl, vertical = AppSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        Box(Modifier.weight(1f)) { WeakButton("취소", onClick = onCancel) }
        Box(Modifier.weight(1f)) {
            BottomCta("저장", onClick = {
                val kept = blocks.mapIndexedNotNull { index, block ->
                    texts[index].trim().ifEmpty { null }?.let { block.withText(it) }
                }
                onSave(blocksToMarkdown(kept))
            })
        }
    }
}

/** 불릿·번호 줄. 앞의 표시는 그대로 두고 뒤 글자만 고친다. */
@Composable
private fun MarkerField(marker: String, value: String, onValueChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(marker, style = AppTheme.typography.body1, color = AppTheme.colors.textTertiary)
        Spacer(Modifier.width(AppSpacing.sm))
        BlockField(value, onValueChange, AppTheme.typography.body1, singleLine = false, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BlockField(
    value: String,
    onValueChange: (String) -> Unit,
    style: TextStyle,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = style.copy(color = c.textPrimary),
        singleLine = singleLine,
        shape = RoundedCornerShape(AppSpacing.radiusControl),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.surfaceWeak, unfocusedContainerColor = c.surfaceWeak,
            focusedBorderColor = c.fillBrand, unfocusedBorderColor = c.surfaceWeak,
            cursorColor = c.fillBrand,
        ),
    )
}
