package com.csh.blogwriter.ui.chat.components

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.csh.blogwriter.speech.SpeechInput
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 빈 채팅(새 글)의 가운데 입력창 높이. */
private val HeroMinHeight = 132.dp

/** 하단 입력줄: 사진 붙이기 · 텍스트 · 마이크 · 보내기. */
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: (List<String>) -> Unit,
    enabled: Boolean,
    placeholder: String,
    /** 초안이 나온 뒤에는 사진을 더 붙일 수 없다 (증분 업로드는 SP3). */
    canAttach: Boolean = true,
    /** 빈 채팅에서 화면 가운데에 크게 놓일 때. 입력창만 높아지고 버튼 배치는 같다. */
    hero: Boolean = false,
    /** 입력창에 커서가 들어오고 나갈 때. 넓은 화면에서 채팅과 오른쪽 패널의 비율이 여기에 따라 바뀐다. */
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val speech = remember { SpeechInput(context) }
    // 인식 엔진 조회는 패키지 매니저를 타므로 리컴포지션마다 하지 않는다.
    val speechAvailable = remember { speech.available }
    var listening by remember { mutableStateOf(false) }
    var listenJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { listenJob?.cancel() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)) { uris ->
        onAttach(uris.map { it.toString() })
    }

    fun startListening() {
        listening = true
        listenJob = scope.launch {
            try {
                speech.listen().collect { onTextChange(it) }
            } finally {
                listening = false
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening()
        else Toast.makeText(context, "마이크를 쓰려면 권한이 필요해요", Toast.LENGTH_SHORT).show()
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
        // 입력창이 여러 줄로 늘어나도 버튼들은 그 세로 중앙에 붙는다.
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = enabled && canAttach,
            modifier = Modifier.size(AppSpacing.touchTarget),
        ) {
            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "사진 붙이기", tint = c.textSecondary)
        }
        Spacer(Modifier.width(AppSpacing.sm))
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            enabled = enabled,
            modifier = Modifier.weight(1f)
                .defaultMinSize(minHeight = if (hero) HeroMinHeight else AppSpacing.ctaHeight)
                .onFocusChanged { onFocusChanged(it.isFocused) },
            textStyle = AppTheme.typography.body1.copy(color = c.textPrimary),
            placeholder = { Text(placeholder, style = AppTheme.typography.body1, color = c.textTertiary) },
            shape = RoundedCornerShape(24.dp),
            maxLines = if (hero) 8 else 5,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.surfaceWeak, unfocusedContainerColor = c.surfaceWeak,
                disabledContainerColor = c.surfaceWeak,
                focusedBorderColor = c.fillBrand, unfocusedBorderColor = c.surfaceWeak,
                disabledBorderColor = c.surfaceWeak, cursorColor = c.fillBrand,
            ),
        )
        Spacer(Modifier.width(AppSpacing.sm))
        // 인식 엔진이 없는 기기(에뮬레이터 등)에서는 아예 보여 주지 않는다.
        if (speechAvailable) {
            IconButton(
                onClick = {
                    if (listening) listenJob?.cancel() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = enabled,
                modifier = Modifier.size(AppSpacing.touchTarget),
            ) {
                Icon(
                    if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = if (listening) "말하기 멈추기" else "말로 입력하기",
                    tint = if (listening) c.fillDanger else c.textSecondary,
                )
            }
            Spacer(Modifier.width(AppSpacing.sm))
        }
        IconButton(
            // 보내고 나면 커서를 놓아 준다 — 오른쪽 패널이 다시 넓어진다.
            onClick = { focusManager.clearFocus(); onSend() },
            enabled = enabled && text.isNotBlank(),
            modifier = Modifier.size(AppSpacing.touchTarget).clip(CircleShape)
                .background(if (enabled && text.isNotBlank()) c.fillBrand else c.surfaceWeak),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Send,
                contentDescription = "보내기",
                tint = if (enabled && text.isNotBlank()) c.textOnBrand else c.textTertiary,
            )
        }
    }
}
