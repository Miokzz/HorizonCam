package com.miokzz.horizoncam.photo

import android.content.Context
import androidx.annotation.MainThread
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.miokzz.horizoncam.camera.LensOption
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Optional OEM HDR processing for the JPEG PhotoEngine group (Preview + ImageCapture) only.
 * Does not enable Ultra HDR/gain maps, video HDR, or any CameraEffect. Constructing this helper
 * never initializes extensions; only explicit query() does. All public calls and callbacks use
 * the main thread. The supplied callback executor MUST be a main-thread executor.
 *
 * A supported selector still needs a real bind check with the chosen photo resolution/crop.
 * On rejection call onBindRejected(), then rebuild ordinary JPEG use cases and bind the returned
 * base selector. The Activity owns unbind/rebind and must discard callbacks for a stale lens,
 * capture mode or destroyed lifecycle. Extension support is not proof a JPEG was captured with HDR.
 */
class HdrPhotoExtension(
    context: Context,
    private val provider: ProcessCameraProvider,
    private val callbackExecutor: Executor = ContextCompat.getMainExecutor(context)
) : AutoCloseable {
    data class Availability(val key: String, val supported: Boolean, val reason: String?)
    data class Selection(val key: String, val selector: CameraSelector, val hdrEnabled: Boolean, val reason: String?)
    private data class Request(val option: LensOption, val callback: (Availability) -> Unit)

    private val appContext = context.applicationContext
    private val eligibility = HdrEligibility()
    private val selectors = mutableMapOf<String, CameraSelector>()
    private val waiting = mutableListOf<Request>()
    // Thread is created lazily, on the first explicit non-physical query.
    private val worker by lazy { Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "HorizonCam-HDR") } }
    private var workerStarted = false
    private var initializing = false
    private var manager: ExtensionsManager? = null
    private var initializationFailure: String? = null
    @Volatile private var closed = false

    @MainThread
    fun query(option: LensOption, onResult: (Availability) -> Unit) {
        if (closed) return
        if (option.isPhysicalOnly) {
            val decision = eligibility.decide(option.key, requested = true, physical = true)
            callbackExecutor.execute { if (!closed) onResult(Availability(option.key, false, decision.reason)) }
            return
        }
        val initialized = manager
        if (initialized != null) {
            callbackExecutor.execute { if (!closed) evaluate(initialized, Request(option, onResult)) }
            return
        }
        initializationFailure?.let { reason ->
            callbackExecutor.execute { if (!closed) onResult(Availability(option.key, false, reason)) }
            return
        }
        waiting += Request(option, onResult)
        if (initializing) return
        initializing = true
        workerStarted = true
        worker.execute {
            // CameraX 1.6.1 may perform binder feature discovery while creating this future.
            // Calling the public async API on our worker also keeps that setup off the UI thread.
            try {
                val future = ExtensionsManager.getInstanceAsync(appContext, provider)
                future.addListener({
                    if (!closed) {
                        runCatching { future.get() }.fold(
                            onSuccess = { result ->
                                manager = result
                                initializing = false
                                val pending = waiting.toList()
                                waiting.clear()
                                pending.forEach { if (!closed) evaluate(result, it) }
                            },
                            onFailure = { failedInitialization(it) }
                        )
                    }
                }, callbackExecutor)
            } catch (error: Exception) {
                callbackExecutor.execute { if (!closed) failedInitialization(error) }
            }
        }
    }

    @MainThread
    private fun evaluate(manager: ExtensionsManager, request: Request) {
        val option = request.option
        val result = runCatching {
            if (!manager.isExtensionAvailable(option.selector, ExtensionMode.HDR)) {
                null
            } else {
                manager.getExtensionEnabledCameraSelector(option.selector, ExtensionMode.HDR)
            }
        }
        val selector = result.getOrNull()
        val reason = when {
            result.isFailure -> "Não foi possível consultar HDR: ${brief(result.exceptionOrNull())}"
            selector == null -> "HDR indisponível para esta câmera no dispositivo."
            else -> null
        }
        if (selector != null) selectors[option.key] = selector else selectors.remove(option.key)
        eligibility.record(option.key, selector != null, reason)
        // An asynchronous availability answer must never undo a prior real bind rejection.
        val decision = eligibility.decide(option.key, requested = true, physical = false)
        request.callback(Availability(option.key, decision.enabled, decision.reason))
    }

    @MainThread
    private fun failedInitialization(error: Throwable) {
        val reason = "HDR indisponível: ${brief(error)}"
        initializationFailure = reason
        initializing = false
        val pending = waiting.toList()
        waiting.clear()
        pending.forEach { request ->
            eligibility.record(request.option.key, false, reason)
            if (!closed) request.callback(Availability(request.option.key, false, reason))
        }
    }

    /** Nonblocking. A cold or unsupported query always gives the ordinary JPEG selector. */
    @MainThread
    fun selection(option: LensOption, requested: Boolean): Selection {
        if (closed) return Selection(option.key, option.selector, false, "Consulta HDR encerrada.")
        val decision = eligibility.decide(option.key, requested, option.isPhysicalOnly)
        val selector = selectors[option.key].takeIf { decision.enabled }
        return Selection(option.key, selector ?: option.selector, selector != null, decision.reason)
    }

    /** Returns an explicit JPEG fallback without pretending the rejected HDR bind succeeded. */
    @MainThread
    fun onBindRejected(option: LensOption, reason: String): Selection {
        val explanation = "HDR rejeitado para esta câmera; JPEG normal. ${reason.take(180)}"
        eligibility.reject(option.key, explanation)
        selectors.remove(option.key)
        return Selection(option.key, option.selector, false, explanation)
    }

    @MainThread
    override fun close() {
        closed = true
        waiting.clear()
        selectors.clear()
        if (workerStarted) worker.shutdown()
        // ExtensionsManager is process-scoped. Never cancel its shared future or shut it down here.
    }

    private fun brief(error: Throwable?): String =
        (error?.cause?.message ?: error?.message ?: error?.javaClass?.simpleName ?: "falha de extensão").take(180)
}
