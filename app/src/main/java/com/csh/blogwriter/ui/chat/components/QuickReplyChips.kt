package com.csh.blogwriter.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/** 탭 한 번으로 답하는 칩. 높이는 터치 타겟(56dp)을 지킨다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickReplyChips(replies: List<String>, onClick: (String) -> Unit) {
    if (replies.isEmpty()) return
    val c = AppTheme.colors
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        replies.forEach { reply ->
            Text(
                reply,
                style = AppTheme.typography.body1,
                color = c.fillBrand,
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(c.fillBrandWeak)
                    .clickable { onClick(reply) }
                    .defaultMinSize(minHeight = AppSpacing.touchTarget)
                    .padding(horizontal = AppSpacing.xl, vertical = AppSpacing.lg),
            )
        }
    }
}
