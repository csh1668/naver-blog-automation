package com.csh.blogwriter.ui.chat.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/**
 * 발행이 끝난 대화의 오른쪽 자리: 계획 대신 실제로 올라간 글을 보여 준다.
 * 기본 UA(모바일)로 열어 네이버가 모바일 블로그 페이지를 내려 주므로 좁은 패널에도 그대로 맞는다.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PublishedPostPanel(url: String, title: String = "발행한 글", modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val web = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
    }
    LaunchedEffect(url) { web.loadUrl(url) }
    DisposableEffect(web) { onDispose { web.destroy() } }

    Column(modifier.fillMaxSize().background(c.backgroundAlt)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = AppSpacing.xl, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = AppTheme.typography.title3, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { uriHandler.openUri(url) }) {
                Text("브라우저에서 열기", style = AppTheme.typography.body2, color = c.fillBrand)
            }
        }
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
    }
}
