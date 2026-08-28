package com.csh.blogwriter.ui.login

import com.csh.blogwriter.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
    }
}
