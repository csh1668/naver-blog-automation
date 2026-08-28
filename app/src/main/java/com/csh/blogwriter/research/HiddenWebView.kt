package com.csh.blogwriter.research

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 화면에 붙지 않는 WebView 하나를 재사용한다. NaverWebViewConfig 는 로그인 세션용 설정이라 여기서는 쓰지 않는다
 * (쿠키는 프로세스 전역이라 완전히 격리되진 않는다 — url 화이트리스트(DefaultToolExecutor.allowedUrls)와
 * 서드파티 쿠키 차단으로 완화한다). 모든 호출은 Mutex + 메인 스레드로 직렬화된다.
 */
class HiddenWebView(private val context: Context) {
    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
    }

    private val lock = Mutex()
    private var web: WebView? = null
    private var onLoaded: ((String) -> Unit)? = null
    private var generation = 0

    @SuppressLint("SetJavaScriptEnabled")
    private fun view(): WebView = web ?: WebView(context).also { w ->
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.userAgentString = UA
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) { onLoaded?.invoke(url) }
        }
        web = w
    }

    /** url 을 로드하고 onPageFinished 후 script 를 실행해 문자열 결과를 돌려준다. timeout 시 로딩을 멈추고 null. */
    suspend fun loadAndExtract(url: String, script: String, timeoutMs: Long): String? = lock.withLock {
        withContext(Dispatchers.Main) {
            val w = view()
            val targetHost = runCatching { Uri.parse(url).host }.getOrNull()
            val myGen = ++generation
            val result = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Unit> { cont ->
                    onLoaded = { finishedUrl ->
                        // 리다이렉트 중간 페이지·이전 호출의 늦은 콜백은 무시하고 목표 호스트에 도달했을 때만 진행한다.
                        val finishedHost = runCatching { Uri.parse(finishedUrl).host }.getOrNull()
                        if (myGen == generation && cont.isActive && (targetHost == null || finishedHost == targetHost)) cont.resume(Unit)
                    }
                    w.loadUrl(url)
                }
                delay(600) // 검색 결과가 JS 로 늦게 렌더되는 경우
                suspendCancellableCoroutine<String?> { cont -> w.evaluateJavascript(script) { r -> if (cont.isActive) cont.resume(r) } }
            }
            onLoaded = null
            if (result == null) w.stopLoading()
            result
        }
    }

    fun destroy() { web?.destroy(); web = null }
}
