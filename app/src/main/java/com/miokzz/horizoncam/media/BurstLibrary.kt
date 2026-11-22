package com.miokzz.horizoncam.media

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import com.miokzz.horizoncam.photo.BurstCapture
import kotlin.math.roundToInt

object BurstLibrary {
    data class Pixels(val width: Int, val height: Int, val data: IntArray, val exposureTimeNs: Long?, val iso: Int?)
    fun decode(file: BurstCapture.FrameFile, maxEdge: Int): Pixels {
        val exif = ExifInterface(file.file)
        val seconds = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
        val iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
        val source = ImageDecoder.createSource(file.file)
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }
        try {
            val data = IntArray(bmp.width * bmp.height)
            bmp.getPixels(data, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return Pixels(bmp.width, bmp.height, data, (seconds * 1e9).toLong().takeIf { it > 0 }, iso.takeIf { it > 0 })
        } finally { bmp.recycle() }
    }

}
