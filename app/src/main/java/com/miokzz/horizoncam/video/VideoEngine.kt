package com.miokzz.horizoncam.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.video.FallbackStrategy
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

class VideoEngine(
    state: HorizonState,
    previewBuilder: Preview.Builder,
    surface: Preview.SurfaceProvider,
    rotation: Int,
    aspect: Rational,
    quality: Quality,
    fps: Int,
    val nativeEis: Boolean,
    onRenderError: (Throwable) -> Unit
) : AutoCloseable {
    private val preview = previewBuilder.setTargetRotation(rotation)
        .apply { if (nativeEis) setPreviewStabilizationEnabled(true) }
        .build().apply { setSurfaceProvider(surface) }
    private val recorder = Recorder.Builder().setQualitySelector(
        QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality))
    ).build()
    val capture = VideoCapture.Builder(recorder)
        .setTargetRotation(rotation)
        .setTargetFrameRate(Range(fps, fps))
        .apply { if (nativeEis) setVideoStabilizationEnabled(true) }
        .build()
    val effect = if (state.isEnabled()) HorizonGlEffect.create(state, onRenderError) else null
    val group: UseCaseGroup = UseCaseGroup.Builder()
        .addUseCase(preview)
        .addUseCase(capture)
        .setViewPort(ViewPort.Builder(aspect, rotation).setScaleType(ViewPort.FILL_CENTER).build())
        .apply { effect?.let { addEffect(it) } }
        .build()

    fun start(context: Context, audio: Boolean, onEvent: (VideoRecordEvent) -> Unit): Recording {
        val output = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(CaptureLibrary.values(photo = false)).build()
        val pending = recorder.prepareRecording(context, output)
        if (audio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending.withAudioEnabled()
        }
        return pending.start(ContextCompat.getMainExecutor(context), onEvent)
    }

    override fun close() { effect?.close() }
}
