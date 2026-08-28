package com.csh.blogwriter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.PendingJobRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(val hasBlogId: Boolean, val pendingJobId: String?, val pendingTitle: String?)

@HiltViewModel
class HomeViewModel @Inject constructor(settings: SettingsStore, pendingJobs: PendingJobRepository) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = combine(settings.blogId, pendingJobs.observeLatest()) { blogId, pending ->
        HomeUiState(hasBlogId = blogId != null, pendingJobId = pending?.id, pendingTitle = pending?.content?.title)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(false, null, null))
}
