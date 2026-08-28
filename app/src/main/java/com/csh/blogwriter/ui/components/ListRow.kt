package com.csh.blogwriter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailingChevron: Boolean = onClick != null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clip(RoundedCornerShape(AppSpacing.radiusCard))
            .background(AppTheme.colors.surfaceWeak)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(AppSpacing.lg)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTheme.typography.title3, color = AppTheme.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = AppTheme.typography.body2, color = AppTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailingChevron) Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = AppTheme.colors.textTertiary)
    }
}
