package com.miokzz.horizoncam

import android.os.Handler
import android.os.HandlerThread
import androidx.camera.core.CameraEffect
import androidx.core.util.Consumer
import java.util.concurrent.Executor

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

    override fun close() {
        processor.release {
            thread.quitSafely()
        }
    }

    companion object {
        fun create(
            state: HorizonState,
            onError: (Throwable) -> Unit
        ): HorizonGlEffect {
            val thread = HandlerThread("HorizonCam-GL").apply { start() }
            val handler = Handler(thread.looper)
            val executor = Executor { command -> handler.post(command) }
            val processor = HorizonSurfaceProcessor(state, handler, executor)
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
