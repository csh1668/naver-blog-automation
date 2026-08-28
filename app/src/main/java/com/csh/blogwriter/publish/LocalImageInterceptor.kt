package com.csh.blogwriter.publish

import android.webkit.WebResourceResponse
import java.io.File

/** 페이지와 같은 origin(blog.naver.com)의 가짜 경로로 로컬 파일을 제공한다. JS 의 fetch 가 CORS 없이 File 을 만들 수 있다. */
class LocalImageInterceptor(private val images: Map<String, File>) {
    companion object {
        private const val PREFIX = "https://blog.naver.com/__app__/"
        fun urlFor(ref: String) = "$PREFIX$ref.jpg"
        fun refFromUrl(url: String): String? =
            if (url.startsWith(PREFIX) && url.endsWith(".jpg")) url.removePrefix(PREFIX).removeSuffix(".jpg") else null
    }

    fun intercept(url: String): WebResourceResponse? {
        val ref = refFromUrl(url) ?: return null
        val file = images[ref]?.takeIf { it.exists() } ?: return null
        return WebResourceResponse("image/jpeg", null, file.inputStream()).apply {
            responseHeaders = mapOf("Cache-Control" to "no-store")
        }
    }
}
