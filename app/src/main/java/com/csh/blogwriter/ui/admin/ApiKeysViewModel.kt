package com.csh.blogwriter.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.llm.ApiKey
import com.csh.blogwriter.llm.ApiKeyParser
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.llm.GeminiClient
import com.csh.blogwriter.llm.GeminiException
import com.csh.blogwriter.llm.KeyProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class Candidate(val secret: String, val status: Status) {
    enum class Status { PENDING, VALID, INVALID, LIMITED, ERROR }
    val masked: String get() = "…" + secret.takeLast(4)

    /** 실수로 로그에 찍혀도 secret 이 노출되지 않도록. */
    override fun toString() = "Candidate(masked=$masked, status=$status)"
}

data class ApiKeysUiState(val keys: List<ApiKey> = emptyList(), val candidates: List<Candidate> = emptyList(), val busy: Boolean = false)

@HiltViewModel
class ApiKeysViewModel @Inject constructor(private val keyStore: ApiKeyStore, private val client: GeminiClient) : ViewModel() {
    private val _uiState = MutableStateFlow(ApiKeysUiState())
    val uiState: StateFlow<ApiKeysUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { refreshKeys() }
    }

    private suspend fun refreshKeys() {
        val keys = keyStore.keysOnce()
        _uiState.update { it.copy(keys = keys) }
    }

    fun onInput(text: String) {
        val existing = _uiState.value.keys.map { it.secret }.toSet()
        val parsed = ApiKeyParser.parse(text, existing).map { Candidate(it, Candidate.Status.PENDING) }
        _uiState.update { it.copy(candidates = parsed) }
    }

    fun register() = viewModelScope.launch {
        _uiState.update { it.copy(busy = true) }
        val results = _uiState.value.candidates.map { c ->
            val status = try {
                when (client.listModels(c.secret)) {
                    KeyProbe.VALID -> Candidate.Status.VALID
                    KeyProbe.LIMITED -> Candidate.Status.LIMITED
                }
            } catch (e: GeminiException) {
                if (e.kind == GeminiException.Kind.INVALID_KEY) Candidate.Status.INVALID else Candidate.Status.ERROR
            }
            c.copy(status = status)
        }
        val valid = results.filter { it.status == Candidate.Status.VALID || it.status == Candidate.Status.LIMITED }
        val added = keyStore.add(valid.map { it.secret })
        added.forEach { addedKey ->
            val status = valid.firstOrNull { it.secret == addedKey.secret }?.status
            // 429 도 "키는 실존한다"는 증거다 — markOk 를 빼면 lastOkAt 이 비어 usable 이 아니게 되고
            // 로테이션에서도, 열쇠 있음 배너에서도 빠져 버린다.
            keyStore.markOk(addedKey.id)
            if (status == Candidate.Status.LIMITED) keyStore.markLimited(addedKey.id)
        }
        _uiState.update { it.copy(candidates = results, busy = false) }
        refreshKeys()
    }

    fun remove(id: String) = viewModelScope.launch {
        keyStore.remove(id)
        refreshKeys()
    }
}
