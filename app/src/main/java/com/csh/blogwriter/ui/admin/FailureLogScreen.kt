package com.csh.blogwriter.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.ListRow
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.format.DateFormats
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/** 관리자용. 기술 용어 허용. 항목을 탭하면 상세가 클립보드에 복사된다. */
@Composable
fun FailureLogScreen(onBack: () -> Unit, viewModel: FailureLogViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ScreenScaffold(topBar = { AppTopBar(onBack = onBack, title = "실패 로그 (관리자)") }) {
        Spacer(Modifier.height(AppSpacing.lg))
        if (items.isEmpty()) Text("기록된 실패가 없습니다.", style = AppTheme.typography.body1, color = AppTheme.colors.textSecondary)
        LazyColumn {
            items(items, key = { it.id }) { item ->
                ListRow(
                    title = "[${item.stage}] ${item.message}",
                    subtitle = DateFormats.relative(item.at) + " · v${item.appVersion}",
                    onClick = {
                        val text = "${item.stage} @ ${item.at}\n${item.message}\n${item.detail}"
                        context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("failure", text))
                    },
                )
                Spacer(Modifier.height(AppSpacing.md))
            }
        }
    }
}
