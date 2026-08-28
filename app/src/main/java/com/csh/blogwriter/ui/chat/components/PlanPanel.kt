package com.csh.blogwriter.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/** 오른쪽 패널의 글 계획. 초안이 나오기 전까지 여기서 계획을 읽고, 고칠 곳은 채팅으로 말한다. */
@Composable
fun PlanPanel(markdown: String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(modifier.fillMaxSize().background(c.backgroundAlt)) {
        Text(
            "글 계획",
            style = AppTheme.typography.title3, color = c.textPrimary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.xl, vertical = AppSpacing.lg),
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.xl)) {
            MarkdownLite(markdown, Modifier.fillMaxWidth().padding(bottom = AppSpacing.section))
        }
    }
}
