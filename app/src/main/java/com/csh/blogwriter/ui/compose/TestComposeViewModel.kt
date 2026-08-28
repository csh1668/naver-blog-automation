package com.csh.blogwriter.ui.compose

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.csh.blogwriter.data.repo.PendingJob
import com.csh.blogwriter.data.repo.PendingJobRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class TestComposeViewModel @Inject constructor(private val pendingJobs: PendingJobRepository) : ViewModel() {
    val title = MutableStateFlow("")
    val body = MutableStateFlow("")
    private val _photos = MutableStateFlow<List<Uri>>(emptyList())
    val photos: StateFlow<List<Uri>> = _photos

    fun addPhotos(uris: List<Uri>) = _photos.update { (it + uris).distinct() }
    fun removePhoto(uri: Uri) = _photos.update { it - uri }
    fun movePhoto(from: Int, to: Int) = _photos.update { list ->
        if (from !in list.indices || to !in list.indices) list else list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** PendingJob 을 저장하고 id 를 돌려준다. 발행 화면은 이 id 로 작업을 읽는다. */
    suspend fun createJob(): String {
        val id = UUID.randomUUID().toString()
        val content = TestPostBuilder.build(title.value, body.value, _photos.value.size)
        pendingJobs.save(PendingJob(id, content, _photos.value.map { it.toString() }, null, System.currentTimeMillis(), null))
        return id
    }
}
