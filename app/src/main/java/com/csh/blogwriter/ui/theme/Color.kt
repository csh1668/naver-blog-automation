package com.csh.blogwriter.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

object Palette {
    val Blue500 = Color(0xFF3182F6); val Blue600 = Color(0xFF1B64DA); val Blue100 = Color(0xFFE8F3FF)
    val Grey50 = Color(0xFFF9FAFB); val Grey100 = Color(0xFFF2F4F6); val Grey200 = Color(0xFFE5E8EB)
    val Grey300 = Color(0xFFD1D6DB); val Grey400 = Color(0xFFB0B8C1); val Grey500 = Color(0xFF8B95A1)
    val Grey600 = Color(0xFF6B7684); val Grey700 = Color(0xFF4E5968); val Grey800 = Color(0xFF333D4B)
    val Grey900 = Color(0xFF191F28)
    val Red500 = Color(0xFFF04452); val Red100 = Color(0xFFFFEEEE)
    val Green500 = Color(0xFF03B26C); val Green100 = Color(0xFFE5F7EF)
    val Orange500 = Color(0xFFFF9E2C)
    val White = Color(0xFFFFFFFF)
}

@Immutable
data class AppColors(
    val background: Color, val backgroundAlt: Color, val surface: Color, val surfaceWeak: Color, val border: Color,
    val textPrimary: Color, val textSecondary: Color, val textTertiary: Color, val textOnBrand: Color,
    val fillBrand: Color, val fillBrandPressed: Color, val fillBrandWeak: Color,
    val fillDanger: Color, val fillDangerWeak: Color, val fillSuccess: Color, val fillSuccessWeak: Color, val fillWarning: Color,
)

val LightAppColors = AppColors(
    background = Palette.White, backgroundAlt = Palette.Grey50, surface = Palette.White, surfaceWeak = Palette.Grey100, border = Palette.Grey200,
    textPrimary = Palette.Grey900, textSecondary = Palette.Grey600, textTertiary = Palette.Grey400, textOnBrand = Palette.White,
    fillBrand = Palette.Blue500, fillBrandPressed = Palette.Blue600, fillBrandWeak = Palette.Blue100,
    fillDanger = Palette.Red500, fillDangerWeak = Palette.Red100, fillSuccess = Palette.Green500, fillSuccessWeak = Palette.Green100, fillWarning = Palette.Orange500,
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
