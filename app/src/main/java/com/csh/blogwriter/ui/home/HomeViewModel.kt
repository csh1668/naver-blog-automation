package com.csh.blogwriter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.update.UpdateChecker
import com.csh.blogwriter.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(val hasBlogId: Boolean, val pendingJobId: String?, val pendingTitle: String?)

private const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settings: SettingsStore,
    pendingJobs: PendingJobRepository,
    private val updateChecker: UpdateChecker,
) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = combine(settings.blogId, pendingJobs.observeLatest()) { blogId, pending ->
        HomeUiState(hasBlogId = blogId != null, pendingJobId = pending?.id, pendingTitle = pending?.content?.title)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(false, null, null))

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (now - settings.lastUpdateCheckAtOnce() < UPDATE_CHECK_INTERVAL_MS) return@launch
            settings.setLastUpdateCheckAt(now)
            val info = updateChecker.checkForUpdate() ?: return@launch
            if (info.tag != settings.dismissedUpdateTagOnce()) _updateInfo.value = info
        }
    }

    fun dismissUpdate() {
        val tag = _updateInfo.value?.tag ?: return
        _updateInfo.value = null
        viewModelScope.launch { settings.setDismissedUpdateTag(tag) }
    }
}
