package com.csh.blogwriter.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.csh.blogwriter.publish.ImagePreparer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** 채팅에 붙인 사진 한 장. [uri] 는 갤러리 원본(발행 때 다시 준비한다), [thumb] 는 세션 캐시의 작은 JPEG 경로. */
data class AttachedPhoto(val ref: String, val uri: String, val thumb: String?)

/**
 * 채팅 첨부 사진을 LLM 이 볼 수 있는 형태로 준비한다.
 * 발행 파이프라인이 쓰는 큰 이미지와 달리 여기서는 긴 변 1024px JPEG + base64 만 만든다.
 */
interface PhotoAttachments {
    /** ref 는 [startIndex] 다음부터 `img_001` 형식으로 이어 붙인다. */
    suspend fun prepare(sessionId: String, startIndex: Int, uris: List<String>): List<AttachedPhoto>
    /** 이 세션의 사진들을 모델에 넘길 형태로. 캐시가 없으면 원본에서 다시 만든다. */
    suspend fun attachments(sessionId: String, photos: List<AttachedPhoto>): List<Attachment>
    fun clear(sessionId: String)
}

@Singleton
class CachedPhotoAttachments @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preparer: ImagePreparer,
) : PhotoAttachments {

    companion object { const val LONG_EDGE = 1024; const val QUALITY = 80 }

    /** "{sessionId}/{ref}" → base64. 한 세션 안에서 매 턴 다시 인코딩하지 않으려고 둔다. */
    private val base64Cache = mutableMapOf<String, String>()

    private fun dir(sessionId: String) = File(context.cacheDir, "chat/$sessionId").apply { mkdirs() }
    private fun file(sessionId: String, ref: String) = File(dir(sessionId), "$ref.jpg")

    override suspend fun prepare(sessionId: String, startIndex: Int, uris: List<String>): List<AttachedPhoto> =
        withContext(Dispatchers.IO) {
            uris.mapIndexed { index, uri ->
                val ref = "img_%03d".format(startIndex + index + 1)
                val out = file(sessionId, ref)
                encode(uri, out)?.let { base64Cache["$sessionId/$ref"] = it }
                AttachedPhoto(ref, uri, out.takeIf { it.exists() }?.absolutePath)
            }
        }

    override suspend fun attachments(sessionId: String, photos: List<AttachedPhoto>): List<Attachment> =
        withContext(Dispatchers.IO) {
            photos.mapNotNull { photo ->
                val key = "$sessionId/${photo.ref}"
                val cached = base64Cache[key]
                    ?: file(sessionId, photo.ref).takeIf { it.exists() }?.let { Base64.getEncoder().encodeToString(it.readBytes()) }
                    ?: encode(photo.uri, file(sessionId, photo.ref))
                cached?.also { base64Cache[key] = it }?.let { Attachment(photo.ref, it) }
            }
        }

    override fun clear(sessionId: String) {
        base64Cache.keys.filter { it.startsWith("$sessionId/") }.forEach(base64Cache::remove)
        dir(sessionId).deleteRecursively()
    }

    /** 원본이 이미 사라졌을 수도 있으므로(권한 만료·삭제) 실패하면 그 사진만 건너뛴다. */
    private fun encode(uri: String, out: File): String? = try {
        val bitmap = preparer.decodeScaled(Uri.parse(uri), LONG_EDGE)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        bitmap.recycle()
        Base64.getEncoder().encodeToString(out.readBytes())
    } catch (e: Exception) {
        null
    }
}
