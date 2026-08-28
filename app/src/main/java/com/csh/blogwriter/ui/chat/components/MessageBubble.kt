package com.csh.blogwriter.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme

/** 말풍선. 사용자는 오른쪽 브랜드색, 어시스턴트는 왼쪽 연회색. 최대 폭은 채팅 영역의 80%. */
@Composable
fun MessageBubble(text: String, mine: Boolean) {
    val c = AppTheme.colors
    val shape = if (mine) {
        RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = AppSpacing.xs),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier.fillMaxWidth(0.8f).clip(shape)
                .background(if (mine) c.fillBrand else c.surfaceWeak)
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        ) {
            Text(text, style = AppTheme.typography.body1, color = if (mine) c.textOnBrand else c.textPrimary)
        }
    }
}

/** 첨부한 사진 줄. 기록용이라 지우거나 순서를 바꾸지 않는다. */
@Composable
fun PhotosBubble(uris: List<String>) {
    Row(Modifier.fillMaxWidth().padding(vertical = AppSpacing.xs), horizontalArrangement = Arrangement.End) {
        LazyRow(
            Modifier.fillMaxWidth(0.8f),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            items(uris) { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = "붙인 사진",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(AppSpacing.radiusThumb)),
                )
            }
        }
    }
}

/** 발행 완료·오류 같은 알림. 가운데 정렬. */
@Composable
fun SystemMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = AppSpacing.sm), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth(0.9f)) { content() }
    }
}
