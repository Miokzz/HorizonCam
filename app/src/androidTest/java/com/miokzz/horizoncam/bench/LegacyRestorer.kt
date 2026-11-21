package com.miokzz.horizoncam.bench

import com.miokzz.horizoncam.ai.*

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/**
 * Blind restoration with a learned general-photo prior. Composite straight into the original
 * pixel grid: keep native framing and never allocate a 4× full-scene intermediate bitmap.
 */
/** Frozen baseline 6708aca, instrumentation APK only. */
object LegacyRestorer {
    const val MAX_PIXELS = 12_600_000
    data class Result(val bitmap: Bitmap, val modelWidth: Int, val modelHeight: Int, val elapsedMs: Long)

    fun restore(context: Context, original: Bitmap, zoom: Float, iso: Int, upscale: Int, onProgress: (AiProgress) -> Unit, cancelled: () -> Boolean): Result {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(upscale in setOf(1, 2, 4))
        val width = original.width * upscale
        val height = original.height * upscale
        val count = width.toLong() * height
        require(count in 1..MAX_PIXELS.toLong()) { "Para uma foto acima de 12,6 MP, selecione um detalhe." }
        val estimate = count * (if (upscale > 1) 10 else 6) + 62L * 1024 * 1024
        val runtime = Runtime.getRuntime()
        require(estimate < runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) {
            "Memória insuficiente para restaurar a cena inteira. Selecione um detalhe."
        }
        val strength = RestorationStrength.forCapture(zoom, iso)
        val started = System.nanoTime()
        val scale = maxOf(upscale, samplingFactor(maxOf(width, height)))
        val guideW = (width + scale - 1) / scale
        val guideH = (height + scale - 1) / scale
        val guide = if (guideW == original.width && guideH == original.height) original else Bitmap.createScaledBitmap(original, guideW, guideH, true)
        var destination: Bitmap? = null
        var baseline: Bitmap? = null
        fun checkCancelled() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException() }
        try {
            val observed = if (upscale == 1) original else Bitmap.createScaledBitmap(original, width, height, true)
            baseline = observed
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            destination = output
            val accumulated = ShortArray(count.toInt())
            val tiles = AiGeometry.tiles(guideW, guideH)
            val model = modelBuffer(context)
            val options = Interpreter.Options().setNumThreads(2)
            Interpreter(model, options).use { interpreter ->
                val inputTensor = interpreter.getInputTensor(0)
                val outputTensor = interpreter.getOutputTensor(0)
                require(inputTensor.shape().contentEquals(intArrayOf(1, 50, 50, 3)) && inputTensor.dataType() == DataType.FLOAT32)
                require(outputTensor.shape().contentEquals(intArrayOf(1, 200, 200, 3)) && outputTensor.dataType() == DataType.FLOAT32)
                val input = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())
                val prediction = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())
                val line = IntArray(50)
                val maxArea = AiGeometry.CORE * scale * AiGeometry.CORE * scale
                val previous = IntArray(maxArea)
                val source = IntArray(maxArea)
                onProgress(AiProgress(0, tiles.size))
                for ((index, tile) in tiles.withIndex()) {
                    checkCancelled()
                    input.rewind()
                    val mirroredX = IntArray(50) { AiGeometry.mirror(tile.x - AiGeometry.HALO + it, guideW) }
                    val left = mirroredX.min()
                    val span = mirroredX.max() - left + 1
                    for (y in 0 until 50) {
                        val sy = AiGeometry.mirror(tile.y - AiGeometry.HALO + y, guideH)
                        guide.getPixels(line, 0, span, left, sy, span, 1)
                        for (sx in mirroredX) {
                            val pixel = line[sx - left]
                            input.putFloat((pixel shr 16 and 255).toFloat())
                            input.putFloat((pixel shr 8 and 255).toFloat())
                            input.putFloat((pixel and 255).toFloat())
                        }
                    }
                    input.rewind()
                    prediction.rewind()
                    interpreter.run(input, prediction)
                    val x0 = tile.x * scale
                    val y0 = tile.y * scale
                    val w = minOf(tile.width * scale, width - x0)
                    val h = minOf(tile.height * scale, height - y0)
                    output.getPixels(previous, 0, w, x0, y0, w, h)
                    observed.getPixels(source, 0, w, x0, y0, w, h)
                    val samples = 4 / scale
                    for (y in 0 until h) {
                        checkCancelled()
                        val wy = AiGeometry.weight(y * samples, h * samples)
                        for (x in 0 until w) {
                            val local = y * w + x
                            val global = (y0 + y) * width + x0 + x
                            val weight = (64 * wy * AiGeometry.weight(x * samples, w * samples)).roundToInt().coerceAtLeast(1)
                            val prior = accumulated[global].toInt()
                            val pixel = areaSample(prediction, AiGeometry.HALO * 4 + x * samples,
                                AiGeometry.HALO * 4 + y * samples, samples)
                            // Low-ISO wide shots retain most of the measured high-frequency signal.
                            val neighbour = source[if (x > 0) local - 1 else if (y > 0) local - w else local]
                            val restored = RestorationStrength.protect(source[local], pixel, neighbour, strength)
                            previous[local] = blend(previous[local], restored, weight.toFloat() / (prior + weight))
                            accumulated[global] = (prior + weight).toShort()
                        }
                    }
                    output.setPixels(previous, 0, w, x0, y0, w, h)
                    onProgress(AiProgress(index + 1, tiles.size))
                }
            }
            destination = null
            return Result(output, guideW, guideH, (System.nanoTime() - started) / 1_000_000L)
        } finally {
            destination?.recycle()
            if (baseline !== original) baseline?.recycle()
            if (guide !== original) guide.recycle()
        }
    }

    private var verifiedModel: ByteBuffer? = null

    @Synchronized private fun modelBuffer(context: Context): ByteBuffer {
        verifiedModel?.let { return it.duplicate().order(ByteOrder.nativeOrder()) }
        val bytes = context.assets.open(RestorationModel.ASSET).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(bytes.size == RestorationModel.BYTES && hash == RestorationModel.SHA256)
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        verifiedModel = model.asReadOnlyBuffer()
        return model
    }

    fun samplingFactor(longEdge: Int): Int = when {
        longEdge >= 2400 -> 4
        longEdge >= 1000 -> 2
        else -> 1
    }

    private fun areaSample(buffer: ByteBuffer, left: Int, top: Int, size: Int): Int {
        var r = 0f
        var g = 0f
        var b = 0f
        for (y in top until top + size) for (x in left until left + size) {
            val offset = (200 * y + x) * 12
            r += buffer.getFloat(offset)
            g += buffer.getFloat(offset + 4)
            b += buffer.getFloat(offset + 8)
        }
        val n = size * size
        return AiColor.pack(r / n, g / n, b / n)
    }

    private fun blend(a: Int, b: Int, fraction: Float): Int {
        fun channel(shift: Int): Float {
            val start = a shr shift and 255
            val end = b shr shift and 255
            return start + (end - start) * fraction
        }
        return AiColor.pack(channel(16), channel(8), channel(0))
    }
}
