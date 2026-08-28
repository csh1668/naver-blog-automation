package com.csh.blogwriter.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun ResultScreen(
    success: Boolean,
    title: String,
    message: String?,
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    ScreenScaffold(bottom = {
        BottomCta(primaryText, onPrimary)
        if (secondaryText != null && onSecondary != null) {
            Spacer(Modifier.height(AppSpacing.md))
            WeakButton(secondaryText, onSecondary)
        }
    }) {
        Spacer(Modifier.height(AppSpacing.huge * 2))
        Icon(
            if (success) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
            contentDescription = null,
            tint = if (success) AppTheme.colors.fillSuccess else AppTheme.colors.fillDanger,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(AppSpacing.xxl))
        Text(title, style = AppTheme.typography.display, color = AppTheme.colors.textPrimary)
        if (message != null) {
            Spacer(Modifier.height(AppSpacing.lg))
            Text(message, style = AppTheme.typography.body1, color = AppTheme.colors.textSecondary)
        }
    }
}
