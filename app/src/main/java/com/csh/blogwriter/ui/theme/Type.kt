package com.csh.blogwriter.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class AppTypography(
    val display: TextStyle, val title1: TextStyle, val title2: TextStyle, val title3: TextStyle,
    val body1: TextStyle, val body2: TextStyle, val caption: TextStyle, val button: TextStyle,
)

private fun style(size: Int, weight: FontWeight, line: Int) =
    TextStyle(fontSize = size.sp, fontWeight = weight, lineHeight = line.sp)

val DefaultAppTypography = AppTypography(
    display = style(32, FontWeight.Bold, 40), title1 = style(26, FontWeight.Bold, 34),
    title2 = style(22, FontWeight.Bold, 30), title3 = style(19, FontWeight.SemiBold, 26),
    body1 = style(17, FontWeight.Normal, 26), body2 = style(15, FontWeight.Normal, 22),
    caption = style(13, FontWeight.Normal, 18), button = style(18, FontWeight.SemiBold, 24),
)

val LocalAppTypography = staticCompositionLocalOf { DefaultAppTypography }
