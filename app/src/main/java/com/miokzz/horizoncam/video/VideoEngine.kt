package com.miokzz.horizoncam.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.ViewPort
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.miokzz.horizoncam.HorizonGlEffect
import com.miokzz.horizoncam.HorizonState
import com.miokzz.horizoncam.media.CaptureLibrary

/** Requested and negotiated configuration, not proof of saved-file cadence or HAL EIS activity. */
data class VideoSessionDiagnostics(
    val requestedQuality: String,
    val selectedQuality: String?,
    val requestedFps: Int,
    val configuredFpsRange: VideoFpsRange?,
    val frameRateVerificationNote: String,
    val bufferWidth: Int?,
    val bufferHeight: Int?,
    val expectedEncodedWidth: Int?,
    val expectedEncodedHeight: Int?,
    val nativeEisRequested: Boolean,
)

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class VideoEngine(
    state: HorizonState,
    previewBuilder: Preview.Builder,
    surface: Preview.SurfaceProvider,
    rotation: Int,
    aspect: Rational,
    private val quality: Quality,
    private val fps: Int,
    val nativeEis: Boolean,
    cameraInfo: CameraInfo,
    physicalCameraId: String? = null,
    nativeEisActive: () -> Boolean = { false },
    onRenderError: (Throwable) -> Unit,
) : AutoCloseable {
    init {
        require(quality in listOf(Quality.FHD, Quality.UHD, Quality.HD, Quality.SD))
        require(fps == 30 || fps == 60)
        require(!nativeEis || (quality == Quality.FHD && fps == 30 && !state.isEnabled())) {
            "A estabilização nativa é oferecida somente em FHD/30, sem o efeito Horizon Lock."
        }
    }

    private val preview =
        previewBuilder
            .setTargetRotation(rotation)
            .setDynamicRange(DynamicRange.SDR)
            .setPreviewStabilizationEnabled(nativeEis)
            .build()
            .apply { setSurfaceProvider(surface) }
    // The explicit FHD -> HD -> SD compatibility ladder preserves cameras with
    // no FHD profile. UI diagnostics must show the actual selected quality.
    private val recorder =
        Recorder.Builder()
            .setQualitySelector(
                if (quality == Quality.FHD)
                    QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD))
                else QualitySelector.from(quality)
            )
            .build()
    val capture =
        VideoCapture.Builder(recorder)
            .setTargetRotation(rotation)
            .setDynamicRange(DynamicRange.SDR)
            .setVideoStabilizationEnabled(nativeEis)
            .apply { physicalCameraId?.let { Camera2Interop.Extender(this).setPhysicalCameraId(it) } }
            .build()
    val effect = if (state.isEnabled()) HorizonGlEffect.create(state, nativeEisActive, onRenderError) else null
    private val viewport =
        ViewPort.Builder(aspect, rotation).setScaleType(ViewPort.FILL_CENTER).build()

    private fun sessionBuilder() =
        SessionConfig.Builder(preview, capture).setViewPort(viewport).apply {
            effect?.let { addEffect(it) }
        }

    val supportedFrameRateRanges: List<VideoFpsRange>
    val configuredFpsRange: VideoFpsRange?
    val frameRateVerificationNote: String
    val sessionConfig: SessionConfig

    init {
        try {
            val info = cameraInfo
            require(
                !nativeEis ||
                    (Preview.getPreviewCapabilities(info).isStabilizationSupported &&
                        Recorder.getVideoCapabilities(info).isStabilizationSupported)
            ) {
                "Estabilização nativa indisponível nesta câmera."
            }
            // Includes this exact quality, viewport, stabilization and GL effect.
            val rangeQuery = runCatching {
                info.getSupportedFrameRateRanges(sessionBuilder().build())
            }
            val supported =
                rangeQuery.getOrDefault(emptySet()).map { VideoFpsRange(it.lower, it.upper) }
            supportedFrameRateRanges = supported
            configuredFpsRange = VideoRatePolicy.negotiateRange(supported, fps)
            require(fps != 60 || configuredFpsRange != null) {
                "${label(quality)}/60 não foi declarado para esta combinação de prévia e gravação."
            }
            frameRateVerificationNote =
                if (configuredFpsRange != null)
                    "Faixa declarada pelo CameraX; confirme a cadência no arquivo."
                else
                    "Cadência padrão não verificada: CameraX não informou uma faixa aplicável a 30 fps."
            // Empty/unsupported public queries must not break established 30fps
            // recording. Keep this regular session unconstrained and unverified.
            sessionConfig =
                sessionBuilder()
                    .apply {
                        configuredFpsRange?.let { setFrameRateRange(Range(it.lower, it.upper)) }
                    }
                    .build()
        } catch (error: Throwable) {
            effect?.close()
            throw error
        }
    }

    fun diagnostics(): VideoSessionDiagnostics {
        val resolution = capture.resolutionInfo
        val crop = resolution?.cropRect
        val transpose = resolution?.rotationDegrees?.let { it % 180 != 0 } ?: false
        return VideoSessionDiagnostics(
            label(quality),
            capture.selectedQuality?.let(::label),
            fps,
            configuredFpsRange,
            frameRateVerificationNote,
            resolution?.resolution?.width,
            resolution?.resolution?.height,
            crop?.let { if (transpose) it.height() else it.width() },
            crop?.let { if (transpose) it.width() else it.height() },
            nativeEis,
        )
    }

    fun start(context: Context, audio: Boolean, onEvent: (VideoRecordEvent) -> Unit): Recording {
        val output =
            MediaStoreOutputOptions.Builder(
                    context.contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                )
                .setContentValues(CaptureLibrary.values(photo = false))
                .build()
        val pending = recorder.prepareRecording(context, output)
        if (
            audio &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        ) {
            pending.withAudioEnabled()
        }
        return pending.start(ContextCompat.getMainExecutor(context), onEvent)
    }

    override fun close() {
        effect?.close()
    }

    companion object {
        private fun label(quality: Quality) =
            when (quality) {
                Quality.UHD -> "UHD"
                Quality.FHD -> "FHD"
                Quality.HD -> "HD"
                Quality.SD -> "SD"
                else -> quality.toString()
            }
    }
}
