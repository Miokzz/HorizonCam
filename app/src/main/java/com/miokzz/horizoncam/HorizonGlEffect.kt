package com.miokzz.horizoncam

import android.os.Handler
import android.os.HandlerThread
import androidx.camera.core.CameraEffect
import androidx.core.util.Consumer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class HorizonGlEffect private constructor(
    val processor: HorizonSurfaceProcessor,
    private val thread: HandlerThread,
    private val handler: Handler,
    executor: Executor,
    errorListener: Consumer<Throwable>
) : CameraEffect(
    PREVIEW or VIDEO_CAPTURE,
    executor,
    processor,
    errorListener
), AutoCloseable {

    private val closeRequested = AtomicBoolean(false)

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        processor.release {
            thread.quitSafely()
        }
    }

    companion object {
        fun create(
            state: HorizonState,
            nativeEisActive: () -> Boolean?,
            onError: (Throwable) -> Unit
        ): HorizonGlEffect {
            val thread = HandlerThread("HorizonCam-GL").apply { start() }
            val handler = Handler(thread.looper)
            val executor = Executor { command -> handler.post(command) }
            val processor = HorizonSurfaceProcessor(state, handler, executor, nativeEisActive, onError)
            return HorizonGlEffect(
                processor,
                thread,
                handler,
                executor,
                Consumer { onError(it) }
            )
        }
    }
}
