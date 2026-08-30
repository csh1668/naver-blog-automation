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
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import com.csh.blogwriter.BuildConfig
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.session.NaverSession
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.BannerKind
import com.csh.blogwriter.ui.components.ConfirmSheet
import com.csh.blogwriter.ui.components.InlineBanner
import com.csh.blogwriter.ui.components.ListRow
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme
import com.csh.blogwriter.update.UpdateChecker
import com.csh.blogwriter.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 설정 화면의 "업데이트 확인" 결과. */
sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Available(val info: UpdateInfo) : UpdateCheckState
    data class UpToDate(val version: String) : UpdateCheckState
    data object Failed : UpdateCheckState
}

data class SettingsUiState(
    val apiKeyCount: Int = 0,
    val researchEnabled: Boolean = true,
    val loggedIn: Boolean = false,
    val updateCheck: UpdateCheckState = UpdateCheckState.Idle,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    keyStore: ApiKeyStore,
    private val naverSession: NaverSession,
    private val updateChecker: UpdateChecker,
) : ViewModel() {
    private val _updateCheck = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)

    val uiState: StateFlow<SettingsUiState> = combine(keyStore.keys, settings.researchEnabled, settings.blogId, _updateCheck) { keys, research, blogId, updateCheck ->
        SettingsUiState(apiKeyCount = keys.size, researchEnabled = research, loggedIn = blogId != null, updateCheck = updateCheck)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setResearchEnabled(enabled: Boolean) = viewModelScope.launch { settings.setResearchEnabled(enabled) }
    fun logout(onDone: () -> Unit) = viewModelScope.launch { naverSession.logout(); onDone() }

    fun checkForUpdate() = viewModelScope.launch {
        _updateCheck.value = UpdateCheckState.Checking
        val info = try { updateChecker.checkForUpdate() } catch (e: CancellationException) { throw e } catch (e: Exception) { _updateCheck.value = UpdateCheckState.Failed; return@launch }
        if (info == null) { _updateCheck.value = UpdateCheckState.UpToDate(BuildConfig.VERSION_NAME); return@launch }
        // 수동으로 찾은 새 버전은 채팅 배너로도 보이게 — 닫아 둔 태그를 풀고 10분 간격도 무시한다.
        settings.setDismissedUpdateTag(null); settings.setLastUpdateCheckAt(0L)
        _updateCheck.value = UpdateCheckState.Available(info)
    }
}

/** 관리자 설정 목록. 기술 용어 허용. */
@Composable
fun SettingsScreen(
    onApiKeys: () -> Unit,
    onModels: () -> Unit,
    onPrompts: () -> Unit,
    onMemory: () -> Unit,
    onFailureLogs: () -> Unit,
    onLogin: () -> Unit,
    onLoggedOut: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var logoutConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
        ListRow(
            title = "업데이트 확인",
            subtitle = "지금 버전 ${BuildConfig.VERSION_NAME}",
            onClick = viewModel::checkForUpdate,
            trailingChevron = false,
        )
        when (val updateCheck = state.updateCheck) {
            UpdateCheckState.Checking -> {
                Spacer(Modifier.height(AppSpacing.sm))
                InlineBanner("새 버전이 있는지 확인하고 있어요", BannerKind.Info)
            }
            is UpdateCheckState.Available -> {
                Spacer(Modifier.height(AppSpacing.sm))
                InlineBanner("새 버전(${updateCheck.info.tag})이 나왔어요 — 받으러 가기", BannerKind.Success) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, updateCheck.info.htmlUrl.toUri()))
                }
            }
            is UpdateCheckState.UpToDate -> {
                Spacer(Modifier.height(AppSpacing.sm))
                InlineBanner("최신 버전이에요 (${updateCheck.version})", BannerKind.Info)
            }
            UpdateCheckState.Failed -> {
                Spacer(Modifier.height(AppSpacing.sm))
                InlineBanner("확인하지 못했어요. 인터넷 연결을 확인해 주세요.", BannerKind.Warning)
            }
            UpdateCheckState.Idle -> {}
        }
        Spacer(Modifier.height(AppSpacing.md))
        // 로그인 전에는 여기서 바로 로그인할 수 있게 한다 — 첫 화면이 채팅이라 따로 로그인 화면을 거치지 않으므로.
        if (state.loggedIn) ListRow(title = "네이버 로그아웃", onClick = { logoutConfirm = true }, trailingChevron = false)
        else ListRow(title = "네이버 로그인", subtitle = "글을 올리려면 로그인이 필요해요", onClick = onLogin)
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
