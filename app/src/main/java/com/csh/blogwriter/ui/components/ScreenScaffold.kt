package com.csh.blogwriter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/**
 * 한 화면 = 상단바 + 본문 + 하단 고정 CTA. 본문 폭은 720dp로 제한해 가운데 정렬.
 * 본문은 스크롤하지 않으며, 긴 정적 내용이 있는 화면은 content 안에서 verticalScroll을 직접 붙이고,
 * 목록 화면은 LazyColumn을 넣는다.
 */
@Composable
fun ScreenScaffold(
    topBar: @Composable () -> Unit = {},
    bottom: @Composable (ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        topBar()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            // 스크롤 없음: LazyColumn 등을 content에 넣을 수 있도록 부모가 스크롤을 소유하지 않는다.
            Column(
                Modifier.widthIn(max = AppSpacing.contentMaxWidth).fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenHorizontal),
                content = content,
            )
        }
        if (bottom != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = AppSpacing.contentMaxWidth).fillMaxWidth()
                        .padding(horizontal = AppSpacing.screenHorizontal, vertical = AppSpacing.xxl),
                    content = bottom,
                )
            }
        }
    }
}
