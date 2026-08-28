package com.csh.blogwriter.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.ui.components.AppTextField
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.BannerKind
import com.csh.blogwriter.ui.components.BottomCta
import com.csh.blogwriter.ui.components.InlineBanner
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/** 관리자용 모델 정책 편집. 기술 용어 허용. */
@Composable
fun ModelsScreen(onBack: () -> Unit, viewModel: ModelsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ScreenScaffold(
        topBar = { AppTopBar(onBack = onBack, title = "모델과 글 길이") },
        bottom = { BottomCta("저장", onClick = viewModel::save) },
    ) {
        Spacer(Modifier.height(AppSpacing.section))
        ModelDropdownField(
            value = state.primaryModel, onValueChange = viewModel::onPrimaryModelChange, label = "기본 모델",
            options = state.availableModels,
        )
        Spacer(Modifier.height(AppSpacing.lg))
        ModelDropdownField(
            value = state.secondaryModel, onValueChange = viewModel::onSecondaryModelChange, label = "대체 모델",
            options = state.availableModels,
        )
        Spacer(Modifier.height(AppSpacing.sm))
        Row {
            TextButton(onClick = viewModel::refreshModels) {
                Text(if (state.modelsLoading) "불러오는 중…" else "목록 새로고침", style = AppTheme.typography.body2, color = AppTheme.colors.fillBrand)
            }
        }
        val modelsError = state.modelsError
        if (modelsError != null) {
            Spacer(Modifier.height(AppSpacing.sm))
            InlineBanner(modelsError, BannerKind.Warning)
        }
        Spacer(Modifier.height(AppSpacing.lg))
        AppTextField(
            value = state.temperature, onValueChange = viewModel::onTemperatureChange, label = "온도 (0.0~2.0)",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Spacer(Modifier.height(AppSpacing.lg))
        Row {
            AppTextField(
                value = state.minLength, onValueChange = viewModel::onMinLengthChange, label = "글 길이 최소",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(AppSpacing.lg))
            AppTextField(
                value = state.maxLength, onValueChange = viewModel::onMaxLengthChange, label = "글 길이 최대",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        val error = state.error
        if (error != null) {
            Spacer(Modifier.height(AppSpacing.lg))
            InlineBanner(error, BannerKind.Danger)
        } else if (state.saved) {
            Spacer(Modifier.height(AppSpacing.lg))
            InlineBanner("저장했어요", BannerKind.Success)
        }
    }
}

/** [AppTextField] 와 같은 스타일을 쓰되, `availableModels` 를 입력값 접두사로 필터링해 보여주는 드롭다운을 덧붙인다.
 * 직접 입력도 항상 허용한다 — 목록에 없는 모델명도 유효한 값이다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdownField(value: String, onValueChange: (String) -> Unit, label: String, options: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val c = AppTheme.colors
    val filtered = remember(value, options) {
        if (value.isBlank()) options else options.filter { it.contains(value, ignoreCase = true) }
    }

    Column {
        Text(label, style = AppTheme.typography.body2, color = c.textSecondary)
        Spacer(Modifier.height(AppSpacing.sm))
        ExposedDropdownMenuBox(expanded = expanded && filtered.isNotEmpty(), onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = value,
                onValueChange = { onValueChange(it); expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppSpacing.touchTarget)
                    .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true),
                singleLine = true,
                textStyle = AppTheme.typography.body1.copy(color = c.textPrimary),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                shape = RoundedCornerShape(AppSpacing.radiusControl),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.surfaceWeak, unfocusedContainerColor = c.surfaceWeak,
                    focusedBorderColor = c.fillBrand, unfocusedBorderColor = c.surfaceWeak,
                    cursorColor = c.fillBrand,
                ),
            )
            ExposedDropdownMenu(expanded = expanded && filtered.isNotEmpty(), onDismissRequest = { expanded = false }) {
                filtered.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, style = AppTheme.typography.body1) },
                        onClick = { onValueChange(option); expanded = false },
                    )
                }
            }
        }
    }
}
