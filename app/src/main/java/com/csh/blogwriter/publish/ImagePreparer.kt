package com.csh.blogwriter.publish

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.csh.blogwriter.domain.model.PreparedImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.max

/** 갤러리 Uri → 업로드용 JPEG (긴 변 1600px, 품질 85, EXIF 회전 적용, ASCII 파일명). */
class ImagePreparer @Inject constructor(@ApplicationContext private val context: Context) {

    companion object { const val LONG_EDGE = 1600; const val QUALITY = 85 }

    private fun dir(jobId: String) = File(context.cacheDir, "publish/$jobId").apply { mkdirs() }

    suspend fun prepare(jobId: String, uris: List<Uri>, onProgress: (Int) -> Unit): List<PreparedImage> = withContext(Dispatchers.IO) {
        val dir = dir(jobId)
        uris.mapIndexed { index, uri ->
            val ref = "img_%03d".format(index + 1)
            val bitmap = decodeScaled(uri)
            val out = File(dir, "$ref.jpg")
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            val prepared = PreparedImage(ref, out, bitmap.width, bitmap.height)
            bitmap.recycle()
            onProgress(index + 1)
            prepared
        }
    }

    fun load(jobId: String, paths: List<String>): List<PreparedImage>? {
        val images = paths.mapIndexed { index, path ->
            val file = File(path)
            if (!file.exists()) return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            PreparedImage("img_%03d".format(index + 1), file, opts.outWidth, opts.outHeight)
        }
        return images
    }

    fun clear(jobId: String) {
        val d = dir(jobId)
        // Windows(JVM)에서 방금 읽은 파일이 네이티브 디코더에 의해 잠시 잠길 수 있어 1회 GC 후 재시도한다.
        if (!d.deleteRecursively()) { System.gc(); d.deleteRecursively() }
    }

    private fun decodeScaled(uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("이미지를 읽을 수 없습니다: $uri")
        val orientation = resolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        val longEdge = max(decoded.width, decoded.height)
        val scale = if (longEdge > LONG_EDGE) LONG_EDGE.toFloat() / longEdge else 1f
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
        }
        if (matrix.isIdentity) return decoded
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result !== decoded) decoded.recycle()
        return result
    }
}
