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
    val uncorrectedActiveArray: Rect? = null
) {
    val initialCameraZoom: Float get() = 1f.coerceIn(minCameraZoom, maxCameraZoom)
    val isPhysicalOnly: Boolean get() = physicalCameraId != null
    val zoomSpec: LensZoomSpec get() = LensZoomSpec(key, relativeZoom, minCameraZoom,
        maxCameraZoom, isPhysicalOnly, isStandaloneSensor)
}

data class CaptureTelemetry(
    val atMs: Long, val frame: Long, val afState: Int?, val aeState: Int?,
    val focalLength: Float?, val focusDistance: Float?, val crop: String?, val zoomRatio: Float?,
    val sourceCameraId: String, val physicalResult: Boolean, val videoStabilizationMode: Int?,
    val physicalCropZoom: Float?
)

data class LensDiagnostics(
    val selectedKey: String?,
    val selectedCameraId: String?,
    val selectedPhysicalCameraId: String?,
    /** The logical capture result's reported active physical ID, NOT inferred output routing. */
    val activePhysicalCameraId: String?,
    val rejection: String?,
    val capture: CaptureTelemetry? = null,
    val standaloneSensor: Boolean = false
) {
    /** The logical active ID describes the logical output, not a pinned physical output. */
    val outputPhysicalCameraId: String?
        get() = if (standaloneSensor) capture?.sourceCameraId
            else if (selectedPhysicalCameraId == null) activePhysicalCameraId
            else capture?.takeIf { it.physicalResult && it.sourceCameraId == selectedPhysicalCameraId }?.sourceCameraId
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
    val options: List<LensOption>
) {
    private val evidence = CaptureEvidence(SystemClock::elapsedRealtime)
    private val rejected = mutableMapOf<String, MutableMap<String, String>>()
    private val runtimeSpecs = mutableMapOf<String, LensZoomSpec>()
    @Volatile private var selected: LensOption? = null
    @Volatile private var telemetry: CaptureTelemetry? = null
    @Volatile private var telemetryToken = 0L

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
        selected = null
        telemetry = null
        evidence.reset().also { telemetryToken = it }
    }

    /** Call before every unbind and on lifecycle stop; attach will start a fresh epoch. */
    fun invalidateCaptureDiagnostics() {
        selected = null
        telemetry = null
        evidence.reset().also { telemetryToken = it }
    }

    /**
     * Attach BEFORE previewBuilder.build(), in BOTH photo and video binding paths.
     * Callback reads metadata only and never controls the Camera2 session. Safe to poll
     * diagnostics from main; stale generations, missing IDs, failures and closed sessions clear ID.
     */
    fun attachCaptureDiagnostics(previewBuilder: Preview.Builder, option: LensOption,
        onNativeVideoStabilization: (Boolean) -> Unit = {}) {
        selected = null
        telemetry = null
        val token = evidence.reset().also { telemetryToken = it }
        Camera2Interop.Extender(previewBuilder).apply { option.physicalCameraId?.let(::setPhysicalCameraId) }
            .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest,
                    result: TotalCaptureResult) {
                    if (session.device.id != option.cameraId) return
                    val reported = runCatching {
                        result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                    }.getOrNull()
                    evidence.record(token, result.frameNumber, reported, session)
                    val physical = option.physicalCameraId?.let { result.physicalCameraResults[it] }
                    val measurement = physical ?: result
                    if (telemetryToken == token && selected?.key == option.key) {
                        val nativeMode = measurement.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                            ?: result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                        onNativeVideoStabilization(listOfNotNull(nativeMode, result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE))
                            .any { it != CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_OFF })
                        val crop = measurement.get(CaptureResult.SCALER_CROP_REGION)
                        val active = if (physical != null && measurement.get(CaptureResult.DISTORTION_CORRECTION_MODE) == CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
                            option.uncorrectedActiveArray ?: option.activeArray else option.activeArray
                        val physicalZoom = if (physical != null && crop != null && active != null &&
                            !crop.isEmpty && active.contains(crop)) {
                            minOf(active.width().toFloat() / crop.width(), active.height().toFloat() / crop.height())
                        } else null
                        telemetry = CaptureTelemetry(SystemClock.elapsedRealtime(), result.frameNumber,
                            measurement.get(CaptureResult.CONTROL_AF_STATE), measurement.get(CaptureResult.CONTROL_AE_STATE),
                            measurement.get(CaptureResult.LENS_FOCAL_LENGTH), measurement.get(CaptureResult.LENS_FOCUS_DISTANCE),
                            crop?.toShortString(),
                            if (android.os.Build.VERSION.SDK_INT >= 30) measurement.get(CaptureResult.CONTROL_ZOOM_RATIO) else null,
                            if (physical != null) option.physicalCameraId!! else option.cameraId, physical != null, nativeMode, physicalZoom)
                    }
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest,
                    failure: CaptureFailure) { evidence.clear(token, session); if (telemetryToken == token) telemetry = null }

                override fun onCaptureSequenceAborted(session: CameraCaptureSession,
                    sequenceId: Int) { evidence.clear(token, session); if (telemetryToken == token) telemetry = null }
            })
            .setSessionStateCallback(object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { evidence.beginSession(token, session) }
                override fun onConfigureFailed(session: CameraCaptureSession) { evidence.endSession(token, session); if (telemetryToken == token) telemetry = null }
                override fun onClosed(session: CameraCaptureSession) { evidence.endSession(token, session); if (telemetryToken == token) telemetry = null }
            })
    }

    @Synchronized fun diagnostics(profile: String): LensDiagnostics {
        val bound = selected
        return LensDiagnostics(bound?.key, bound?.cameraId, bound?.physicalCameraId,
            evidence.activePhysicalId(), rejected[profile]?.entries?.lastOrNull()?.let { "${it.key}: ${it.value}" },
            telemetry?.takeIf { SystemClock.elapsedRealtime() - it.atMs in 0..1500 }, bound?.isStandaloneSensor == true)
    }

    companion object {
        fun discover(context: Context, provider: ProcessCameraProvider): RearLensManager {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val primary = provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
            val primaryId = Camera2CameraInfo.from(primary).cameraId
            val primaryFov = horizontalFov(manager, primaryId)
            val rear = provider.availableCameraInfos.filter { info ->
                runCatching { Camera2CameraInfo.from(info).getCameraCharacteristic(
                    CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }.getOrDefault(false)
            }
            val primaryPhysicalIds = runCatching { manager.getCameraCharacteristics(primaryId).physicalCameraIds }
                .getOrDefault(emptySet())
            val discovered = mutableListOf<LensOption>()
            for (info in rear) {
                val c2 = Camera2CameraInfo.from(info)
                val id = c2.cameraId
                val degrees = horizontalFov(manager, id)
                val ratio = if (id == primaryId) 1f else {
                    LensGeometry.relativeZoom(primaryFov, degrees)
                        ?: runCatching { info.intrinsicZoomRatio }.getOrNull()
                        ?.takeIf { it.isFinite() && it > 0f && it != 1f }
                        ?: 1f
                }
                val zoom = runCatching { info.zoomState.value }.getOrNull()
                val min = zoom?.minZoomRatio ?: 1f
                val max = zoom?.maxZoomRatio ?: 1f
                // CameraManager includes public metadata for non-standalone physical IDs.
                val physicalIds = runCatching { manager.getCameraCharacteristics(id).physicalCameraIds }
                    .getOrDefault(emptySet())
                discovered += LensOption("camera:$id", id, null, ratio, min, max,
                    label(ratio, id == primaryId, false, id), selector(id), degrees,
                    supportsAf(manager, id), isStandaloneSensor = id in primaryPhysicalIds && physicalIds.isEmpty())
                for (physicalId in physicalIds.sorted()) {
                    val physicalFov = horizontalFov(manager, physicalId)
                    val relative = LensGeometry.relativeZoom(primaryFov, physicalFov) ?: continue
                    // Unknown FOV is not enough to manufacture an ultrawide/tele option.
                    val physicalSelector = runCatching { selector(id, physicalId) }.getOrNull() ?: continue
                    val physicalChars = runCatching { manager.getCameraCharacteristics(physicalId) }.getOrNull()
                    discovered += LensOption("physical:$id/$physicalId", id, physicalId, relative,
                        1f, 1f, label(relative, false, true, physicalId), physicalSelector, physicalFov,
                        supportsAf(manager, physicalId), activeArray = physicalChars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                        uncorrectedActiveArray = physicalChars?.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE))
                }
            }
            val default = discovered.firstOrNull { it.cameraId == primaryId && !it.isPhysicalOnly }
                ?: LensOption("camera:$primaryId", primaryId, null, 1f, 1f, 1f,
                    "Principal · automática", selector(primaryId), primaryFov, supportsAf(manager, primaryId)).also { discovered += it }
            return RearLensManager(default, discovered.distinctBy { it.key }
                .sortedWith(compareBy<LensOption> { it.key != default.key }.thenBy { it.relativeZoom }))
        }

        private fun selector(logicalId: String, physicalId: String? = null): CameraSelector {
            val builder = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter(CameraFilter { cameras -> cameras.filter {
                    runCatching { Camera2CameraInfo.from(it).cameraId == logicalId }.getOrDefault(false)
                } })
            if (physicalId != null) builder.setPhysicalCameraId(physicalId)
            return builder.build()
        }

        private fun horizontalFov(manager: CameraManager, id: String): Double? = runCatching {
            val chars = manager.getCameraCharacteristics(id)
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull()?.takeIf { it > 0f }?.toDouble() ?: return@runCatching null
            val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@runCatching null
            val pixels = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: pixels?.let { Rect(0, 0, it.width, it.height) }
            val width = if (pixels != null && active != null && pixels.width > 0)
                sensor.width * active.width().toDouble() / pixels.width else sensor.width.toDouble()
            val height = if (pixels != null && active != null && pixels.height > 0)
                sensor.height * active.height().toDouble() / pixels.height else sensor.height.toDouble()
            LensGeometry.stillHorizontalFov(focal, width, height)
        }.getOrNull()

        private fun supportsAf(manager: CameraManager, id: String): Boolean? = runCatching {
            val chars = manager.getCameraCharacteristics(id)
            val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return@runCatching null
            modes.any { it == CaptureRequest.CONTROL_AF_MODE_AUTO || it == CaptureRequest.CONTROL_AF_MODE_MACRO ||
                it == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE || it == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO }
        }.getOrNull()

        private fun label(ratio: Float, primary: Boolean, physical: Boolean, id: String): String {
            val kind = when { primary -> "Principal · automática"; ratio < 0.85f -> "Ultra-wide"
                ratio > 1.2f -> "Tele"; else -> "Principal" }
            val scale = String.format(Locale.US, "≈%.2f×", ratio)
            return "$kind · $scale · ${if (physical) "física " else "ID "}$id"
        }
    }
}
