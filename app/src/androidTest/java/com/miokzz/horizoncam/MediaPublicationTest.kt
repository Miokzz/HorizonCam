package com.miokzz.horizoncam

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.miokzz.horizoncam.media.AiPhotoFiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPublicationTest {
    @Test fun galleryJpegContainsFinalPixelsAndMetadataOnFirstOpen() {
        val runner = InstrumentationRegistry.getInstrumentation()
        val app = runner.targetContext
        val source = File.createTempFile("publication-test-", ".png", app.cacheDir)
        try {
            runner.context.assets.open("quality/astronaut-detail-truth.png").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val bitmap = BitmapFactory.decodeFile(source.path)
            try {
                val tag = "HorizonCam · teste de publicação"
                val uri = AiPhotoFiles.save(app, Uri.fromFile(source), bitmap, "HC_PUBLICATION_TEST", tag)
                try {
                    app.contentResolver.openInputStream(uri)!!.use {
                        assertEquals("HorizonCam | teste de publicacao", ExifInterface(it).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
                    }
                    app.contentResolver.openInputStream(uri)!!.use {
                        val decoded = BitmapFactory.decodeStream(it)
                        try {
                            assertEquals(bitmap.width, decoded.width)
                            assertEquals(bitmap.height, decoded.height)
                        } finally { decoded.recycle() }
                    }
                } finally { app.contentResolver.delete(uri, null, null) }
            } finally { bitmap.recycle() }
        } finally { source.delete() }
    }
}
