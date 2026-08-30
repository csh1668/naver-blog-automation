package com.csh.blogwriter.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/** 모델의 생각 요약. 연하고 작게, 접으면 첫 줄만. 탭으로 펴고 접는다(터치 56dp). */
@Composable
fun ThoughtBlock(text: String, expanded: Boolean, onToggle: () -> Unit) {
    val c = AppTheme.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = AppSpacing.touchTarget).clickable(onClick = onToggle)
            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            // 생각 요약은 영어 마크다운으로 온다 — 굵게 표시만 떼어 내고 그대로 보여 준다.
            text.replace("**", "").trim(),
            style = AppTheme.typography.caption, color = c.textTertiary,
            maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
