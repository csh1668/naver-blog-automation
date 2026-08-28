package com.csh.blogwriter.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun BottomCta(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) = CtaButton(text, onClick, modifier, enabled && !loading, loading,
    container = AppTheme.colors.fillBrand, content = AppTheme.colors.textOnBrand)

@Composable
fun WeakButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    CtaButton(text, onClick, modifier, enabled, false, container = AppTheme.colors.fillBrandWeak, content = AppTheme.colors.fillBrand)

@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    CtaButton(text, onClick, modifier, enabled, false, container = AppTheme.colors.fillDanger, content = AppTheme.colors.textOnBrand)

@Composable
private fun CtaButton(
    text: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean, loading: Boolean,
    container: Color, content: Color,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(AppSpacing.ctaHeight),
        shape = RoundedCornerShape(AppSpacing.radiusControl),
        colors = ButtonDefaults.buttonColors(
            containerColor = container, contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.4f), disabledContentColor = content,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = AppTheme.typography.button)
            if (loading) {
                Spacer(Modifier.width(AppSpacing.md))
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).testTag("bottom_cta_loading"),
                    color = content, strokeWidth = 2.dp,
                )
            }
        }
    }
}
