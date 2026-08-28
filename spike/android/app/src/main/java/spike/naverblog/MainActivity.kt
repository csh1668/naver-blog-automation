package spike.naverblog

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * [스파이크용 throwaway] 네이버 스마트에디터 ONE 에 documentModel 을 주입하는 최소 WebView 앱.
 * 버튼: 로그인 → 글쓰기 열기 → 주입. 모든 URL 변화와 JS 콘솔은 logcat 태그 SPIKE 로 출력.
 */
class MainActivity : Activity() {

    companion object {
        const val TAG = "SPIKE"
        const val BLOG_ID = "myblog"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
        const val LOGIN_URL = "https://nid.naver.com/nidlogin.login"
        val WRITE_URL = "https://blog.naver.com/$BLOG_ID?Redirect=Write&viewType=pc"
    }

    private lateinit var webView: WebView
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        status = TextView(this).apply { text = "ready"; maxLines = 6 }
        webView = WebView(this)

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }
        buttons.addView(button("로그인") { webView.loadUrl(LOGIN_URL) })
        buttons.addView(button("글쓰기 열기") { webView.loadUrl(WRITE_URL) })
        buttons.addView(button("주입") { inject() })
        buttons.addView(button("쿠키") { log("cookies(blog.naver.com)=" + (CookieManager.getInstance().getCookie("https://blog.naver.com") ?: "null").take(200)) })

        root.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(ScrollView(this).apply { addView(status) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 200))
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

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

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { log("pageStarted $url") }
            override fun onPageFinished(view: WebView, url: String) {
                log("pageFinished $url")
                CookieManager.getInstance().flush()
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { log("history $url") }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.i(TAG, "JS[${m.messageLevel()}] ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.loadUrl(intent.getStringExtra("url") ?: LOGIN_URL)
    }

    inner class Bridge {
        @JavascriptInterface
        fun onResult(json: String) {
            Log.i(TAG, "RESULT_JSON $json")
            runOnUiThread { status.text = "RESULT: " + json.take(400) }
        }
        @JavascriptInterface
        fun log(msg: String) { this@MainActivity.log("js: $msg") }
    }

    private fun inject() {
        val images = JSONArray().apply {
            put(JSONObject().put("name", "spike_android_1.png").put("dataUrl", makeImageDataUrl("ANDROID SPIKE 1", Color.rgb(0x31, 0x82, 0xf6))))
            put(JSONObject().put("name", "spike_android_2.png").put("dataUrl", makeImageDataUrl("ANDROID SPIKE 2", Color.rgb(0xf0, 0x44, 0x52))))
        }
        val script = assets.open("inject.js").bufferedReader().readText()
            .replace("__IMAGES__", images.toString())
        log("inject: script ${script.length} chars")
        webView.evaluateJavascript(script) { r -> log("evaluateJavascript returned: $r") }
    }

    private fun makeImageDataUrl(label: String, color: Int): String {
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(color)
            drawText(label, 40f, 300f, Paint().apply { this.color = Color.WHITE; textSize = 64f; isFakeBoldText = true })
        }
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread { status.text = (msg + "\n" + status.text).take(1500) }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
