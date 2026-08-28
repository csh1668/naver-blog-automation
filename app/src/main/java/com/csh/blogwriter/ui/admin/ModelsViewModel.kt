package com.csh.blogwriter.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.llm.ModelPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelsUiState(
    val primaryModel: String = "",
    val secondaryModel: String = "",
    val temperature: String = "",
    val minLength: String = "",
    val maxLength: String = "",
    val loaded: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(private val settings: SettingsStore) : ViewModel() {
    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val policy = settings.modelPolicyOnce()
            _uiState.value = ModelsUiState(
                primaryModel = policy.models.getOrElse(0) { "" },
                secondaryModel = policy.models.getOrElse(1) { "" },
                temperature = policy.temperature.toString(),
                minLength = policy.targetLength.first.toString(),
                maxLength = policy.targetLength.last.toString(),
                loaded = true,
            )
        }
    }

    fun onPrimaryModelChange(value: String) { _uiState.value = _uiState.value.copy(primaryModel = value, saved = false) }
    fun onSecondaryModelChange(value: String) { _uiState.value = _uiState.value.copy(secondaryModel = value, saved = false) }
    fun onTemperatureChange(value: String) { _uiState.value = _uiState.value.copy(temperature = value, saved = false) }
    fun onMinLengthChange(value: String) { _uiState.value = _uiState.value.copy(minLength = value, saved = false) }
    fun onMaxLengthChange(value: String) { _uiState.value = _uiState.value.copy(maxLength = value, saved = false) }

    fun save() = viewModelScope.launch {
        val s = _uiState.value
        val current = settings.modelPolicyOnce()
        val models = listOfNotNull(
            s.primaryModel.trim().takeIf { it.isNotEmpty() },
            s.secondaryModel.trim().takeIf { it.isNotEmpty() },
        ).ifEmpty { current.models }
        val temperature = s.temperature.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: current.temperature
        val min = s.minLength.toIntOrNull()
        val max = s.maxLength.toIntOrNull()
        val targetLength = if (min != null && max != null && min <= max) min..max else current.targetLength
        settings.setModelPolicy(ModelPolicy(models = models, temperature = temperature, targetLength = targetLength))
        _uiState.value = _uiState.value.copy(saved = true)
    }
}
