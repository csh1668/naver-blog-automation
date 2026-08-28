package com.csh.blogwriter.ui.admin

import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.llm.ApiKey
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.llm.GeminiClient
import com.csh.blogwriter.llm.ModelPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {
    private val server = MockWebServer()
    private lateinit var client: GeminiClient
    private val stored = MutableStateFlow<List<ApiKey>>(emptyList())
    private val keyStore = object : ApiKeyStore {
        override val keys: Flow<List<ApiKey>> = stored
        override val hasUsableKey: Flow<Boolean> = stored.map { l -> l.any { it.usable } }
        override suspend fun add(secrets: List<String>): List<ApiKey> = emptyList()
        override suspend fun remove(id: String) {}
        override suspend fun markOk(id: String) {}
        override suspend fun markLimited(id: String) {}
        override suspend fun markInvalid(id: String) {}
        override suspend fun resetAll() {}
    }
    private val settings = object : SettingsStore {
        override val blogId: Flow<String?> = flowOf(null)
        override suspend fun setBlogId(id: String?) {}
        override val modelPolicy: Flow<ModelPolicy> = flowOf(ModelPolicy(models = listOf("gemini-3.6-flash", "gemini-3.5-flash-lite")))
        var savedPolicy: ModelPolicy? = null
        override suspend fun setModelPolicy(policy: ModelPolicy) { savedPolicy = policy }
    }

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()); server.start(); client = GeminiClient(OkHttpClient(), server.url("/").toString().trimEnd('/')) }
    @After fun tearDown() { Dispatchers.resetMain(); server.shutdown() }

    @Test
    fun loadsAvailableModelsWhenUsableKeyExists() = runTest {
        // stored 는 비어 있는 채로 생성 — init 이 자동으로 트리거하는 refreshModels() 는 "키 없음" 분기라 실제 네트워크를 타지 않는다.
        // 이후 키를 채우고 refreshModels() 를 명시적으로 호출해 join() 으로 완료를 기다린다(실제 IO 디스패처를 타므로 advanceUntilIdle 로는 못 기다린다).
        val vm = ModelsViewModel(settings, keyStore, client)
        advanceUntilIdle()
        stored.value = listOf(ApiKey("id1", "SECRET", 0, lastOkAt = 1))
        server.enqueue(MockResponse().setBody(
            """{"models":[
                {"name":"models/gemini-3.7-flash","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-1.5-pro","supportedGenerationMethods":["generateContent"]}
            ]}"""
        ))
        vm.refreshModels().join()
        assertEquals(listOf("gemini-3.7-flash", "gemini-1.5-pro"), vm.uiState.value.availableModels)
        assertNull(vm.uiState.value.modelsError)
        assertEquals(false, vm.uiState.value.modelsLoading)
    }

    @Test
    fun noUsableKeyShowsErrorAndEmptyList() = runTest {
        stored.value = emptyList()
        val vm = ModelsViewModel(settings, keyStore, client)
        advanceUntilIdle()
        assertEquals("등록된 API 키가 없어 목록을 불러올 수 없어요. 직접 입력해 주세요.", vm.uiState.value.modelsError)
        assertTrue(vm.uiState.value.availableModels.isEmpty())
    }

    @Test
    fun saveRejectsTemperatureOutOfRange() = runTest {
        val vm = ModelsViewModel(settings, keyStore, client)
        advanceUntilIdle()
        vm.onPrimaryModelChange("gemini-3.6-flash")
        vm.onTemperatureChange("2.5")
        vm.onMinLengthChange("1200"); vm.onMaxLengthChange("1800")
        vm.save().join()
        assertEquals("온도는 0.0~2.0 사이여야 해요", vm.uiState.value.error)
        assertNull(settings.savedPolicy)
    }

    @Test
    fun saveRejectsMinGreaterThanMax() = runTest {
        val vm = ModelsViewModel(settings, keyStore, client)
        advanceUntilIdle()
        vm.onPrimaryModelChange("gemini-3.6-flash")
        vm.onTemperatureChange("0.7")
        vm.onMinLengthChange("1800"); vm.onMaxLengthChange("1200")
        vm.save().join()
        assertEquals("글 길이 최소가 최대보다 클 수 없어요", vm.uiState.value.error)
        assertNull(settings.savedPolicy)
    }

    @Test
    fun saveRejectsBlankModels() = runTest {
        val vm = ModelsViewModel(settings, keyStore, client)
        advanceUntilIdle()
        vm.onPrimaryModelChange(""); vm.onSecondaryModelChange("")
        vm.onTemperatureChange("0.7")
        vm.onMinLengthChange("1200"); vm.onMaxLengthChange("1800")
        vm.save().join()
        assertEquals("모델을 하나 이상 입력해 주세요", vm.uiState.value.error)
        assertNull(settings.savedPolicy)
    }

    @Test
    fun saveSucceedsWithValidInput() = runTest {
        val vm = ModelsViewModel(settings, keyStore, client)
        advanceUntilIdle()
        vm.onPrimaryModelChange("gemini-3.6-flash")
        vm.onSecondaryModelChange("")
        vm.onTemperatureChange("0.7")
        vm.onMinLengthChange("1200"); vm.onMaxLengthChange("1800")
        vm.save().join()
        assertNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.saved)
        assertEquals(listOf("gemini-3.6-flash"), settings.savedPolicy?.models)
    }
}
