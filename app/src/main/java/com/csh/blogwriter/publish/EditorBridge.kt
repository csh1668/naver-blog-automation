package com.csh.blogwriter.publish

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject

/** JS(editor_bridge.js) → Kotlin 콜백. 모든 콜백은 메인 스레드로 전달된다. 이름은 JS 쪽 `AndroidBridge.*` 와 일치해야 한다. */
class EditorBridge(private val listener: Listener) {
    interface Listener {
        fun onReady()
        fun onPopupsDismissed(count: Int)
        fun onImageUploaded(ref: String, response: JsonObject)
        fun onImageFailed(ref: String, message: String)
        fun onInjected(componentCount: Int)
        fun onError(step: String, message: String)
        fun onLog(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private fun post(block: () -> Unit) = main.post(block)

    @JavascriptInterface fun onReady() = post { listener.onReady() }
    @JavascriptInterface fun onPopupsDismissed(count: Int) = post { listener.onPopupsDismissed(count) }
    @JavascriptInterface fun onImageUploaded(ref: String, responseJson: String) = post {
        runCatching { Json.parseToJsonElement(responseJson).jsonObject }
            .onSuccess { listener.onImageUploaded(ref, it) }
            .onFailure { listener.onImageFailed(ref, "응답 파싱 실패: ${it.message}") }
    }
    @JavascriptInterface fun onImageFailed(ref: String, message: String) = post { listener.onImageFailed(ref, message) }
    @JavascriptInterface fun onInjected(componentCount: Int) = post { listener.onInjected(componentCount) }
    @JavascriptInterface fun onError(step: String, message: String) = post { listener.onError(step, message) }
    @JavascriptInterface fun log(message: String) = post { listener.onLog(message) }
}
