package com.miokzz.horizoncam.photo

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import android.util.Rational
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.miokzz.horizoncam.media.CaptureLibrary
import java.util.concurrent.Executor

class PhotoEngine(rotation: Int, portrait: Boolean, previewBuilder: Preview.Builder, surface: Preview.SurfaceProvider) {
    private val selector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        .build()
    val capture = ImageCapture.Builder()
        .setTargetRotation(rotation)
        .setResolutionSelector(selector)
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setJpegQuality(95)
        .build()
    private val preview = previewBuilder.setTargetRotation(rotation).build().apply { setSurfaceProvider(surface) }
    private val aspect = if (portrait) Rational(3, 4) else Rational(4, 3)
    val group: UseCaseGroup = UseCaseGroup.Builder()
        .setViewPort(ViewPort.Builder(aspect, rotation).setScaleType(ViewPort.FILL_CENTER).build())
        .addUseCase(preview)
        .addUseCase(capture)
        .build()

    fun shoot(resolver: ContentResolver, executor: Executor, onResult: (Result<Uri>) -> Unit) {
        val options = ImageCapture.OutputFileOptions.Builder(
            resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, CaptureLibrary.values(photo = true)
        ).build()
        capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onResult(runCatching { requireNotNull(output.savedUri) { "JPEG sem URI de saída" } })
            }

            override fun onError(exception: ImageCaptureException) = onResult(Result.failure(exception))
        })
    }
}
