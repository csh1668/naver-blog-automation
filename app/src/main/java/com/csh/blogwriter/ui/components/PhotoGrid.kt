package com.csh.blogwriter.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

/** 선택한 사진 그리드. 순서 배지 + 삭제/앞으로/뒤로 버튼(드래그 대신). 높이는 내용에 맞춰 고정. */
@Composable
fun PhotoGrid(uris: List<Uri>, onRemove: (Uri) -> Unit, onMove: (from: Int, to: Int) -> Unit, columns: Int = 3) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = AppSpacing.sm
        val cell = (maxWidth - gap * (columns - 1)) / columns
        val rows = (uris.size + columns - 1) / columns
        val height = cell * rows + 48.dp * rows + gap * (rows - 1).coerceAtLeast(0)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            userScrollEnabled = false,
        ) {
            itemsIndexed(uris, key = { _, u -> u.toString() }) { index, uri ->
                Column {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(AppSpacing.radiusThumb))) {
                        AsyncImage(model = uri, contentDescription = "사진 ${index + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                        Box(Modifier.padding(AppSpacing.sm).size(28.dp).clip(CircleShape).background(AppTheme.colors.fillBrand), contentAlignment = Alignment.Center) {
                            Text("${index + 1}", style = AppTheme.typography.body2, color = AppTheme.colors.textOnBrand)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) { Icon(Icons.Rounded.ArrowBack, contentDescription = "앞으로") }
                        IconButton(onClick = { onRemove(uri) }) { Icon(Icons.Rounded.Close, contentDescription = "빼기", tint = AppTheme.colors.fillDanger) }
                        IconButton(onClick = { onMove(index, index + 1) }, enabled = index < uris.lastIndex) { Icon(Icons.Rounded.ArrowForward, contentDescription = "뒤로") }
                    }
                }
            }
        }
    }
}
