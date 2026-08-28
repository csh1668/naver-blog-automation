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
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * 캐시 키는 ref 가 아니라 **원본 uri** 다. 사진을 빼면 남은 사진의 ref 를 다시 매기는데,
     * ref 로 캐시를 잡으면 그때 다른 사진의 데이터를 돌려주게 된다.
     * 여러 코루틴(턴 실행 · 첨부)이 동시에 건드리므로 동시성 맵을 쓴다.
     */
    private val base64Cache = ConcurrentHashMap<String, String>()

    private fun key(sessionId: String, uri: String) = "$sessionId|$uri"
    private fun dir(sessionId: String) = File(context.cacheDir, "chat/$sessionId").apply { mkdirs() }
    private fun file(sessionId: String, uri: String) =
        File(dir(sessionId), uri.hashCode().toUInt().toString(16) + ".jpg")

    /**
     * 사진 선택기가 준 content:// 권한은 이 프로세스가 살아 있는 동안만 유효하다. 앱이 죽었다 살아난 뒤
     * 발행 파이프라인이 원본을 다시 읽으면 "permission to access picker uri" 로 실패하므로,
     * 붙이는 순간 원본 바이트를 앱 내부 저장소로 복사하고 그 file:// 주소를 이후 모든 곳(대화 기록·발행 작업)에서 쓴다.
     */
    private fun durableDir(sessionId: String) = File(context.filesDir, "photos/$sessionId").apply { mkdirs() }

    private fun copyOriginal(sessionId: String, uri: String): String? = try {
        val target = File(durableDir(sessionId), uri.hashCode().toUInt().toString(16) + ".img")
        if (!target.exists()) {
            context.contentResolver.openInputStream(Uri.parse(uri))!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        Uri.fromFile(target).toString()
    } catch (e: Exception) {
        null
    }

    override suspend fun prepare(sessionId: String, startIndex: Int, uris: List<String>): List<AttachedPhoto> =
        withContext(Dispatchers.IO) {
            uris.mapIndexed { index, original ->
                // file:// 이미 우리 사본이면(재첨부 등) 그대로 쓴다.
                val uri = if (original.startsWith("file:")) original else copyOriginal(sessionId, original) ?: original
                val out = file(sessionId, uri)
                val encoded = encode(uri, out)
                encoded?.let { base64Cache[key(sessionId, uri)] = it }
                AttachedPhoto("img_%03d".format(startIndex + index + 1), uri, if (encoded != null) out.absolutePath else null)
            }
        }

    override suspend fun attachments(sessionId: String, photos: List<AttachedPhoto>): List<Attachment> =
        withContext(Dispatchers.IO) {
            photos.mapNotNull { photo ->
                val cached = base64Cache[key(sessionId, photo.uri)]
                    ?: file(sessionId, photo.uri).takeIf { it.exists() }?.let { Base64.getEncoder().encodeToString(it.readBytes()) }
                    ?: encode(photo.uri, file(sessionId, photo.uri))
                cached?.also { base64Cache[key(sessionId, photo.uri)] = it }?.let { Attachment(photo.ref, it) }
            }
        }

    override fun clear(sessionId: String) {
        base64Cache.keys.removeAll { it.startsWith("$sessionId|") }
        dir(sessionId).deleteRecursively()
        durableDir(sessionId).deleteRecursively()
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
