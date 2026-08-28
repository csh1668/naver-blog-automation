package com.csh.blogwriter.ui.publish

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.csh.blogwriter.data.prefs.SettingsStore
import com.csh.blogwriter.data.repo.FailureLogRepository
import com.csh.blogwriter.data.repo.HistoryRepository
import com.csh.blogwriter.data.repo.PendingJob
import com.csh.blogwriter.data.repo.PendingJobRepository
import com.csh.blogwriter.data.repo.PublishHistoryItem
import com.csh.blogwriter.data.repo.FailureLogItem
import com.csh.blogwriter.domain.model.Block
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.PreparedImage
import com.csh.blogwriter.domain.model.Run
import com.csh.blogwriter.domain.publish.PublishStage
import com.csh.blogwriter.domain.publish.PublishState
import com.csh.blogwriter.publish.DocumentModelConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PublishViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val content = PostContent("제목", listOf(Block.Paragraph(listOf(Run("본문"))), Block.Image("img_001")))
    private val job = PendingJob("job1", content, listOf("content://a"), null, 1L, null)

    private val pending = MutableStateFlow<PendingJob?>(job)
    private val history = mutableListOf<PublishHistoryItem>()
    private val failures = mutableListOf<FailureLogItem>()

    private val pendingRepo = object : PendingJobRepository {
        override fun observeLatest(): Flow<PendingJob?> = pending
        override suspend fun get(id: String) = pending.value?.takeIf { it.id == id }
        override suspend fun save(job: PendingJob) { pending.value = job }
        override suspend fun setPreparedPaths(id: String, paths: List<String>?) { pending.value = pending.value?.copy(preparedPaths = paths) }
        override suspend fun setLastFailure(id: String, message: String?) { pending.value = pending.value?.copy(lastFailure = message) }
        override suspend fun delete(id: String) { pending.value = null }
    }
    private val historyRepo = object : HistoryRepository {
        override fun observeAll() = flowOf(history.toList())
        override suspend fun add(title: String, logNo: String, url: String, imageCount: Int) { history += PublishHistoryItem(1, title, logNo, url, 0, imageCount) }
    }
    private val failureRepo = object : FailureLogRepository {
        override fun observeAll() = flowOf(failures.toList())
        override suspend fun add(stage: String, message: String, detail: String) { failures += FailureLogItem(1, 0, stage, message, detail, "t") }
    }
    private val settings = object : SettingsStore {
        override val blogId: Flow<String?> = MutableStateFlow("myblog")
        override suspend fun setBlogId(id: String?) {}
    }
    private val preparer = object : ImagePreparing {
        override suspend fun prepare(jobId: String, uris: List<String>, onProgress: (Int) -> Unit) = uris.mapIndexed { i, _ -> PreparedImage("img_%03d".format(i + 1), File("/tmp/img.jpg"), 800, 600).also { onProgress(i + 1) } }
        override fun load(jobId: String, paths: List<String>) = null
        override fun clear(jobId: String) {}
    }

    class FakeController : EditorController {
        val calls = mutableListOf<String>()
        override fun loadEditor(blogId: String) { calls += "load:$blogId" }
        override fun setLocalImages(images: List<PreparedImage>) { calls += "images:${images.size}" }
        override fun installBridgeScript() { calls += "install" }
        override fun checkReady() { calls += "ready?" }
        override fun dismissPopups() { calls += "popups" }
        override fun uploadImages(refs: List<String>) { calls += "upload:${refs.joinToString(",")}" }
        override fun setDocument(documentJson: String) { calls += "inject" }
    }

    private fun vm() = PublishViewModel(SavedStateHandle(mapOf("jobId" to "job1")), pendingRepo, historyRepo, failureRepo, settings, preparer, DocumentModelConverter())

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun happyPathDrivesControllerAndSavesHistory() = runTest {
        val vm = vm(); val c = FakeController()
        vm.attach(c); runCurrent()
        assertEquals(PublishState.LoadingEditor, vm.uiState.value.state)
        assertEquals(listOf("images:1", "load:myblog"), c.calls)

        vm.onUrlChanged("https://blog.naver.com/myblog?Redirect=Write"); vm.onPageFinished("https://blog.naver.com/myblog?Redirect=Write&categoryNo=25"); runCurrent()
        assertTrue(c.calls.contains("install")); assertTrue(c.calls.contains("ready?"))
        vm.onReady(); runCurrent()
        assertEquals(PublishState.DismissingPopups, vm.uiState.value.state); assertTrue(c.calls.contains("popups"))
        vm.onPopupsDismissed(0); runCurrent()
        assertEquals(PublishState.UploadingImages(0, 1), vm.uiState.value.state); assertTrue(c.calls.contains("upload:img_001"))
        vm.onImageUploaded("img_001", Json.parseToJsonElement("""{"url":"/a/b.PNG/img_001.jpg","fileName":"img_001.jpg","width":800,"height":600,"fileSize":1,"domain":"https://blogfiles.pstatic.net"}""").jsonObject); runCurrent()
        assertEquals(PublishState.Injecting, vm.uiState.value.state); assertTrue(c.calls.contains("inject"))
        vm.onInjected(3); runCurrent()
        assertEquals(PublishState.Reviewing, vm.uiState.value.state)
        vm.onUrlChanged("https://blog.naver.com/PostView.naver?blogId=myblog&logNo=99&isAfterWrite=true"); runCurrent()
        assertEquals(PublishState.Published("99", "https://blog.naver.com/myblog/99"), vm.uiState.value.state)
        assertEquals("제목", history.single().title); assertEquals(1, history.single().imageCount)
        assertNull(pending.value)
    }

    @Test
    fun loginRedirectSavesPendingAndSignalsSessionExpired() = runTest {
        val vm = vm(); val c = FakeController()
        vm.navigation.test {
            vm.attach(c); runCurrent()
            vm.onUrlChanged("https://nid.naver.com/nidlogin.login?url=x"); runCurrent()
            assertEquals(PublishNav.SessionExpired("job1"), awaitItem())
        }
        assertEquals(PublishState.SessionExpired, vm.uiState.value.state)
        assertEquals(listOf(File("/tmp/img.jpg").absolutePath), pending.value!!.preparedPaths)
    }

    @Test
    fun editorReadyTimeoutFailsAndLogs() = runTest {
        val vm = vm(); val c = FakeController()
        vm.navigation.test {
            vm.attach(c); runCurrent()
            vm.onPageFinished("https://blog.naver.com/myblog?Redirect=Write&categoryNo=25")
            advanceTimeBy(PublishViewModel.EDITOR_READY_TIMEOUT_MS + 1_000); advanceUntilIdle()
            assertEquals(PublishNav.Failed("job1"), awaitItem())
        }
        val failed = vm.uiState.value.state as PublishState.Failed
        assertEquals(PublishStage.LOAD_EDITOR, failed.stage)
        assertEquals("LOAD_EDITOR", failures.single().stage)
        assertEquals("LOAD_EDITOR: " + failed.message, pending.value!!.lastFailure)
    }
}
