package com.csh.blogwriter.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.repo.MemoryItem
import com.csh.blogwriter.data.repo.MemoryKind
import com.csh.blogwriter.data.repo.MemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoryViewModel @Inject constructor(private val memory: MemoryRepository) : ViewModel() {
    val items: StateFlow<List<MemoryItem>> = memory.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(text: String) = viewModelScope.launch { if (text.isNotBlank()) memory.add(MemoryKind.PREFERENCE, text, "manual") }
    fun edit(id: Long, text: String) = viewModelScope.launch { memory.update(id, text) }
    fun toggle(id: Long, enabled: Boolean) = viewModelScope.launch { memory.setEnabled(id, enabled) }
    fun delete(id: Long) = viewModelScope.launch { memory.delete(id) }
}
