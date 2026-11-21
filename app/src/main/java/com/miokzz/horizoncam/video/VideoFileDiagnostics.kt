package com.miokzz.horizoncam.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

data class VideoFileEvidence(
    val encodedWidth: Int?,
    val encodedHeight: Int?,
    val rotationDegrees: Int,
    val displayWidth: Int?,
    val displayHeight: Int?,
    val metadataFrameRate: Double?,
    val cadence: VideoCadence,
)

/** Read-only bounded MP4 inspection. Call on a worker after VideoRecordEvent.Finalize. */
object VideoFileDiagnostics {
    fun inspect(context: Context, uri: Uri, maxSamples: Int = 3000): VideoFileEvidence {
        require(maxSamples in 2..10000)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val videoTrack =
                (0 until extractor.trackCount).firstOrNull {
                    extractor
                        .getTrackFormat(it)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                } ?: error("Arquivo sem trilha de vídeo.")
            val format = extractor.getTrackFormat(videoTrack)
            extractor.selectTrack(videoTrack)
            val timestamps = ArrayList<Long>(maxSamples)
            while (timestamps.size < maxSamples && extractor.sampleTime >= 0) {
                timestamps.add(extractor.sampleTime)
                if (!extractor.advance()) break
            }
            val limited = timestamps.size == maxSamples && extractor.sampleTime >= 0
            fun integer(key: String): Int? =
                if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrNull()
                else null
            val width = integer(MediaFormat.KEY_WIDTH)
            val height = integer(MediaFormat.KEY_HEIGHT)
            val rotation = integer(MediaFormat.KEY_ROTATION) ?: 0
            val transpose = ((rotation % 180) + 180) % 180 != 0
            val frameRate =
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                    runCatching { format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble() }
                        .getOrNull()
                else null
            return VideoFileEvidence(
                width,
                height,
                rotation,
                if (transpose) height else width,
                if (transpose) width else height,
                frameRate,
                VideoCadence.measure(timestamps, limited),
            )
        } finally {
            extractor.release()
        }
    }
}
