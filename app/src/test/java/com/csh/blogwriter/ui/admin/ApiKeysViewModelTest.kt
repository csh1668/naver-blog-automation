package com.csh.blogwriter.ui.admin

import com.csh.blogwriter.llm.ApiKey
import com.csh.blogwriter.llm.ApiKeyStore
import com.csh.blogwriter.llm.GeminiClient
import com.csh.blogwriter.llm.GeminiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApiKeysViewModelTest {
    private val server = MockWebServer()
    private val stored = MutableStateFlow<List<ApiKey>>(emptyList())
    private val keyStore = object : ApiKeyStore {
        override val keys: Flow<List<ApiKey>> = stored
        override val hasUsableKey: Flow<Boolean> = stored.map { l -> l.any { it.usable } }
        override suspend fun add(secrets: List<String>): List<ApiKey> { val added = secrets.map { ApiKey(it, it, 0) }; stored.value = stored.value + added; return added }
        override suspend fun remove(id: String) { stored.value = stored.value.filterNot { it.id == id } }
        override suspend fun markOk(id: String) { stored.value = stored.value.map { if (it.id == id) it.copy(lastOkAt = 1) else it } }
        override suspend fun markLimited(id: String) {}
        override suspend fun markInvalid(id: String) {}
        override suspend fun resetAll() {}
    }
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()); server.start() }
    @After fun tearDown() { Dispatchers.resetMain(); server.shutdown() }

    @Test
    fun parsesCandidatesAndRegistersOnlyValidOnes() = runTest {
        val vm = ApiKeysViewModel(keyStore, GeminiClient(OkHttpClient(), server.url("/").toString().trimEnd('/')))
        vm.onInput("AQ.Ab8RN6validvalidvalidvalid\nAQ.Ab8RN6invalidinvalidinvalidx\nshort")
        assertEquals(2, vm.uiState.value.candidates.size)
        server.enqueue(MockResponse().setBody("{\"models\":[]}"))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"status":"PERMISSION_DENIED","message":"no"}}"""))
        vm.register().join()
        assertEquals(listOf(Candidate.Status.VALID, Candidate.Status.INVALID), vm.uiState.value.candidates.map { it.status })
        assertEquals(1, stored.value.size); assertEquals(true, stored.value[0].usable)
    }
}
