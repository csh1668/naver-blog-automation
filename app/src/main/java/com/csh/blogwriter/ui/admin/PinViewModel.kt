package com.csh.blogwriter.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.admin.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PinMode { LOADING, SET_FIRST, SET_CONFIRM, VERIFY }

data class PinUiState(val mode: PinMode = PinMode.LOADING, val error: String? = null, val busy: Boolean = false)

@HiltViewModel
class PinViewModel @Inject constructor(private val pinManager: PinManager) : ViewModel() {
    private val _uiState = MutableStateFlow(PinUiState())
    val uiState: StateFlow<PinUiState> = _uiState.asStateFlow()

    private var firstPin: String? = null
    private var forceSet = false

    init {
        viewModelScope.launch {
            val isSet = pinManager.isSet()
            _uiState.value = PinUiState(mode = if (forceSet || !isSet) PinMode.SET_FIRST else PinMode.VERIFY)
        }
    }

    /** PIN 변경 화면에서 호출한다. 기존 PIN 이 있어도 새로 두 번 입력받는 흐름으로 강제 진입한다. */
    fun forceSetFlow() {
        forceSet = true
        _uiState.value = PinUiState(mode = PinMode.SET_FIRST)
    }

    fun submit(pin: String, onPassed: () -> Unit) {
        if (!PinManager.isValidPin(pin)) {
            _uiState.update { it.copy(error = "숫자 4~6자리를 입력해 주세요") }
            return
        }
        when (_uiState.value.mode) {
            PinMode.SET_FIRST -> {
                firstPin = pin
                _uiState.value = PinUiState(mode = PinMode.SET_CONFIRM)
            }
            PinMode.SET_CONFIRM -> {
                if (pin == firstPin) {
                    viewModelScope.launch {
                        _uiState.update { it.copy(busy = true) }
                        pinManager.set(pin)
                        onPassed()
                    }
                } else {
                    firstPin = null
                    _uiState.value = PinUiState(mode = PinMode.SET_FIRST, error = "번호가 달라요. 처음부터 다시 입력해 주세요")
                }
            }
            PinMode.VERIFY -> viewModelScope.launch {
                _uiState.update { it.copy(busy = true) }
                when (val result = pinManager.verify(pin)) {
                    PinManager.VerifyResult.OK -> onPassed()
                    is PinManager.VerifyResult.WRONG ->
                        _uiState.value = PinUiState(mode = PinMode.VERIFY, error = "비밀번호가 달라요. ${result.remaining}번 더 틀리면 30초 동안 잠겨요")
                    is PinManager.VerifyResult.LOCKED ->
                        _uiState.value = PinUiState(mode = PinMode.VERIFY, error = "너무 많이 틀렸어요. 30초 후 다시 시도해 주세요")
                }
            }
            PinMode.LOADING -> Unit
        }
    }
}
