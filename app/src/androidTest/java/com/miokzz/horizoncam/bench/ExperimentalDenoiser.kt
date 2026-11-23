package com.miokzz.horizoncam.bench

import android.graphics.Bitmap
import com.miokzz.horizoncam.ai.AiGeometry
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Private opt-in evaluation engine. The caller owns input and returned bitmaps. */
internal class ExperimentalDenoiser(model: File, expectedHash: String, requestGpu: Boolean) : AutoCloseable {
    private val modelBytes = checkedModel(model, expectedHash)
    private val delegate = if (requestGpu) GpuDelegate(GpuDelegateFactory.Options().setPrecisionLossAllowed(false)) else null
    private val engine = try {
        Interpreter(modelBytes, Interpreter.Options().setNumThreads(4).setUseXNNPACK(delegate == null).apply {
            delegate?.let(::addDelegate)
        })
    } catch (failure: Throwable) {
        delegate?.close()
        throw failure
    }

    init {
        try {
            engine.allocateTensors()
            val shape = intArrayOf(1, EDGE, EDGE, 3)
            require(engine.inputTensorCount == 1 && engine.outputTensorCount == 1)
            require(engine.getInputTensor(0).dataType() == DataType.FLOAT32 &&
                engine.getInputTensor(0).shape().contentEquals(shape))
            require(engine.getOutputTensor(0).dataType() == DataType.FLOAT32 &&
                engine.getOutputTensor(0).shape().contentEquals(shape))
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    data class Result(val bitmap: Bitmap, val tiles: Int, val inferenceMs: Double, val wallMs: Double) : AutoCloseable {
        override fun close() = bitmap.recycle()
    }

    fun restore(source: Bitmap): Result {
        val started = System.nanoTime()
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height).also { source.getPixels(it, 0, width, 0, 0, width, height) }
        val output = IntArray(pixels.size)
        val input = ByteBuffer.allocateDirect(EDGE * EDGE * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val rgb = input.asFloatBuffer()
        var neuronsNs = 0L
        var tiles = 0
        for (y in 0 until height step CORE) for (x in 0 until width step CORE) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            rgb.clear()
            for (sy in 0 until EDGE) for (sx in 0 until EDGE) {
                val ix = AiGeometry.mirror(x + sx - HALO, width)
                val iy = AiGeometry.mirror(y + sy - HALO, height)
                val value = pixels[iy * width + ix]
                rgb.put((value ushr 16 and 255) / 255f).put((value ushr 8 and 255) / 255f).put((value and 255) / 255f)
            }
            input.rewind()
            val before = System.nanoTime()
            engine.run(input, null)
            val prediction = engine.getOutputTensor(0).asReadOnlyBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer()
            neuronsNs += System.nanoTime() - before
            repeat(minOf(CORE, height - y)) { row ->
                prediction.position(((row + HALO) * EDGE + HALO) * 3)
                repeat(minOf(CORE, width - x)) { column ->
                    val r = color(prediction.get())
                    val g = color(prediction.get())
                    val b = color(prediction.get())
                    output[(y + row) * width + x + column] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            tiles++
        }
        val bitmap = Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
        return Result(bitmap, tiles, neuronsNs / 1e6, (System.nanoTime() - started) / 1e6)
    }

    override fun close() {
        try { engine.close() } finally { delegate?.close() }
    }

    private companion object {
        const val EDGE = 64
        const val HALO = 4
        const val CORE = EDGE - 2 * HALO

        fun color(value: Float): Int {
            require(value.isFinite())
            return (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }

        fun checkedModel(model: File, expectedHash: String): ByteBuffer {
            require(model.length() in 1..1_000_000)
            val bytes = model.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(hash == expectedHash)
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
        }
    }
}
