package com.miokzz.horizoncam

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.miokzz.horizoncam.media.AutoPhotos
import com.miokzz.horizoncam.media.AiPhotoFiles
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class BurstPublicationRecoveryTest {
    @Test fun recoveryKeepsCommittedGalleryJpegWhenPrivateRenderAndMarkerAreGone() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = app.contentResolver
        val directory = File(app.noBackupFilesDir, "pending-photos")
        check(directory.isDirectory || directory.mkdirs())
        val source = File(directory,
            "${System.currentTimeMillis()}_${30f.toRawBits()}_${3f.toRawBits()}_${UUID.randomUUID()}.jpg")
        val uuidName = "HC_${source.name}"
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uuidName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/HorizonCam/")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            try {
                source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { bitmap.recycle() }
            val finalBytes = source.readBytes()
            AiPhotoFiles.copyAndPublish(app, source, uri)
            // Recovery has only the capture UUID. Decoding the damaged private file would fail.
            source.writeBytes(byteArrayOf(0))
            AutoPhotos.recover(app)
            awaitPublication(source)
            resolver.query(images, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(uuidName), null)!!.use {
                assertEquals(1, it.count)
                assertTrue(it.moveToFirst())
                assertEquals(ContentUris.parseId(uri), it.getLong(0))
            }
            resolver.openInputStream(uri)!!.use { assertArrayEquals(finalBytes, it.readBytes()) }
        } finally {
            source.delete()
            resolver.delete(uri, null, null)
        }
    }

    @Test fun interruptedBurstReusesOrphanReservationAndPublishesOneExactJpeg() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = AutoPhotos.beginBurst(app, 2.3f)
        val photo = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        try {
            photo.eraseColor(0xff31556a.toInt())
            file.outputStream().use { check(photo.compress(Bitmap.CompressFormat.JPEG, 96, it)) }
        } finally { photo.recycle() }
        val original = file.readBytes()
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = "HC_${file.name}"
        val resolver = app.contentResolver
        val pending = requireNotNull(resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/HorizonCam/")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            AutoPhotos.releaseBurst(app, file, useCaptured = true)
            AutoPhotos.recover(app)
            awaitPublication(file)
            resolver.query(images, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name), null)!!.use {
                assertEquals(1, it.count)
                assertTrue(it.moveToFirst())
                assertEquals(ContentUris.parseId(pending), it.getLong(0))
            }
            resolver.openInputStream(pending)!!.use { assertArrayEquals(original, it.readBytes()) }
        } finally {
            resolver.delete(images, "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name))
            runCatching { resolver.delete(pending, null, null) }
        }
    }

    private fun awaitPublication(file: File) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (file.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertFalse("Worker never committed the captured reference", file.exists())
    }
}
