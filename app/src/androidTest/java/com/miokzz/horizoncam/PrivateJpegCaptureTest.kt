package com.miokzz.horizoncam

import android.content.ContentUris
import android.graphics.Bitmap
import android.media.ExifInterface
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.miokzz.horizoncam.media.AutoPhotos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PrivateJpegCaptureTest {
    @Test fun adapterPublishesOneFinalJpegAfterDuplicateCompletionAndRecovery() {
        val runner = InstrumentationRegistry.getInstrumentation()
        val app = runner.targetContext
        val resolver = app.contentResolver
        val photos = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val requestedFile = AtomicReference<File>()
        val callbacks = AtomicInteger()
        val completion = CompletableFuture<Pair<Boolean, Throwable?>>()
        try {
            AutoPhotos.capture(app, zoom = 1f, crop = 1f, nativeEdge = 96,
                takePicture = { file, complete ->
                    check(Looper.myLooper() == Looper.getMainLooper())
                    requestedFile.set(file)
                    val random = java.util.Random(531L)
                    val pixels = IntArray(96 * 72) {
                        val gray = 32 + random.nextInt(192)
                        (255 shl 24) or (gray * 0x010101)
                    }
                    val bitmap = Bitmap.createBitmap(pixels, 96, 72, Bitmap.Config.ARGB_8888)
                    try {
                        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                    } finally {
                        bitmap.recycle()
                    }
                    ExifInterface(file).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                        setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "70/10")
                        setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.002")
                        setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "100")
                        saveAttributes()
                    }
                    complete(Result.success(Unit))
                    complete(Result.failure(IllegalStateException("late backend completion")))
                },
                physicalId = { "6" }, boundSensor = "6") { error ->
                callbacks.incrementAndGet()
                completion.complete((Looper.myLooper() == Looper.getMainLooper()) to error)
            }

            val (onMain, error) = completion.get(10, TimeUnit.SECONDS)
            assertTrue(onMain)
            assertNull(error)
            val part = checkNotNull(requestedFile.get())
            val source = File(part.parentFile, "${part.nameWithoutExtension}.jpg")
            runner.runOnMainSync { AutoPhotos.recover(app) }
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (source.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertFalse("The queue did not finish this synthetic capture", source.exists())
            assertFalse(part.exists())
            assertEquals(1, callbacks.get())

            val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING)
            val args = arrayOf("HC_${source.name}")
            val uri = checkNotNull(resolver.query(photos, columns, selection, args, null)).use {
                assertEquals(1, it.count)
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(1))
                ContentUris.withAppendedId(photos, it.getLong(0))
            }
            checkNotNull(resolver.openInputStream(uri)).use {
                val exif = ExifInterface(it)
                assertEquals(ExifInterface.ORIENTATION_ROTATE_90,
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
                assertEquals(7.0, exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0), 0.01)
            }
        } finally {
            requestedFile.get()?.let { part ->
                part.delete()
                val source = File(part.parentFile, "${part.nameWithoutExtension}.jpg")
                source.delete()
                resolver.delete(photos, selection, arrayOf("HC_${source.name}"))
            }
        }
    }

    @Test fun adapterReportsCaptureFailureAndRemovesPartialFile() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val requestedFile = AtomicReference<File>()
        val completion = CompletableFuture<Throwable?>()
        val failure = IllegalStateException("simulated camera failure")
        AutoPhotos.capture(app, zoom = 3f, crop = 1f, nativeEdge = 3648,
            takePicture = { file, complete ->
                requestedFile.set(file)
                file.writeText("incomplete")
                complete(Result.failure(failure))
            },
            physicalId = { "6" }, boundSensor = "6") { completion.complete(it) }

        assertSame(failure, completion.get(10, TimeUnit.SECONDS))
        val part = checkNotNull(requestedFile.get())
        assertFalse(part.exists())
        assertFalse(File(part.parentFile, "${part.nameWithoutExtension}.jpg").exists())
    }
}
