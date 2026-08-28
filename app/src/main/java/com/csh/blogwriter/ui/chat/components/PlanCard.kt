package com.csh.blogwriter.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.chat.Plan
import com.csh.blogwriter.ui.components.ListRow
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/** 어시스턴트의 계획 카드: 제목 후보(탭해서 고름) + 개요 + 톤. */
@Composable
fun PlanCard(plan: Plan, onPickTitle: (index: Int, title: String) -> Unit) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = AppSpacing.xs)) {
        Column(
            Modifier.fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(AppSpacing.radiusCard))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(AppSpacing.radiusCard))
                .padding(AppSpacing.lg),
        ) {
            Text("제목은 어떤 게 좋으세요?", style = AppTheme.typography.title3, color = c.textPrimary)
            Spacer(Modifier.height(AppSpacing.md))
            plan.titleCandidates.forEachIndexed { index, title ->
                ListRow(title = title, subtitle = "${index + 1}번", onClick = { onPickTitle(index + 1, title) })
                Spacer(Modifier.height(AppSpacing.sm))
            }
            if (plan.outline.isNotEmpty()) {
                Spacer(Modifier.height(AppSpacing.md))
                Text("이런 순서로 써 볼게요", style = AppTheme.typography.title3, color = c.textPrimary)
                Spacer(Modifier.height(AppSpacing.sm))
                plan.outline.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = AppSpacing.xs)) {
                        Text("${index + 1}.", style = AppTheme.typography.body1, color = c.textSecondary)
                        Spacer(Modifier.width(AppSpacing.sm))
                        Column {
                            Text(item.heading, style = AppTheme.typography.body1, color = c.textPrimary)
                            Text(
                                item.summary + if (item.photoRefs.isEmpty()) "" else " (사진 ${item.photoRefs.size}장)",
                                style = AppTheme.typography.body2, color = c.textSecondary,
                            )
                        }
                    }
                }
            }
            if (plan.tone.isNotBlank()) {
                Spacer(Modifier.height(AppSpacing.md))
                Text("말투: ${plan.tone}", style = AppTheme.typography.caption, color = c.textSecondary)
            }
        }
    }
}
