package com.csh.blogwriter.research

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.csh.blogwriter.publish.NaverWebViewConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 화면에 붙지 않는 WebView 하나를 재사용한다. 모든 호출은 메인 스레드에서 직렬로. */
class HiddenWebView(private val context: Context) {
    private var web: WebView? = null
    private var onLoaded: ((String) -> Unit)? = null

    private fun view(): WebView = web ?: WebView(context).also { w ->
        NaverWebViewConfig.apply(w)
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) { onLoaded?.invoke(url) }
        }
        web = w
    }

    /** url 을 로드하고 onPageFinished 후 script 를 실행해 문자열 결과를 돌려준다. timeout 시 null. */
    suspend fun loadAndExtract(url: String, script: String, timeoutMs: Long): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(timeoutMs) {
            val w = view()
            suspendCancellableCoroutine<Unit> { cont -> onLoaded = { if (cont.isActive) cont.resume(Unit) }; w.loadUrl(url) }
            kotlinx.coroutines.delay(600) // 검색 결과가 JS 로 늦게 렌더되는 경우
            suspendCancellableCoroutine<String?> { cont -> w.evaluateJavascript(script) { r -> if (cont.isActive) cont.resume(r) } }
        }
    }

    fun destroy() { web?.destroy(); web = null }
}
