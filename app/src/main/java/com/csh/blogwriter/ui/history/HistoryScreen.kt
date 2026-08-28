package com.csh.blogwriter.ui.history

import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.ListRow
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.format.DateFormats
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

@Composable
fun HistoryScreen(onBack: () -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ScreenScaffold(topBar = { AppTopBar(onBack = onBack) }) {
        Spacer(Modifier.height(AppSpacing.lg))
        Text("발행한 글", style = AppTheme.typography.title1, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(AppSpacing.xxl))
        if (items.isEmpty()) {
            Text("아직 올린 글이 없어요.\n첫 글을 써 볼까요?", style = AppTheme.typography.body1, color = AppTheme.colors.textSecondary)
        } else {
            LazyColumn {
                items(items, key = { it.id }) { item ->
                    ListRow(
                        title = item.title,
                        subtitle = DateFormats.relative(item.publishedAt) + " · 사진 ${item.imageCount}장",
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, item.url.toUri())) },
                    )
                    Spacer(Modifier.height(AppSpacing.md))
                }
            }
        }
    }
}
