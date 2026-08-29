package com.csh.blogwriter.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.csh.blogwriter.ui.theme.Palette

/** 묶음 배지 색 — 묶음 번호 순서대로 돌려 쓴다. */
private val GroupColors = listOf(Palette.Blue500, Palette.Green500, Palette.Orange500, Palette.Red500)

/**
 * 선택한 사진 그리드. 순서 배지 + 삭제/앞으로/뒤로 버튼(드래그 대신). 높이는 내용에 맞춰 고정.
 *
 * @param selectedOrder 묶는 중에 고른 사진과 고른 순서(1부터). 있으면 왼쪽 위 배지가 그 번호로 바뀐다.
 * @param badges 이미 묶여 있는 사진과 그 묶음 번호(1부터). 테두리와 오른쪽 위 배지로 보인다.
 * @param onTap 썸네일을 눌렀을 때(묶는 중에만 준다). null 이면 썸네일은 눌리지 않는다.
 * @param onBadgeTap 묶음 배지를 눌렀을 때 — 그 묶음을 푼다.
 * @param showControls 썸네일 아래 빼기·앞으로·뒤로 줄을 보일지. 묶는 중에는 탭만 받으려고 끈다.
 */
@Composable
fun PhotoGrid(
    uris: List<Uri>,
    onRemove: (Uri) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    columns: Int = 3,
    selectedOrder: Map<Uri, Int> = emptyMap(),
    badges: Map<Uri, Int> = emptyMap(),
    onTap: ((Uri) -> Unit)? = null,
    onBadgeTap: ((Uri) -> Unit)? = null,
    showControls: Boolean = true,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = AppSpacing.sm
        val cell = (maxWidth - gap * (columns - 1)) / columns
        val rows = (uris.size + columns - 1) / columns
        val height = cell * rows + (if (showControls) 48.dp * rows else 0.dp) + gap * (rows - 1).coerceAtLeast(0)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            userScrollEnabled = false,
        ) {
            itemsIndexed(uris, key = { _, u -> u.toString() }) { index, uri ->
                val groupNo = badges[uri]
                val groupColor = groupNo?.let { GroupColors[(it - 1) % GroupColors.size] }
                val picked = selectedOrder[uri]
                Column {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(AppSpacing.radiusThumb))
                            .then(if (groupColor != null) Modifier.border(3.dp, groupColor, RoundedCornerShape(AppSpacing.radiusThumb)) else Modifier)
                            .then(if (onTap != null) Modifier.clickable { onTap(uri) } else Modifier)
                    ) {
                        AsyncImage(model = uri, contentDescription = "사진 ${index + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                        Box(
                            Modifier.padding(AppSpacing.sm).size(28.dp).clip(CircleShape)
                                .background(if (picked != null) AppTheme.colors.fillSuccess else AppTheme.colors.fillBrand),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${picked ?: index + 1}", style = AppTheme.typography.body2, color = AppTheme.colors.textOnBrand)
                        }
                        // 묶음 배지는 눌러서 그 묶음을 풀 수 있다.
                        if (groupNo != null && groupColor != null) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(AppSpacing.sm).size(28.dp).clip(CircleShape).background(groupColor)
                                    .then(if (onBadgeTap != null) Modifier.clickable { onBadgeTap(uri) } else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$groupNo", style = AppTheme.typography.body2, color = AppTheme.colors.textOnBrand)
                            }
                        }
                    }
                    if (showControls) {
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
}
