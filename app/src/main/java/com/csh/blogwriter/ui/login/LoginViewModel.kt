package com.csh.blogwriter.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.publish.NaverWebViewConfig
import com.csh.blogwriter.publish.PublishUrlParser
import com.csh.blogwriter.session.BlogIdResolver
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val _phase = MutableStateFlow<LoginPhase>(LoginPhase.LoggingIn)
    val phase: StateFlow<LoginPhase> = _phase
    private val _urlToLoad = MutableStateFlow(NaverWebViewConfig.LOGIN_URL)
    val urlToLoad: StateFlow<String> = _urlToLoad
    private var resolving = false

    fun onUrlChanged(url: String) {
        when (val p = _phase.value) {
            LoginPhase.LoggingIn -> if (!PublishUrlParser.isLoginPage(url) && url.startsWith("https://")) {
                _phase.value = LoginPhase.ResolvingBlogId
                _urlToLoad.value = NaverWebViewConfig.MY_BLOG_URL
            }
            LoginPhase.ResolvingBlogId -> {
                if (PublishUrlParser.isLoginPage(url)) { _phase.value = LoginPhase.LoggingIn; _urlToLoad.value = NaverWebViewConfig.LOGIN_URL; return }
                if (resolving) return
                val id = BlogIdResolver.fromUrl(url) ?: return
                resolving = true
                viewModelScope.launch {
                    settings.setBlogId(id)
                    _phase.value = LoginPhase.Done(id)
                    resolving = false
                }
            }
            is LoginPhase.Done -> Unit
        }
    }
}
