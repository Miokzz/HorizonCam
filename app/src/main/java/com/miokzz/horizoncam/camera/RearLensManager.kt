package com.miokzz.horizoncam.camera

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.Locale

data class LensOption(
    val key: String,
    val cameraId: String,
    val physicalCameraId: String?,
    val relativeZoom: Float,
    val minCameraZoom: Float,
    val maxCameraZoom: Float,
    val label: String,
    val selector: CameraSelector,
    val approximateHorizontalFovDegrees: Double?,
    val autofocus: Boolean?,
    val isStandaloneSensor: Boolean = false,
    val activeArray: Rect? = null,
    val uncorrectedActiveArray: Rect? = null,
    val physicalPhotoOutputs: PhysicalPhotoOutputs? = null,
    val focalLengthsMm: List<Float> = emptyList()
) {
    val initialCameraZoom: Float get() = 1f.coerceIn(minCameraZoom, maxCameraZoom)
    val isPhysicalOnly: Boolean get() = physicalCameraId != null
    val zoomSpec: LensZoomSpec get() = LensZoomSpec(key, relativeZoom, minCameraZoom,
        maxCameraZoom, isPhysicalOnly, isStandaloneSensor)
}

data class CaptureTelemetry(
    val atMs: Long, val frame: Long, val afState: Int?, val aeState: Int?,
    val focalLength: Float?, val focusDistance: Float?, val crop: String?, val zoomRatio: Float?,
    val sourceCameraId: String?, val physicalResult: Boolean, val videoStabilizationMode: Int?,
    val physicalCropZoom: Float?,
    val nativeZoom: NativeZoomObservation?,
    val stillCapture: Boolean,
    val activePhysicalCameraId: String?
)

data class LensDiagnostics(
    val selectedKey: String?,
    val selectedCameraId: String?,
    val selectedPhysicalCameraId: String?,
    /** The logical capture result's reported active physical ID, NOT inferred output routing. */
    val activePhysicalCameraId: String?,
    val rejection: String?,
    val capture: CaptureTelemetry? = null,
    val standaloneSensor: Boolean = false,
    val lastStillCapture: CaptureTelemetry? = null
) {
    /** The logical active ID describes the logical output, not a pinned physical output. */
    val outputPhysicalCameraId: String?
        get() = if (standaloneSensor) capture?.sourceCameraId
            else if (selectedPhysicalCameraId == null) activePhysicalCameraId
            else capture?.takeIf { it.physicalResult && it.sourceCameraId == selectedPhysicalCameraId }?.sourceCameraId

    /** The value recorded by the HAL for a request issued by the current zoom command. */
    fun nativeZoomAfter(requestedRatio: Float, issuedAtMs: Long, afterFrame: Long = -1L): Float? =
        capture?.takeIf { it.atMs >= issuedAtMs && it.frame > afterFrame }?.nativeZoom?.appliedFor(requestedRatio)
}

