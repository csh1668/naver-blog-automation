package com.csh.blogwriter.ui.fallback

import androidx.lifecycle.SavedStateHandle
import com.csh.blogwriter.data.repo.PendingJob
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.Run
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FallbackViewModelTest {
    private fun repoWith(job: PendingJob) = object : PendingJobRepository {
        private val flow = MutableStateFlow<PendingJob?>(job)
        override fun observeLatest(): Flow<PendingJob?> = flow
        override suspend fun get(id: String) = flow.value
        override suspend fun save(job: PendingJob) {}
        override suspend fun setPreparedPaths(id: String, paths: List<String>?) {}
        override suspend fun setLastFailure(id: String, message: String?) {}
        override suspend fun delete(id: String) {}
    }

    private val job = PendingJob(
        "j",
        PostContent("제목", listOf(Block.Paragraph(listOf(Run("본문"))), Block.Image("img_001"))),
        listOf("u"), null, 0,
        "UPLOAD: 사진 업로드 실패 (img_001): SERVER_ERROR",
    )
    private val repo = repoWith(job)

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun buildsUserFacingStateFromJob() = runTest {
        val vm = FallbackViewModel(SavedStateHandle(mapOf("jobId" to "j")), repo)
        advanceUntilIdle()
        val s = vm.uiState.value!!
        assertEquals("제목", s.title)
        assertEquals("제목\n\n본문\n\n[사진 1]", s.clipboardText)
        assertTrue(s.reason.contains("사진"))
        assertTrue(s.shareText.contains("SERVER_ERROR"))
    }

    @Test
    fun derivesReasonFromLoadEditorStagePrefix() = runTest {
        val loadEditorJob = job.copy(lastFailure = "LOAD_EDITOR: 제한 시간 초과")
        val vm = FallbackViewModel(SavedStateHandle(mapOf("jobId" to "j")), repoWith(loadEditorJob))
        advanceUntilIdle()
        val s = vm.uiState.value!!
        assertEquals("네이버 글쓰기 화면이 열리지 않았어요.", s.reason)
    }

    @Test
    fun reasonMessagesAreNonTechnical() {
        assertEquals("네이버 글쓰기 화면이 열리지 않았어요.", FallbackReason.userMessage("제한 시간 초과", stageHint = "LOAD_EDITOR"))
        assertEquals("사진을 올리다가 멈췄어요.", FallbackReason.userMessage("사진 업로드 실패 (img_001): x", stageHint = null))
        assertEquals("글을 자동으로 채우지 못했어요.", FallbackReason.userMessage("컴포넌트 수 불일치", stageHint = null))
    }
}
