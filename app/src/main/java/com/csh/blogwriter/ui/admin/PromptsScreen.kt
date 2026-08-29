package com.csh.blogwriter.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.chat.PromptGroup
import com.csh.blogwriter.chat.PromptSection
import com.csh.blogwriter.ui.components.AppTextField
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.ConfirmSheet
import com.csh.blogwriter.ui.components.DangerButton
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.components.WeakButton
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/** 관리자용 프롬프트 섹션 편집. 기술 용어 허용. */
@Composable
fun PromptsScreen(onBack: () -> Unit, viewModel: PromptsViewModel = hiltViewModel()) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    var resetTarget by remember { mutableStateOf<PromptSection?>(null) }

    ScreenScaffold(topBar = { AppTopBar(onBack = onBack, title = "프롬프트") }) {
        Spacer(Modifier.height(AppSpacing.lg))
        Text(
            "여기 내용이 글을 만드는 규칙이에요. 바꾼 뒤 저장하면 다음 대화부터 적용돼요.",
            style = AppTheme.typography.body2, color = AppTheme.colors.textSecondary,
        )
        Text(
            "제목에 * 가 붙은 섹션은 직접 고친 것이라, 앱을 업데이트해도 새 기본값이 적용되지 않아요. 새 기본값을 쓰려면 되돌려 주세요.",
            style = AppTheme.typography.body2, color = AppTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(AppSpacing.xxl))
        LazyColumn {
            PromptGroup.entries.forEach { group ->
                val inGroup = sections.filter { it.section.group == group }
                if (inGroup.isEmpty()) return@forEach
                item(key = "header-${group.name}") {
                    Text(group.title, style = AppTheme.typography.title2, color = AppTheme.colors.textPrimary, modifier = Modifier.padding(bottom = AppSpacing.md))
                }
                items(inGroup, key = { it.section }) { state ->
                    PromptCard(
                        state = state,
                        onSave = { text -> viewModel.save(state.section, text) },
                        onResetRequest = { resetTarget = state.section },
                    )
                    Spacer(Modifier.height(AppSpacing.lg))
                }
                item(key = "gap-${group.name}") { Spacer(Modifier.height(AppSpacing.xl)) }
            }
        }
    }

    ConfirmSheet(
        visible = resetTarget != null,
        title = "기본값으로 되돌릴까요?",
        message = resetTarget?.let { "${it.title} 섹션을 기본값으로 되돌려요" },
        confirmText = "되돌리기",
        onConfirm = { resetTarget?.let { viewModel.reset(it) }; resetTarget = null },
        dismissText = "취소",
        onDismiss = { resetTarget = null },
        danger = true,
    )
}

@Composable
private fun PromptCard(state: PromptSectionState, onSave: (String) -> Unit, onResetRequest: () -> Unit) {
    var localText by remember(state.section) { mutableStateOf(state.text) }
    var dirty by remember(state.section) { mutableStateOf(false) }
    LaunchedEffect(state.text) { if (!dirty) localText = state.text }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.radiusCard))
            .background(AppTheme.colors.surfaceWeak)
            .padding(AppSpacing.lg),
    ) {
        Row {
            Text(if (state.overridden) "${state.section.title} *" else state.section.title, style = AppTheme.typography.title3, color = AppTheme.colors.textPrimary, modifier = Modifier.weight(1f))
            if (state.overridden) OverriddenBadge()
        }
        Spacer(Modifier.height(AppSpacing.md))
        AppTextField(
            value = localText,
            onValueChange = { localText = it; dirty = true },
            label = state.section.name,
            singleLine = false, minLines = 6,
        )
        Spacer(Modifier.height(AppSpacing.md))
        WeakButton("저장", onClick = { onSave(localText); dirty = false }, enabled = localText.isNotBlank())
        if (state.overridden) {
            Spacer(Modifier.height(AppSpacing.sm))
            DangerButton("기본값으로 되돌리기", onClick = onResetRequest)
        }
    }
}

@Composable
private fun OverriddenBadge() {
    Text(
        "수정됨", style = AppTheme.typography.caption, color = AppTheme.colors.fillBrand,
        modifier = Modifier.wrapContentWidth()
            .clip(RoundedCornerShape(AppSpacing.radiusControl))
            .background(AppTheme.colors.fillBrandWeak)
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
    )
}
