package com.csh.blogwriter.ui.login

import com.csh.blogwriter.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val stored = MutableStateFlow<String?>(null)
    private val settings = object : SettingsStore {
        override val blogId: Flow<String?> = stored
        override suspend fun setBlogId(id: String?) { stored.value = id }
    }
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun loginThenResolveBlogId() = runTest {
        val vm = LoginViewModel(settings)
        assertEquals(LoginPhase.LoggingIn, vm.phase.value)
        assertEquals("https://nid.naver.com/nidlogin.login", vm.urlToLoad.value)

        vm.onUrlChanged("https://nid.naver.com/nidlogin.login?mode=form")
        assertEquals(LoginPhase.LoggingIn, vm.phase.value)

        vm.onUrlChanged("https://www.naver.com/")
        assertEquals(LoginPhase.ResolvingBlogId, vm.phase.value)
        assertEquals("https://blog.naver.com/MyBlog.naver", vm.urlToLoad.value)

        vm.onUrlChanged("https://blog.naver.com/MyBlog.naver")
        vm.onUrlChanged("https://blog.naver.com/myblog")
        advanceUntilIdle()
        assertEquals(LoginPhase.Done("myblog"), vm.phase.value)
        assertEquals("myblog", stored.value)
    }

    @Test
    fun loginPageAfterResolvingMeansSessionDidNotStick() = runTest {
        val vm = LoginViewModel(settings)
        vm.onUrlChanged("https://www.naver.com/")
        vm.onUrlChanged("https://nid.naver.com/nidlogin.login?url=blog")
        assertEquals(LoginPhase.LoggingIn, vm.phase.value)
        assertNull(vm.message.value)
    }

    /** blogId 를 못 읽고 진행 화면에 갇히지 않도록, 오래 걸리면 로그인 화면으로 되돌린다. */
    @Test
    fun resolvingBlogIdTimesOutBackToLogin() = runTest {
        val vm = LoginViewModel(settings)
        vm.onUrlChanged("https://www.naver.com/")
        assertEquals(LoginPhase.ResolvingBlogId, vm.phase.value)

        advanceTimeBy(LoginViewModel.RESOLVE_TIMEOUT_MS + 1_000)
        advanceUntilIdle()
        assertEquals(LoginPhase.LoggingIn, vm.phase.value)
        assertEquals("https://nid.naver.com/nidlogin.login", vm.urlToLoad.value)
        assertEquals("블로그 정보를 확인하지 못했어요. 다시 로그인해 주세요.", vm.message.value)

        // 되돌린 뒤에도 다시 로그인해 blogId 를 읽을 수 있어야 한다.
        vm.onUrlChanged("https://www.naver.com/")
        vm.onUrlChanged("https://blog.naver.com/myblog")
        advanceUntilIdle()
        assertEquals(LoginPhase.Done("myblog"), vm.phase.value)
        assertNull(vm.message.value)
    }

    /** blogId 를 제때 읽으면 제한 시간이 지나도 그대로 Done 이어야 한다. */
    @Test
    fun resolveTimeoutIsCancelledWhenDone() = runTest {
        val vm = LoginViewModel(settings)
        vm.onUrlChanged("https://www.naver.com/")
        vm.onUrlChanged("https://blog.naver.com/myblog")
        advanceUntilIdle()
        assertEquals(LoginPhase.Done("myblog"), vm.phase.value)

        advanceTimeBy(LoginViewModel.RESOLVE_TIMEOUT_MS + 1_000)
        advanceUntilIdle()
        assertEquals(LoginPhase.Done("myblog"), vm.phase.value)
        assertNull(vm.message.value)
    }
}
