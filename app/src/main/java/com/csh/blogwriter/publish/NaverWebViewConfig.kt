package com.csh.blogwriter.publish

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import com.csh.blogwriter.BuildConfig

object NaverWebViewConfig {
    /** 모바일 UA 면 m.blog.naver.com → 앱 설치 안내로 빠지므로 데스크톱 UA 를 강제한다 (spike/findings.md §1). */
    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
    const val LOGIN_URL = "https://nid.naver.com/nidlogin.login"
    const val MY_BLOG_URL = "https://blog.naver.com/MyBlog.naver"
    fun writeUrl(blogId: String) = "https://blog.naver.com/$blogId?Redirect=Write"

    @SuppressLint("SetJavaScriptEnabled")
    fun apply(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }
}
