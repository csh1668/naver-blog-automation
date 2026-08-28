package com.csh.blogwriter.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    Column(modifier) {
        Text(label, style = AppTheme.typography.body2, color = c.textSecondary)
        Spacer(Modifier.height(AppSpacing.sm))
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine, minLines = minLines,
            textStyle = AppTheme.typography.body1.copy(color = c.textPrimary),
            placeholder = placeholder?.let { { Text(it, style = AppTheme.typography.body1, color = c.textTertiary) } },
            shape = RoundedCornerShape(AppSpacing.radiusControl),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.surfaceWeak, unfocusedContainerColor = c.surfaceWeak,
                focusedBorderColor = c.fillBrand, unfocusedBorderColor = c.surfaceWeak,
                cursorColor = c.fillBrand,
            ),
        )
    }
}
