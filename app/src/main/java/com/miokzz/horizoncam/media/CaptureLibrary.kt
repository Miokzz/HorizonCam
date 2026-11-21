package com.miokzz.horizoncam.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.miokzz.horizoncam.video.VideoFileDiagnostics
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class CaptureLibrary(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("horizoncam", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = ContextCompat.getMainExecutor(app)
    private var closed = false
    var latest: Uri? = (prefs.getString("lastMedia", null) ?: prefs.getString("lastVideo", null))?.let(Uri::parse)
        private set

    fun remember(uri: Uri) {
        latest = uri
        prefs.edit().putString("lastMedia", uri.toString()).apply()
    }

    fun inspectAndLoad(uri: Uri, onThumbnail: (Bitmap) -> Unit) {
        if (closed) return
        worker.execute {
            runCatching {
                val mime = app.contentResolver.getType(uri).orEmpty()
                if (mime.startsWith("image/")) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    Log.i(TAG, "$mime saved ${options.outWidth}x${options.outHeight}")
                } else {
                    val details = VideoFileDiagnostics.inspect(app, uri, maxSamples = 600)
                    Log.i(TAG, "MP4 saved ${details.encodedWidth}x${details.encodedHeight} rotation=${details.rotationDegrees} cadence=${details.cadence}")
                }
                app.contentResolver.loadThumbnail(uri, Size(144, 144), null)
            }.onSuccess { bitmap ->
                main.execute { if (!closed && latest == uri) onThumbnail(bitmap) }
            }.onFailure { Log.w(TAG, "Mídia indisponível para miniatura", it) }
        }
    }

    fun viewIntent(uri: Uri) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, app.contentResolver.getType(uri) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun close() {
        closed = true
        worker.shutdown()
    }

    companion object {
        private const val TAG = "HorizonCamMedia"

        fun values(photo: Boolean) = ContentValues().apply {
            val extension = if (photo) "jpg" else "mp4"
            val folder = if (photo) "Pictures" else "Movies"
            put(MediaStore.MediaColumns.DISPLAY_NAME, "HC_${System.currentTimeMillis()}.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, if (photo) "image/jpeg" else "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/HorizonCam")
        }
    }
}
