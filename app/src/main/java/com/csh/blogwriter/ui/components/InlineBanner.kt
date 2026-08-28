package com.csh.blogwriter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

enum class BannerKind { Info, Danger, Success, Warning }

@Composable
fun InlineBanner(text: String, kind: BannerKind = BannerKind.Info, onClick: (() -> Unit)? = null) {
    val c = AppTheme.colors
    val (bg, fg, icon) = when (kind) {
        BannerKind.Info -> Triple(c.fillBrandWeak, c.fillBrand, Icons.Rounded.Info)
        BannerKind.Danger -> Triple(c.fillDangerWeak, c.fillDanger, Icons.Rounded.Error)
        BannerKind.Success -> Triple(c.fillSuccessWeak, c.fillSuccess, Icons.Rounded.CheckCircle)
        BannerKind.Warning -> Triple(c.surfaceWeak, c.fillWarning, Icons.Rounded.Warning)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(AppSpacing.radiusControl)).background(bg)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg)
        Spacer(Modifier.width(AppSpacing.md))
        Text(text, style = AppTheme.typography.body2, color = c.textPrimary, modifier = Modifier.weight(1f))
        if (onClick != null) Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = fg)
    }
}
