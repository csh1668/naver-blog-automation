package com.csh.blogwriter.publish

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.csh.blogwriter.domain.model.PreparedImage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 스마트에디터 페이지를 띄우고 editor_bridge.js 의 함수를 호출하는 래퍼.
 * 모든 결과는 [Listener] 로 비동기 회신된다. 화면(Compose AndroidView)이 [view] 를 붙인다.
 */
class NaverEditorWebView(context: Context, private val listener: Listener) {
    interface Listener : EditorBridge.Listener {
        fun onUrlChanged(url: String)
        fun onPageFinished(url: String)
    }

    companion object { private const val TAG = "NaverEditorWebView" }

    private var interceptor = LocalImageInterceptor(emptyMap())
    private var bridgeScript: String? = null

    val view: WebView = WebView(context).also { web ->
        NaverWebViewConfig.apply(web)
        web.addJavascriptInterface(EditorBridge(listener), "AndroidBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { listener.onUrlChanged(url) }
            override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) { listener.onUrlChanged(url) }
            override fun onPageFinished(v: WebView, url: String) {
                CookieManager.getInstance().flush()
                listener.onPageFinished(url)
            }
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                interceptor.intercept(request.url.toString()) ?: super.shouldInterceptRequest(v, request)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d(TAG, "JS[${m.messageLevel()}] ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
    }

    fun loadEditor(blogId: String) = view.loadUrl(NaverWebViewConfig.writeUrl(blogId))

    fun setLocalImages(images: List<PreparedImage>) {
        interceptor = LocalImageInterceptor(images.associate { it.ref to it.file })
    }

    /** 페이지 로드 후 한 번 호출. window.__app 을 정의한다. 이미 있으면 다시 정의하지 않는다. */
    fun installBridgeScript() {
        val script = bridgeScript ?: view.context.assets.open("editor_bridge.js").bufferedReader().readText().also { bridgeScript = it }
        view.evaluateJavascript("if(!window.__app){$script}", null)
    }

    fun checkReady() = view.evaluateJavascript("window.__app && window.__app.checkReady();", null)
    fun dismissPopups() = view.evaluateJavascript("window.__app.dismissPopups();", null)

    fun uploadImages(refs: List<String>) {
        val arg = JSONArray(refs.map { JSONObject().put("ref", it).put("url", LocalImageInterceptor.urlFor(it)) })
        view.evaluateJavascript("window.__app.uploadImages($arg);", null)
    }

    fun setDocument(documentJson: String) {
        // JSON 문자열을 JS 문자열 리터럴로 안전하게 넘긴 뒤 JS 쪽에서 parse 한다.
        val literal = JSONObject.quote(documentJson)
        view.evaluateJavascript("window.__app.setDocument($literal);", null)
    }

    fun destroy() {
        view.stopLoading()
        view.removeJavascriptInterface("AndroidBridge")
        view.destroy()
    }
}
