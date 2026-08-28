package com.csh.blogwriter.ui.login

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.publish.NaverWebViewConfig
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.BannerKind
import com.csh.blogwriter.ui.components.InlineBanner
import com.csh.blogwriter.ui.components.ProgressScreen
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun LoginScreen(onBack: () -> Unit, onDone: (blogId: String) -> Unit, viewModel: LoginViewModel = hiltViewModel()) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val url by viewModel.urlToLoad.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val webView = remember {
        WebView(context).also { web ->
            NaverWebViewConfig.apply(web)
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, u: String, favicon: Bitmap?) { viewModel.onUrlChanged(u) }
                override fun doUpdateVisitedHistory(v: WebView, u: String, isReload: Boolean) { viewModel.onUrlChanged(u) }
                override fun onPageFinished(v: WebView, u: String) { CookieManager.getInstance().flush() }
            }
        }
    }
    DisposableEffect(webView) { onDispose { webView.stopLoading(); webView.destroy() } }
    LaunchedEffect(url) { webView.loadUrl(url) }
    LaunchedEffect(phase) { (phase as? LoginPhase.Done)?.let { onDone(it.blogId) } }

    when (phase) {
        LoginPhase.LoggingIn -> ScreenScaffold(topBar = { AppTopBar(onBack = onBack) }) {
            Text("네이버에 로그인해 주세요", style = AppTheme.typography.title2, color = AppTheme.colors.textPrimary)
            Spacer(Modifier.height(AppSpacing.md))
            message?.let {
                InlineBanner(it, BannerKind.Danger)
                Spacer(Modifier.height(AppSpacing.md))
            }
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize().padding(bottom = AppSpacing.lg))
        }
        else -> {
            // ResolvingBlogId / Done: 블로그 정보를 읽는 동안 WebView 는 숨기고 진행 화면만 보여준다.
            ProgressScreen(title = "블로그 정보를 확인하고 있어요", detail = null, progress = null)
            AndroidView(factory = { webView }, modifier = Modifier.height(0.dp))
        }
    }
}
