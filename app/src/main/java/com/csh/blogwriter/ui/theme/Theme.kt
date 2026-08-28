package com.csh.blogwriter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = LightAppColors
    val scheme = lightColorScheme(
        primary = colors.fillBrand, onPrimary = colors.textOnBrand,
        background = colors.background, onBackground = colors.textPrimary,
        surface = colors.surface, onSurface = colors.textPrimary,
        surfaceVariant = colors.surfaceWeak, onSurfaceVariant = colors.textSecondary,
        error = colors.fillDanger, outline = colors.border,
    )
    CompositionLocalProvider(LocalAppColors provides colors, LocalAppTypography provides DefaultAppTypography) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

object AppTheme {
    val colors: AppColors @Composable @ReadOnlyComposable get() = LocalAppColors.current
    val typography: AppTypography @Composable @ReadOnlyComposable get() = LocalAppTypography.current
}
