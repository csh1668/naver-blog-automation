package com.csh.blogwriter.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.chat.PromptSection
import com.csh.blogwriter.chat.PromptStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PromptSectionState(val section: PromptSection, val text: String, val overridden: Boolean)

@HiltViewModel
class PromptsViewModel @Inject constructor(private val store: PromptStore) : ViewModel() {
    private val _sections = MutableStateFlow<List<PromptSectionState>>(emptyList())
    val sections: StateFlow<List<PromptSectionState>> = _sections.asStateFlow()

    init {
        viewModelScope.launch {
            val flows = PromptSection.entries.map { section ->
                store.observe(section).map { text -> PromptSectionState(section, text, store.isOverridden(section)) }
            }
            combine(flows) { it.toList() }.collect { _sections.value = it }
        }
    }

    fun save(section: PromptSection, text: String) = viewModelScope.launch { store.override(section, text) }
    fun reset(section: PromptSection) = viewModelScope.launch { store.override(section, null) }
}
