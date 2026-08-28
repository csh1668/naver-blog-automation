package com.csh.blogwriter.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.session.NaverSession
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.ConfirmSheet
import com.csh.blogwriter.ui.components.ListRow
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(val apiKeyCount: Int = 0, val researchEnabled: Boolean = true)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    keyStore: ApiKeyStore,
    private val naverSession: NaverSession,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> = combine(keyStore.keys, settings.researchEnabled) { keys, research ->
        SettingsUiState(apiKeyCount = keys.size, researchEnabled = research)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setResearchEnabled(enabled: Boolean) = viewModelScope.launch { settings.setResearchEnabled(enabled) }
    fun logout(onDone: () -> Unit) = viewModelScope.launch { naverSession.logout(); onDone() }
}

/** 관리자 설정 목록. 기술 용어 허용. */
@Composable
fun SettingsScreen(
    onApiKeys: () -> Unit,
    onModels: () -> Unit,
    onPrompts: () -> Unit,
    onMemory: () -> Unit,
    onFailureLogs: () -> Unit,
    onChangePin: () -> Unit,
    onLoggedOut: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var logoutConfirm by remember { mutableStateOf(false) }

    ScreenScaffold(topBar = { AppTopBar(onBack = onBack, title = "설정 (관리자)") }) {
        Spacer(Modifier.height(AppSpacing.lg))
        ListRow(title = "API 키", subtitle = "${state.apiKeyCount}개 등록됨", onClick = onApiKeys)
        Spacer(Modifier.height(AppSpacing.md))
        ListRow(title = "모델과 글 길이", onClick = onModels)
        Spacer(Modifier.height(AppSpacing.md))
        ListRow(title = "프롬프트", onClick = onPrompts)
        Spacer(Modifier.height(AppSpacing.md))
        ListRow(title = "기억한 것들", onClick = onMemory)
        Spacer(Modifier.height(AppSpacing.md))
        ResearchToggleRow(checked = state.researchEnabled, onCheckedChange = viewModel::setResearchEnabled)
        Spacer(Modifier.height(AppSpacing.md))
        ListRow(title = "실패 로그", onClick = onFailureLogs)
        Spacer(Modifier.height(AppSpacing.md))
        ListRow(title = "PIN 변경", onClick = onChangePin)
        Spacer(Modifier.height(AppSpacing.md))
        ListRow(title = "네이버 로그아웃", onClick = { logoutConfirm = true }, trailingChevron = false)
    }

    ConfirmSheet(
        visible = logoutConfirm,
        title = "네이버에서 로그아웃할까요?",
        message = "다시 로그인해야 글을 올릴 수 있어요",
        confirmText = "로그아웃",
        onConfirm = { logoutConfirm = false; viewModel.logout(onLoggedOut) },
        dismissText = "취소",
        onDismiss = { logoutConfirm = false },
        danger = true,
    )
}

@Composable
private fun ResearchToggleRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = AppSpacing.touchTarget)
            .clip(RoundedCornerShape(AppSpacing.radiusCard))
            .background(AppTheme.colors.surfaceWeak)
            .padding(AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("자료 검색 도구", style = AppTheme.typography.title3, color = AppTheme.colors.textPrimary)
        }
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AppTheme.colors.fillBrand),
        )
    }
}
