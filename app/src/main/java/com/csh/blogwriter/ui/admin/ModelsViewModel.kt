package com.csh.blogwriter.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.llm.ModelPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val error: String? = null,
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

    fun onPrimaryModelChange(value: String) { _uiState.update { it.copy(primaryModel = value, saved = false, error = null) } }
    fun onSecondaryModelChange(value: String) { _uiState.update { it.copy(secondaryModel = value, saved = false, error = null) } }
    fun onTemperatureChange(value: String) { _uiState.update { it.copy(temperature = value, saved = false, error = null) } }
    fun onMinLengthChange(value: String) { _uiState.update { it.copy(minLength = value, saved = false, error = null) } }
    fun onMaxLengthChange(value: String) { _uiState.update { it.copy(maxLength = value, saved = false, error = null) } }

    fun save() = viewModelScope.launch {
        val s = _uiState.value
        val primary = s.primaryModel.trim()
        val secondary = s.secondaryModel.trim()
        val temperature = s.temperature.toDoubleOrNull()
        val min = s.minLength.toIntOrNull()
        val max = s.maxLength.toIntOrNull()

        fun fail(message: String) { _uiState.update { it.copy(error = message, saved = false) } }
        if (temperature == null) return@launch fail("온도는 숫자로 입력해 주세요")
        if (temperature < 0.0 || temperature > 2.0) return@launch fail("온도는 0.0~2.0 사이여야 해요")
        if (min == null || max == null) return@launch fail("글 길이는 숫자로 입력해 주세요")
        if (min > max) return@launch fail("글 길이 최소가 최대보다 클 수 없어요")
        if (min < 100) return@launch fail("글 길이 최소는 100자 이상이어야 해요")
        if (primary.isEmpty() && secondary.isEmpty()) return@launch fail("모델을 하나 이상 입력해 주세요")

        val models = listOfNotNull(primary.takeIf { it.isNotEmpty() }, secondary.takeIf { it.isNotEmpty() })
        settings.setModelPolicy(ModelPolicy(models = models, temperature = temperature, targetLength = min..max))
        _uiState.update { it.copy(saved = true, error = null) }
    }
}
