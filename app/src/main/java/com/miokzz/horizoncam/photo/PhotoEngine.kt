package com.miokzz.horizoncam.photo

import android.content.ContentResolver
import android.content.ContentValues
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
import java.util.concurrent.Executor

class PhotoEngine(
    rotation: Int,
    portrait: Boolean,
    previewBuilder: Preview.Builder,
    surface: Preview.SurfaceProvider,
    val settings: AdvancedPhotoSettings = AdvancedPhotoSettings(),
    capabilities: AdvancedPhotoCapabilities? = null,
    extensionActive: Boolean = false
) {
    init {
        if (capabilities != null) capabilities.validate(settings, extensionActive)
        else require(settings == AdvancedPhotoSettings()) { "Verifique as capacidades antes de ativar opções avançadas." }
        require(!extensionActive || (settings.format == PhotoFormat.JPEG && !settings.highResolution && settings.manual.isAuto)) {
            "HDR requer JPEG padrão com controles automáticos."
        }
    }

    private val selector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        .setAllowedResolutionMode(if (settings.highResolution)
            ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
            else ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
        .apply {
            if (settings.highResolution) {
                val allowed = AdvancedPhotoCapabilities.largerHighResolutionSizes(
                    capabilities!!.normalJpegSizes, capabilities.highResolutionJpegSizes).toSet()
                // Do not silently select a normal size while displaying "high resolution".
                // A device-specific unsupported combination must fail bind and let UX fall back.
                setResolutionFilter { sizes, _ -> sizes.filter { PhotoSize(it.width,it.height) in allowed } }
            }
        }
        .build()
    val capture: ImageCapture = ImageCapture.Builder()
        .setTargetRotation(rotation)
        .setResolutionSelector(selector)
        .setOutputFormat(if (settings.format == PhotoFormat.RAW_DNG) ImageCapture.OUTPUT_FORMAT_RAW else ImageCapture.OUTPUT_FORMAT_JPEG)
        .setCaptureMode(if (settings.highResolution) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setJpegQuality(if (settings.highResolution) 100 else 95)
        .build()
    private val preview = previewBuilder.setTargetRotation(rotation).build().apply { setSurfaceProvider(surface) }
    // DNG contains the full sensor array; cropping raw mosaic data would corrupt
    // its metadata. Match preview viewport to a declared native RAW aspect.
    private val rawSize = if (settings.format == PhotoFormat.RAW_DNG) capabilities?.rawSizes?.maxByOrNull { it.pixels } else null
    private val aspect = rawSize?.let {
        val landscapeWidth = maxOf(it.width,it.height)
        val landscapeHeight = minOf(it.width,it.height)
        if (portrait) Rational(landscapeHeight,landscapeWidth) else Rational(landscapeWidth,landscapeHeight)
    } ?: if (portrait) Rational(3,4) else Rational(4,3)
    val group: UseCaseGroup = UseCaseGroup.Builder()
        .setViewPort(ViewPort.Builder(aspect, rotation).setScaleType(ViewPort.FILL_CENTER).build())
        .addUseCase(preview)
        .addUseCase(capture)
        .build()

    /** CameraX's OUTPUT_FORMAT_RAW disk path uses DngCreator + actual capture metadata. */
    fun shoot(resolver: ContentResolver, executor: Executor, onResult: (Result<Uri>) -> Unit) {
        val output = ImageCapture.OutputFileOptions.Builder(resolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, outputValues(settings.format)).build()
        capture.takePicture(output, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                onResult(runCatching { requireNotNull(result.savedUri) { "Foto sem URI de saída" } })
            }
            override fun onError(exception: ImageCaptureException) = onResult(Result.failure(exception))
        })
    }

    companion object {
        fun outputValues(format: PhotoFormat): ContentValues = ContentValues().apply {
            val spec = PhotoOutputSpec.forFormat(format)
            put(MediaStore.MediaColumns.DISPLAY_NAME, "HC_${System.currentTimeMillis()}.${spec.extension}")
            put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, spec.relativePath)
        }
    }
}
