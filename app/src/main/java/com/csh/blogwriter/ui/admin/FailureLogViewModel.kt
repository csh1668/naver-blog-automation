package com.csh.blogwriter.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.repo.FailureLogItem
import com.csh.blogwriter.data.repo.FailureLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class FailureLogViewModel @Inject constructor(repo: FailureLogRepository) : ViewModel() {
    val items: StateFlow<List<FailureLogItem>> = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
