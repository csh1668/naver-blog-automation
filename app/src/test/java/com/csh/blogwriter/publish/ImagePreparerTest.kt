package com.csh.blogwriter.publish

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagePreparerTest {
    @get:Rule val folder = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()

    private fun jpeg(name: String, w: Int, h: Int, orientation: Int? = null): Uri {
        val file = File(folder.root, name)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (orientation != null) ExifInterface(file.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
        return Uri.fromFile(file)
    }

    @Test
    fun resizesToLongEdge1600AndNamesSequentially() = runTest {
        val preparer = ImagePreparer(context)
        val progress = mutableListOf<Int>()
        val result = preparer.prepare("job1", listOf(jpeg("a.jpg", 3200, 1600), jpeg("b.jpg", 400, 800)), progress::add)

        assertEquals(listOf("img_001", "img_002"), result.map { it.ref })
        assertEquals(1600, result[0].width); assertEquals(800, result[0].height)
        assertEquals(400, result[1].width); assertEquals(800, result[1].height)
        assertTrue(result.all { it.file.exists() && it.file.name == "${it.ref}.jpg" })
        val decoded = BitmapFactory.decodeFile(result[0].file.absolutePath)
        assertEquals(1600, decoded.width)
        assertEquals(listOf(1, 2), progress)
    }

    @Test
    fun appliesExifRotation() = runTest {
        val result = ImagePreparer(context).prepare("job2", listOf(jpeg("r.jpg", 800, 400, ExifInterface.ORIENTATION_ROTATE_90)), {})
        assertEquals(400, result[0].width); assertEquals(800, result[0].height)
    }

    @Test
    fun loadReusesExistingFilesAndClearDeletes() = runTest {
        val preparer = ImagePreparer(context)
        val prepared = preparer.prepare("job3", listOf(jpeg("c.jpg", 100, 100)), {})
        val loaded = preparer.load("job3", prepared.map { it.file.absolutePath })!!
        assertEquals(prepared.map { it.ref }, loaded.map { it.ref })
        assertEquals(100, loaded[0].width)
        preparer.clear("job3")
        assertNull(preparer.load("job3", prepared.map { it.file.absolutePath }))
    }
}
