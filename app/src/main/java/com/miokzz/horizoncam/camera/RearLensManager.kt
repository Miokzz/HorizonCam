package com.miokzz.horizoncam.camera

import android.content.Context
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
import kotlin.math.atan

data class LensOption(
    val key: String,
    val cameraId: String,
    val physicalCameraId: String?,
    val relativeZoom: Float,
    val minCameraZoom: Float,
    val maxCameraZoom: Float,
    val label: String,
    val selector: CameraSelector,
    val approximateHorizontalFovDegrees: Double?
) {
    val initialCameraZoom: Float get() = 1f.coerceIn(minCameraZoom, maxCameraZoom)
    val isPhysicalOnly: Boolean get() = physicalCameraId != null
    val zoomSpec: LensZoomSpec get() = LensZoomSpec(key, relativeZoom, minCameraZoom,
        maxCameraZoom, isPhysicalOnly)
}

data class LensDiagnostics(
    val selectedKey: String?,
    val selectedCameraId: String?,
    val selectedPhysicalCameraId: String?,
    /** The logical capture result's reported active physical ID, NOT inferred output routing. */
    val activePhysicalCameraId: String?,
    val rejection: String?
)

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
    private val rejected = mutableMapOf<String, String>()
    private val runtimeSpecs = mutableMapOf<String, LensZoomSpec>()
    @Volatile private var selected: LensOption? = null

    fun option(key: String): LensOption? = options.firstOrNull { it.key == key }

    @Synchronized fun isRejected(key: String): Boolean = rejected.containsKey(key)

    @Synchronized fun route(
        requested: Float,
        currentKey: String,
        minZoom: Float,
        maxZoom: Float,
        recording: Boolean,
        preferredKey: String? = null
    ): ZoomRoute {
        option(currentKey)?.takeUnless { it.isPhysicalOnly }?.let { current ->
            if (minZoom.isFinite() && maxZoom.isFinite() && minZoom > 0f && maxZoom >= minZoom) {
                runtimeSpecs[currentKey] = current.zoomSpec.copy(minCameraZoom = minZoom,
                    maxCameraZoom = maxZoom)
            }
        }
        return ZoomRouter.route(requested, currentKey, defaultOption.key,
            options.map { runtimeSpecs[it.key] ?: it.zoomSpec }, recording,
            rejected.keys.toSet(), preferredKey)
    }

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
    @Synchronized fun onBindRejected(option: LensOption, reason: String) {
        if (option.key != defaultOption.key) rejected[option.key] = reason.take(240)
        selected = null
        evidence.reset()
    }

    /** Call before every unbind and on lifecycle stop; attach will start a fresh epoch. */
    fun invalidateCaptureDiagnostics() {
        selected = null
        evidence.reset()
    }

    /**
     * Attach BEFORE previewBuilder.build(), in BOTH photo and video binding paths.
     * Callback reads metadata only and never controls the Camera2 session. Safe to poll
     * diagnostics from main; stale generations, missing IDs, failures and closed sessions clear ID.
     */
    fun attachCaptureDiagnostics(previewBuilder: Preview.Builder, option: LensOption) {
        selected = null
        val token = evidence.reset()
        Camera2Interop.Extender(previewBuilder)
            .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest,
                    result: TotalCaptureResult) {
                    if (session.device.id != option.cameraId) return
                    val reported = runCatching {
                        result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                    }.getOrNull()
                    evidence.record(token, result.frameNumber, reported, session)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest,
                    failure: CaptureFailure) { evidence.clear(token, session) }

                override fun onCaptureSequenceAborted(session: CameraCaptureSession,
                    sequenceId: Int) { evidence.clear(token, session) }
            })
            .setSessionStateCallback(object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { evidence.beginSession(token, session) }
                override fun onConfigureFailed(session: CameraCaptureSession) { evidence.endSession(token, session) }
                override fun onClosed(session: CameraCaptureSession) { evidence.endSession(token, session) }
            })
    }

    @Synchronized fun diagnostics(): LensDiagnostics {
        val bound = selected
        return LensDiagnostics(bound?.key, bound?.cameraId, bound?.physicalCameraId,
            evidence.activePhysicalId(), rejected.entries.lastOrNull()?.let { "${it.key}: ${it.value}" })
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
                discovered += LensOption("camera:$id", id, null, ratio, min, max,
                    label(ratio, id == primaryId, false, id), selector(id), degrees)
                // CameraManager includes public metadata for non-standalone physical IDs.
                val physicalIds = runCatching { manager.getCameraCharacteristics(id).physicalCameraIds }
                    .getOrDefault(emptySet())
                for (physicalId in physicalIds.sorted()) {
                    val physicalFov = horizontalFov(manager, physicalId)
                    val relative = LensGeometry.relativeZoom(primaryFov, physicalFov) ?: continue
                    // Unknown FOV is not enough to manufacture an ultrawide/tele option.
                    val physicalSelector = runCatching { selector(id, physicalId) }.getOrNull() ?: continue
                    discovered += LensOption("physical:$id/$physicalId", id, physicalId, relative,
                        1f, 1f, label(relative, false, true, physicalId), physicalSelector, physicalFov)
                }
            }
            val default = discovered.firstOrNull { it.cameraId == primaryId && !it.isPhysicalOnly }
                ?: LensOption("camera:$primaryId", primaryId, null, 1f, 1f, 1f,
                    "Principal · automática", selector(primaryId), primaryFov).also { discovered += it }
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
            val width = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?.width?.toDouble()?.takeIf { it > 0.0 } ?: return@runCatching null
            Math.toDegrees(2.0 * atan(width / (2.0 * focal)))
        }.getOrNull()

        private fun label(ratio: Float, primary: Boolean, physical: Boolean, id: String): String {
            val kind = when { primary -> "Principal · automática"; ratio < 0.85f -> "Ultra-wide"
                ratio > 1.2f -> "Tele"; else -> "Principal" }
            val scale = String.format(Locale.US, "≈%.2f×", ratio)
            return "$kind · $scale · ${if (physical) "física " else "ID "}$id"
        }
    }
}
