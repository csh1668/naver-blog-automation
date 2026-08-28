package com.csh.blogwriter.ui.admin

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

/**
 * 관리자 PIN 게이트. 미설정이면 두 번 입력받아 새로 정하고, 설정돼 있으면 확인만 한다.
 * [forceSet] 이 true 면(PIN 변경) 기존 PIN 유무와 상관없이 새로 두 번 입력받는 흐름으로 시작한다 —
 * 확인 전까지는 기존 PIN 이 그대로 남아 있고, [PinManager.set] 이 확인 시점에만 덮어쓴다.
 */
@Composable
fun PinGateScreen(onPassed: () -> Unit, onBack: () -> Unit, forceSet: Boolean = false, viewModel: PinViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pin by remember(state.mode) { mutableStateOf("") }

    LaunchedEffect(forceSet) { if (forceSet) viewModel.forceSetFlow() }

    ScreenScaffold(
        topBar = { AppTopBar(onBack = onBack, title = "관리자 확인") },
        bottom = {
            BottomCta(
                text = "확인",
                onClick = { viewModel.submit(pin, onPassed) },
                enabled = pin.isNotEmpty() && !state.busy,
                loading = state.busy,
            )
        },
    ) {
        Spacer(Modifier.height(AppSpacing.section))
        Text(
            text = when (state.mode) {
                PinMode.SET_FIRST -> "관리자 비밀번호를 정해 주세요"
                PinMode.SET_CONFIRM -> "한 번 더 입력해 주세요"
                PinMode.VERIFY -> "관리자 비밀번호를 입력해 주세요"
                PinMode.LOADING -> ""
            },
            style = AppTheme.typography.title1, color = AppTheme.colors.textPrimary,
        )
        Spacer(Modifier.height(AppSpacing.section))
        AppTextField(
            value = pin,
            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
            label = "숫자 4~6자리",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
        )
        if (state.error != null) {
            Spacer(Modifier.height(AppSpacing.lg))
            InlineBanner(state.error!!, BannerKind.Danger)
        }
    }
}