/**
 * CameraX 1.6.1 public API only. Discovery is a list of candidates; binding the actual
 * preview/photo/video use-case group is the final device-specific compatibility check.
 * Selector-level physical IDs apply to all use cases. They are fixed-FOV choices: CameraX's
 * zoom control remains logical, so physical-stream digital zoom is not assumed calibrated.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class RearLensManager private constructor(
    val defaultOption: LensOption,
    val options: List<LensOption>,
    val publicCameraIds: Set<String>,
    val cameraXCameraIds: Set<String>
) {
    private val evidence = CaptureEvidence(SystemClock::elapsedRealtime)
    private val rejected = mutableMapOf<String, MutableMap<String, String>>()
    private val runtimeSpecs = mutableMapOf<String, LensZoomSpec>()
    private var selected: LensOption? = null
    private var telemetry: CaptureTelemetry? = null
    private var lastStillCapture: CaptureTelemetry? = null

    fun option(key: String): LensOption? = options.firstOrNull { it.key == key }

    @Synchronized fun isRejected(key: String, profile: String): Boolean = key in rejected[profile].orEmpty()

    @Synchronized fun clearRejections(profile: String) { rejected.remove(profile) }

    @Synchronized fun route(
        requested: Float,
        currentKey: String,
        minZoom: Float,
        maxZoom: Float,
        recording: Boolean,
        preferredKey: String? = null,
        stillPhoto: Boolean = false,
        profile: String
    ): ZoomRoute {
        option(currentKey)?.takeUnless { it.isPhysicalOnly }?.let { current ->
            if (minZoom.isFinite() && maxZoom.isFinite() && minZoom > 0f && maxZoom >= minZoom) {
                runtimeSpecs[currentKey] = current.zoomSpec.copy(minCameraZoom = minZoom,
                    maxCameraZoom = maxZoom)
            }
        }
        return ZoomRouter.route(requested, currentKey, defaultOption.key,
            options.map { runtimeSpecs[it.key] ?: it.zoomSpec }, recording,
            rejected[profile].orEmpty().keys, preferredKey, stillPhoto)
    }

    @Synchronized fun horizonLockRoute(currentKey: String, profile: String): ZoomRoute =
        LockLensPolicy.route(options.map { runtimeSpecs[it.key] ?: it.zoomSpec }, defaultOption.key,
            currentKey, rejected[profile].orEmpty().keys)

    /** Call on main after a successful bind. Reading physical CameraInfo zoomState is avoided. */
    @Synchronized fun onBound(option: LensOption, camera: Camera) {
        selected = option
        if (!option.isPhysicalOnly) {
            runCatching { camera.cameraInfo.zoomState.value }.getOrNull()?.let { zoom ->
                runtimeSpecs[option.key] = option.zoomSpec.copy(minCameraZoom = zoom.minZoomRatio,
                    maxCameraZoom = zoom.maxZoomRatio)
            }
        }
    }

    /** Call on a failed bind, then bind defaultOption with fresh builders/use cases. */
    @Synchronized fun onBindRejected(option: LensOption, profile: String, reason: String) {
        if (option.key != defaultOption.key) rejected.getOrPut(profile, ::linkedMapOf)[option.key] = reason.take(240)
        invalidateCaptureDiagnostics()
    }

    /** Call before every unbind and on lifecycle stop; attach will start a fresh epoch. */
    @Synchronized fun invalidateCaptureDiagnostics() {
        selected = null
        clearTelemetry()
        evidence.reset()
    }

    private fun clearTelemetry() {
        telemetry = null
        lastStillCapture = null
    }

    /**
     * Attach BEFORE previewBuilder.build(), in BOTH photo and video binding paths.
     * Callback reads metadata only and never controls the Camera2 session. Safe to poll
     * diagnostics from main; stale generations, missing IDs, failures and closed sessions clear ID.
     */
    @Synchronized fun attachCaptureDiagnostics(previewBuilder: Preview.Builder, option: LensOption,
        onNativeVideoStabilization: (Boolean?) -> Unit = {}) {
        selected = null
        clearTelemetry()
        val token = evidence.reset()
        Camera2Interop.Extender(previewBuilder).apply { option.physicalCameraId?.let(::setPhysicalCameraId) }
            .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest,
                    result: TotalCaptureResult) {
                    if (session.device.id != option.cameraId) return
                    synchronized(this@RearLensManager) {
                        val reported = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                        if (selected?.key != option.key) return
                        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                        if (evidence.beginSession(token, session, timestamp)) clearTelemetry()
                        if (!evidence.record(token, result.frameNumber, reported, session, timestamp)) return
                        val data = measure(option, request, result, reported)
                        if (data.stillCapture) lastStillCapture = data else telemetry = data
                        if (!data.stillCapture) onNativeVideoStabilization(nativeStabilization(
                            data.videoStabilizationMode, result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)))
                    }
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest,
                    failure: CaptureFailure) = clearFrame(session)

                override fun onCaptureSequenceAborted(session: CameraCaptureSession,
                    sequenceId: Int) = clearFrame(session)

                private fun clearFrame(session: CameraCaptureSession) {
                    synchronized(this@RearLensManager) {
                        if (evidence.clear(token, session)) {
                            clearTelemetry()
                            onNativeVideoStabilization(null)
                        }
                    }
                }
            })
    }

    @Suppress("DEPRECATION")
    private fun measure(option: LensOption, request: CaptureRequest, result: TotalCaptureResult,
        activePhysicalCameraId: String?): CaptureTelemetry {
        val physical = option.physicalCameraId?.let { id ->
            if (Build.VERSION.SDK_INT >= 31) result.physicalCameraTotalResults[id] ?: result.physicalCameraResults[id]
            else result.physicalCameraResults[id]
        }?.takeIf { matchesFocalLength(option, it) }
        val measurement = physical ?: result
        val active = if (measurement.get(CaptureResult.DISTORTION_CORRECTION_MODE) == CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            option.uncorrectedActiveArray ?: option.activeArray else option.activeArray
        val crop = measurement.get(CaptureResult.SCALER_CROP_REGION)
        val zoom = if (Build.VERSION.SDK_INT >= 30) measurement.get(CaptureResult.CONTROL_ZOOM_RATIO) else null
        val requested = if (Build.VERSION.SDK_INT >= 30) request.get(CaptureRequest.CONTROL_ZOOM_RATIO) else null
        val requestCrop = request.get(CaptureRequest.SCALER_CROP_REGION)
        val standaloneEvidence = option.isStandaloneSensor && matchesFocalLength(option, result)
        return CaptureTelemetry(
            SystemClock.elapsedRealtime(), result.frameNumber, measurement.get(CaptureResult.CONTROL_AF_STATE),
            measurement.get(CaptureResult.CONTROL_AE_STATE), measurement.get(CaptureResult.LENS_FOCAL_LENGTH),
            measurement.get(CaptureResult.LENS_FOCUS_DISTANCE), crop?.toShortString(), zoom,
            if (physical != null) option.physicalCameraId else option.cameraId.takeIf { standaloneEvidence || !option.isStandaloneSensor },
            physical != null, measurement.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
            if (physical != null) opticalCropZoom(active, crop) else null,
            if (option.isPhysicalOnly) null else NativeZoomObservation(requested, opticalCropZoom(active, requestCrop), zoom,
                opticalCropZoom(active, crop)),
            request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE,
            activePhysicalCameraId?.takeIf { it.isNotBlank() }
        )
    }

    private fun nativeStabilization(measured: Int?, logical: Int?): Boolean? {
        val off = CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        val on = CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_ON
        val preview = CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
        return when {
            measured == on || measured == preview || logical == on || logical == preview -> true
            measured == off || logical == off -> false
            else -> null
        }
    }

    private fun matchesFocalLength(option: LensOption, result: CaptureResult): Boolean {
        val reported = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: return true
        return option.focalLengthsMm.isEmpty() || option.focalLengthsMm.any { expected ->
            kotlin.math.abs(reported - expected) <= 0.05f * expected
        }
    }

    private fun opticalCropZoom(active: Rect?, crop: Rect?): Float? {
        if (crop == null || active == null || crop.isEmpty || !active.contains(crop)) return null
        return minOf(active.width().toFloat() / crop.width(), active.height().toFloat() / crop.height())
    }

    @Synchronized fun diagnostics(profile: String): LensDiagnostics {
        val bound = selected
        val now = SystemClock.elapsedRealtime()
        val preview = telemetry?.takeIf { now - it.atMs in 0..1500 }
        return LensDiagnostics(bound?.key, bound?.cameraId, bound?.physicalCameraId,
            preview?.activePhysicalCameraId, rejected[profile]?.entries?.lastOrNull()?.let { "${it.key}: ${it.value}" },
            preview, bound?.isStandaloneSensor == true, lastStillCapture?.takeIf { now - it.atMs in 0..1500 })
    }

    companion object {
        fun discover(context: Context, provider: ProcessCameraProvider): RearLensManager {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val primary = provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
            val primaryId = Camera2CameraInfo.from(primary).cameraId
            val cameraIds = runCatching { manager.cameraIdList.toSet() }.getOrDefault(emptySet())
            val cameraXInfos = provider.availableCameraInfos.mapNotNull { info ->
                runCatching { Camera2CameraInfo.from(info).cameraId to info }.getOrNull()
            }.toMap()
            val metadata = mutableMapOf<String, CameraCharacteristics?>()
            fun characteristics(id: String) = metadata.getOrPut(id) {
                runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
            }
            val primaryFov = characteristics(primaryId)?.let(::horizontalFov)
            val discovered = mutableListOf<LensOption>()
            for ((id, info) in cameraXInfos + (primaryId to primary)) {
                val chars = characteristics(id)
                if (id != primaryId && (id !in cameraIds ||
                        chars?.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK)) continue
                val degrees = chars?.let(::horizontalFov)
                // An uncharacterized camera should never replace the known 1× baseline.
                val ratio = if (id == primaryId) 1f else LensGeometry.relativeZoom(primaryFov, degrees) ?: continue
                val zoom = runCatching { info.zoomState.value }.getOrNull()
                val min = zoom?.minZoomRatio ?: 1f
                val max = zoom?.maxZoomRatio ?: 1f
                val physicalIds = chars?.physicalCameraIds.orEmpty()
                val capabilities = chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val outputs = chars?.let { runCatching { PhysicalPhotoOutputs.from(it) }.getOrNull() }
                val standalone = id in cameraIds && degrees != null && physicalIds.isEmpty() && capabilities != null &&
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE in capabilities &&
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA !in capabilities &&
                    outputs != null && outputs.preview.isNotEmpty() && outputs.jpeg.isNotEmpty()
                discovered += LensOption("camera:$id", id, null, ratio, min, max,
                    label(ratio, id == primaryId, false, id), selector(id), degrees,
                    supportsAf(chars), isStandaloneSensor = standalone,
                    activeArray = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                    uncorrectedActiveArray = chars?.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE),
                    focalLengthsMm = focalLengths(chars))
                // A physical-only option is eligible only through a public logical parent.
                if (id !in cameraIds) continue
                for (physicalId in physicalIds.sorted()) {
                    val physicalChars = characteristics(physicalId) ?: continue
                    if (physicalChars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                    val physicalFov = horizontalFov(physicalChars)
                    val relative = LensGeometry.relativeZoom(primaryFov, physicalFov) ?: continue
                    discovered += LensOption("physical:$id/$physicalId", id, physicalId, relative,
                        1f, 1f, label(relative, false, true, physicalId), selector(id, physicalId), physicalFov,
                        supportsAf(physicalChars), activeArray = physicalChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                        uncorrectedActiveArray = physicalChars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE),
                        physicalPhotoOutputs = runCatching { PhysicalPhotoOutputs.from(physicalChars) }.getOrNull(),
                        focalLengthsMm = focalLengths(physicalChars))
                }
            }
            val default = discovered.first { it.cameraId == primaryId && !it.isPhysicalOnly }
            return RearLensManager(default, discovered.distinctBy { it.key }
                .sortedWith(compareBy<LensOption> { it.key != default.key }.thenBy { it.relativeZoom }),
                cameraIds, cameraXInfos.keys)
        }

        private fun selector(logicalId: String, physicalId: String? = null): CameraSelector {
            val builder = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter(CameraFilter { cameras -> cameras.filter {
                    runCatching { Camera2CameraInfo.from(it).cameraId == logicalId }.getOrDefault(false)
                } })
            if (physicalId != null) builder.setPhysicalCameraId(physicalId)
            return builder.build()
        }

        private fun horizontalFov(chars: CameraCharacteristics): Double? {
            val focal = focalLengths(chars).firstOrNull()?.toDouble() ?: return null
            val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val pixels = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: pixels?.let { Rect(0, 0, it.width, it.height) }
            val width = if (pixels != null && active != null && pixels.width > 0)
                sensor.width * active.width().toDouble() / pixels.width else sensor.width.toDouble()
            val height = if (pixels != null && active != null && pixels.height > 0)
                sensor.height * active.height().toDouble() / pixels.height else sensor.height.toDouble()
            return LensGeometry.stillHorizontalFov(focal, width, height)
        }

        private fun focalLengths(chars: CameraCharacteristics?): List<Float> =
            chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.filter { it.isFinite() && it > 0f }.orEmpty()

        private fun supportsAf(chars: CameraCharacteristics?): Boolean? {
            val modes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return null
            return modes.any { it == CaptureRequest.CONTROL_AF_MODE_AUTO || it == CaptureRequest.CONTROL_AF_MODE_MACRO ||
                it == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE || it == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO }
        }

        private fun label(ratio: Float, primary: Boolean, physical: Boolean, id: String): String {
            val kind = when { primary -> "Principal · automática"; ratio < 0.85f -> "Ultra-wide"
                ratio > 1.2f -> "Tele"; else -> "Principal" }
            val scale = String.format(Locale.US, "≈%.2f×", ratio)
            return "$kind · $scale · ${if (physical) "física " else "ID "}$id"
        }
    }
}
