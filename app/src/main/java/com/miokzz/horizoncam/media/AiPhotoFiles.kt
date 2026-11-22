package com.miokzz.horizoncam.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import kotlin.math.roundToInt

object AiPhotoFiles {
    data class Input(val uri: Uri, val detail: Boolean, val x: Float = 0.5f, val y: Float = 0.5f, val zoom: Float? = null)

    fun preview(context: Context, uri: Uri, maxEdge: Int = 900): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val ratio = minOf(1.0, maxEdge.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * ratio).roundToInt().coerceAtLeast(1),
                (info.size.height * ratio).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }
    }

    fun read(context: Context, input: Input): Bitmap {
        if (!input.detail) return preview(context, input.uri, 640)
        val resolver = context.contentResolver
        val orientation = resolver.openInputStream(input.uri)?.use {
            runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        } ?: 1
        val bitmap = requireNotNull(resolver.openFileDescriptor(input.uri, "r")).use { descriptor ->
            @Suppress("DEPRECATION")
            val decoder = requireNotNull(BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)) { "Imagem incompatível com recorte." }
            try {
                val p = PhotoRegion.patch(decoder.width, decoder.height, 256, input.x, input.y, orientation)
                val settings = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
                }
                requireNotNull(decoder.decodeRegion(Rect(p.left, p.top, p.left + p.size, p.top + p.size), settings))
            } finally { decoder.recycle() }
        }
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    fun encode(context: Context, source: Uri, bitmap: Bitmap, file: File, description: String) {
        val original = context.contentResolver.openInputStream(source)?.use { runCatching { ExifInterface(it) }.getOrNull() }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it)) }
        val exif = ExifInterface(file)
        val tags = listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_ISO_SPEED_RATINGS)
        tags.forEach { tag -> original?.getAttribute(tag)?.let { exif.setAttribute(tag, it) } }
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "HorizonCam 2.0")
        // EXIF ImageDescription is ASCII. Explicitly normalize Portuguese text so
        // other readers never get replacement characters in the processing description.
        val asciiDescription = java.text.Normalizer.normalize(description.replace("·", "|").replace("×", "x").replace("→", "->"),
            java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").filter { it.code in 32..126 }
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, asciiDescription)
        exif.saveAttributes()
    }

    fun copyAndPublish(context: Context, jpeg: File, uri: Uri) {
        val resolver = context.contentResolver
        requireNotNull(resolver.openOutputStream(uri, "wt")).use { out -> jpeg.inputStream().use { it.copyTo(out) } }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
    }

    fun save(context: Context, source: Uri, bitmap: Bitmap, prefix: String, description: String): Uri {
        val resolver = context.contentResolver
        val jpeg = File.createTempFile("hc-render-", ".jpg", context.cacheDir)
        try {
            encode(context, source, bitmap, jpeg, description)
            val values = CaptureLibrary.values(true).apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${prefix}_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
            try { copyAndPublish(context, jpeg, uri); return uri }
            catch (e: Throwable) { runCatching { resolver.delete(uri, null, null) }; throw e }
        } finally { jpeg.delete() }
    }
}
