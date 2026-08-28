package com.csh.blogwriter.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.publish.NaverWebViewConfig
import com.csh.blogwriter.publish.PublishUrlParser
import com.csh.blogwriter.session.BlogIdResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LoginPhase {
    data object LoggingIn : LoginPhase
    data object ResolvingBlogId : LoginPhase
    data class Done(val blogId: String) : LoginPhase
}

/**
 * 로그인 페이지에서 nid.naver.com 밖으로 나가면 로그인 성공 → MyBlog.naver 를 로드해 리다이렉트 URL 에서 blogId 를 얻는다.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(private val settings: SettingsStore) : ViewModel() {

    companion object {
        const val RESOLVE_TIMEOUT_MS = 20_000L
    }

    private val _phase = MutableStateFlow<LoginPhase>(LoginPhase.LoggingIn)
    val phase: StateFlow<LoginPhase> = _phase
    private val _urlToLoad = MutableStateFlow(NaverWebViewConfig.LOGIN_URL)
    val urlToLoad: StateFlow<String> = _urlToLoad
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private var resolving = false
    private var resolveTimeout: Job? = null

    fun onUrlChanged(url: String) {
        when (val p = _phase.value) {
            LoginPhase.LoggingIn -> if (!PublishUrlParser.isLoginPage(url) && url.startsWith("https://")) {
                _message.value = null
                _phase.value = LoginPhase.ResolvingBlogId
                _urlToLoad.value = NaverWebViewConfig.MY_BLOG_URL
                armResolveTimeout()
            }
            LoginPhase.ResolvingBlogId -> {
                if (PublishUrlParser.isLoginPage(url)) { backToLogin(null); return }
                if (resolving) return
                val id = BlogIdResolver.fromUrl(url) ?: return
                resolving = true
                viewModelScope.launch {
                    settings.setBlogId(id)
                    resolveTimeout?.cancel()
                    _phase.value = LoginPhase.Done(id)
                    resolving = false
                }
            }
            is LoginPhase.Done -> Unit
        }
    }

    /** blogId 를 못 읽고 오래 머무르면 로그인 화면으로 되돌린다 (계속 도는 진행 화면 방지). */
    private fun armResolveTimeout() {
        resolveTimeout?.cancel()
        resolveTimeout = viewModelScope.launch {
            delay(RESOLVE_TIMEOUT_MS)
            backToLogin("블로그 정보를 확인하지 못했어요. 다시 로그인해 주세요.")
        }
    }

    private fun backToLogin(message: String?) {
        resolveTimeout?.cancel()
        resolving = false
        _message.value = message
        _phase.value = LoginPhase.LoggingIn
        _urlToLoad.value = NaverWebViewConfig.LOGIN_URL
    }
}
