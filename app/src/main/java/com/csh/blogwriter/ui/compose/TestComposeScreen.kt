package com.csh.blogwriter.ui.compose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csh.blogwriter.ui.components.AppTextField
import com.csh.blogwriter.ui.components.AppTopBar
import com.csh.blogwriter.ui.components.BottomCta
import com.csh.blogwriter.ui.components.PhotoGrid
import com.csh.blogwriter.ui.components.ScreenScaffold
import com.csh.blogwriter.ui.components.WeakButton
import com.csh.blogwriter.ui.theme.AppSpacing
import com.csh.blogwriter.ui.theme.AppTheme
import kotlinx.coroutines.launch

/** SP1 임시 화면: 제목/본문/사진을 손으로 넣어 발행 파이프라인을 시험한다. SP2 에서 4단계 글쓰기 흐름으로 대체된다. */
@Composable
fun TestComposeScreen(onBack: () -> Unit, onPublish: (jobId: String) -> Unit, viewModel: TestComposeViewModel = hiltViewModel()) {
    val title by viewModel.title.collectAsStateWithLifecycle()
    val body by viewModel.body.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)) { uris -> viewModel.addPhotos(uris) }

    ScreenScaffold(
        topBar = { AppTopBar(onBack = onBack) },
        bottom = {
            BottomCta("발행하러 가기", enabled = title.isNotBlank() && (body.isNotBlank() || photos.isNotEmpty()), loading = creating, onClick = {
                creating = true
                scope.launch { onPublish(viewModel.createJob()) }
            })
        },
    ) {
        Spacer(Modifier.height(AppSpacing.lg))
        Text("테스트 글을 써 볼까요?", style = AppTheme.typography.title1, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(AppSpacing.xxl))
        androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
            AppTextField(value = title, onValueChange = { viewModel.title.value = it }, label = "제목", placeholder = "오늘의 이야기")
            Spacer(Modifier.height(AppSpacing.xl))
            AppTextField(value = body, onValueChange = { viewModel.body.value = it }, label = "본문 (빈 줄로 문단을 나눠요)", placeholder = "여기에 글을 써 주세요", singleLine = false, minLines = 6)
            Spacer(Modifier.height(AppSpacing.xl))
            Text("사진 ${photos.size}장", style = AppTheme.typography.title3, color = AppTheme.colors.textPrimary)
            Spacer(Modifier.height(AppSpacing.md))
            if (photos.isNotEmpty()) { PhotoGrid(photos, onRemove = viewModel::removePhoto, onMove = viewModel::movePhoto); Spacer(Modifier.height(AppSpacing.md)) }
            WeakButton("사진 고르기", onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            Spacer(Modifier.height(AppSpacing.section))
        }
    }
}
