package com.csh.blogwriter.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    visible: Boolean,
    title: String,
    message: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    if (!visible) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = AppSpacing.radiusSheet, topEnd = AppSpacing.radiusSheet),
        containerColor = AppTheme.colors.surface,
    ) {
        Column(Modifier.padding(horizontal = AppSpacing.screenHorizontal).padding(bottom = AppSpacing.section)) {
            Text(title, style = AppTheme.typography.title2, color = AppTheme.colors.textPrimary)
            if (message != null) {
                Spacer(Modifier.height(AppSpacing.md))
                Text(message, style = AppTheme.typography.body1, color = AppTheme.colors.textSecondary)
            }
            Spacer(Modifier.height(AppSpacing.section))
            if (danger) DangerButton(confirmText, onConfirm) else BottomCta(confirmText, onConfirm)
            Spacer(Modifier.height(AppSpacing.md))
            WeakButton(dismissText, onDismiss)
        }
    }
}
