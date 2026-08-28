package com.csh.blogwriter.ui.admin

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/** 관리자용 모델 정책 편집. 기술 용어 허용. */
@Composable
fun ModelsScreen(onBack: () -> Unit, viewModel: ModelsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ScreenScaffold(
        topBar = { AppTopBar(onBack = onBack, title = "모델과 글 길이") },
        bottom = { BottomCta("저장", onClick = viewModel::save) },
    ) {
        Spacer(Modifier.height(AppSpacing.section))
        AppTextField(value = state.primaryModel, onValueChange = viewModel::onPrimaryModelChange, label = "기본 모델")
        Spacer(Modifier.height(AppSpacing.lg))
        AppTextField(value = state.secondaryModel, onValueChange = viewModel::onSecondaryModelChange, label = "대체 모델")
        Spacer(Modifier.height(AppSpacing.lg))
        AppTextField(
            value = state.temperature, onValueChange = viewModel::onTemperatureChange, label = "온도 (0.0~1.0)",
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
        if (state.saved) {
            Spacer(Modifier.height(AppSpacing.lg))
            InlineBanner("저장했어요", BannerKind.Success)
        }
    }
}
