package com.csh.blogwriter.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun AppTopBar(
    onBack: (() -> Unit)? = null,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로 가기", tint = AppTheme.colors.textPrimary)
            }
        }
        Box(Modifier.weight(1f).padding(start = AppSpacing.sm)) {
            if (title != null) Text(title, style = AppTheme.typography.title3, color = AppTheme.colors.textPrimary)
        }
        actions()
    }
}
