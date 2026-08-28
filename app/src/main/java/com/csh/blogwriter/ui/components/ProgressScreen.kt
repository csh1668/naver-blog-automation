package com.csh.blogwriter.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/** 전체 화면 진행 표시. progress 가 null 이면 불확정 바. */
@Composable
fun ProgressScreen(title: String, detail: String?, progress: Float?, onCancel: (() -> Unit)? = null) {
    ScreenScaffold(bottom = if (onCancel != null) ({ WeakButton("그만두기", onCancel) }) else null) {
        Spacer(Modifier.height(AppSpacing.huge * 2))
        Text(title, style = AppTheme.typography.title1, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(AppSpacing.xxl))
        val barModifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        if (progress == null) LinearProgressIndicator(modifier = barModifier, color = AppTheme.colors.fillBrand, trackColor = AppTheme.colors.surfaceWeak)
        else LinearProgressIndicator(progress = { progress }, modifier = barModifier, color = AppTheme.colors.fillBrand, trackColor = AppTheme.colors.surfaceWeak)
        if (detail != null) {
            Spacer(Modifier.height(AppSpacing.md))
            Text(detail, style = AppTheme.typography.body2, color = AppTheme.colors.textSecondary)
        }
    }
}
