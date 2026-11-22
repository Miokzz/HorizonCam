package com.miokzz.horizoncam

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.miokzz.horizoncam.ai.NativeRestorer
import com.miokzz.horizoncam.ai.PhotoProvenance
import com.miokzz.horizoncam.media.AutoPhotos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePublicationTest {
    @Test fun nativeResultBorrowsInputAndReportsZeroInference() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val input = texturedBitmap()
        try {
            NativeRestorer.restore(app, input, 1f, 100, 1, {}, { false }, 0.002).use { result ->
                assertSame(input, result.bitmap)
                assertFalse(result.usedNeuralModel)
                assertEquals(0, result.tiles)
                assertEquals(0L, result.inferenceMs)
                assertEquals(0L, result.nativeInferenceMs)
            }
            assertFalse("Closing the result must leave the captured pixels usable", input.isRecycled)
        } finally {
            input.recycle()
        }
    }

    @Test fun nativeQueuePublishesOneJpegWithCameraPixelsAndOrientation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = app.contentResolver
        val photos = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val source = AutoPhotos.beginBurst(app, 1f)
        val displayName = "HC_${source.name}"
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val args = arrayOf(displayName)
        try {
            val bitmap = texturedBitmap()
            try {
                source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.002")
                setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "100")
                saveAttributes()
            }
            val original = source.readBytes()
            AutoPhotos.releaseBurst(app, source)
            AutoPhotos.recover(app)
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (source.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertFalse("Worker did not complete the capture", source.exists())

            val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING)
            val uri = checkNotNull(resolver.query(photos, columns, selection, args, null)).use {
                assertEquals(1, it.count)
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(1))
                ContentUris.withAppendedId(photos, it.getLong(0))
            }
            val saved = checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
            val exif = ExifInterface(saved.inputStream())
            val description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).orEmpty()
            assertEquals(PhotoProvenance.Kind.NATIVE, PhotoProvenance.classify(description))
            assertTrue("input/output 72x96 -> 72x96" in description)
            assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
            assertEquals(100, exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0))
            val expectedPixels = BitmapFactory.decodeByteArray(original, 0, original.size)
            val savedPixels = BitmapFactory.decodeByteArray(saved, 0, saved.size)
            try {
                assertEquals(96, savedPixels.width)
                assertEquals(72, savedPixels.height)
                assertTrue("Native JPEG should preserve the camera's decoded pixels", savedPixels.sameAs(expectedPixels))
            } finally {
                expectedPixels.recycle()
                savedPixels.recycle()
            }
        } finally {
            source.delete()
            AutoPhotos.releaseBurst(app, source)
            resolver.delete(photos, selection, args)
        }
    }

    private fun texturedBitmap(): Bitmap {
        val random = java.util.Random(531L)
        val pixels = IntArray(96 * 72) {
            val gray = 32 + random.nextInt(192)
            (255 shl 24) or (gray * 0x010101)
        }
        return Bitmap.createBitmap(pixels, 96, 72, Bitmap.Config.ARGB_8888)
    }
}
