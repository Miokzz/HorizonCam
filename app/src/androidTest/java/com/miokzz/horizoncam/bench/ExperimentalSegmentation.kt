package com.miokzz.horizoncam.bench

import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.roundToInt

/** Opt-in DeepLab-v3 replay. Construct, use and close it on the same instrumentation thread. */
internal class ExperimentalSegmentation(
    model: File,
    backend: Backend = Backend.CPU,
) : AutoCloseable {
    enum class Backend { CPU, GPU, NONE }

    enum class Category {
        BACKGROUND, PERSON, CAT, DOG, POTTED_PLANT, UNKNOWN;

        internal val givesProtection get() = this != BACKGROUND && this != UNKNOWN
    }

    data class Prediction(val category: Category, val confidence: Float)

    class Result internal constructor(
        val width: Int,
        val height: Int,
        private val classes: ByteArray,
        private val scores: FloatArray,
        private val protectedScores: FloatArray,
        val coverage: Map<Category, Float>,
        val protectedFraction: Float,
        val backend: Backend,
        val preprocessMs: Double,
        val inferenceMs: Double,
        val postprocessMs: Double,
        val fallbackReason: String?,
    ) {
        /** Coordinates are normalized across the upright input crop, excluding letterbox padding. */
        fun classAt(xNormalized: Float, yNormalized: Float): Prediction {
            val index = index(xNormalized, yNormalized) ?: return Prediction(Category.UNKNOWN, 0f)
            return Prediction(Category.entries[classes[index].toInt()], scores[index])
        }

        fun protectionAt(xNormalized: Float, yNormalized: Float): Float =
            index(xNormalized, yNormalized)?.let { protectedScores[it] } ?: 0f

        private fun index(x: Float, y: Float): Int? {
            if (x !in 0f..1f || y !in 0f..1f) return null
            val column = (x * width).toInt().coerceAtMost(width - 1)
            val row = (y * height).toInt().coerceAtMost(height - 1)
            return row * width + column
        }
    }

    private data class Content(val width: Int, val height: Int) {
        val left get() = (EDGE - width) / 2
        val top get() = (EDGE - height) / 2
    }

    private class Session(val interpreter: Interpreter, val delegate: GpuDelegate?, val backend: Backend) : AutoCloseable {
        override fun close() {
            try { interpreter.close() } finally { delegate?.close() }
        }
    }

    private val owner = Thread.currentThread()
    private var closed = false
    private val failures = linkedSetOf<String>()
    private val fallbackReason get() = failures.takeIf { it.isNotEmpty() }?.joinToString()
    private val initializedAt = System.nanoTime()
    private val modelBuffer = attempt("model") { readModel(model) }
    private var session = when (backend) {
        Backend.CPU -> open(Backend.CPU)
        Backend.GPU -> open(Backend.GPU) ?: open(Backend.CPU)
        Backend.NONE -> null
    }
    val initializationMs = elapsedMs(initializedAt)

    fun segment(source: Bitmap): Result {
        checkOwner()
        check(!closed)
        val active = session ?: return unavailable()
        val preprocessStart = System.nanoTime()
        val ratio = EDGE.toDouble() / maxOf(source.width, source.height)
        val content = Content(
            (ratio * source.width).roundToInt().coerceIn(1, EDGE),
            (ratio * source.height).roundToInt().coerceIn(1, EDGE),
        )
        val input = attempt("preprocess") { prepare(source, content) } ?: return unavailable()
        val preprocessMs = elapsedMs(preprocessStart)
        attempt("${active.backend} inference") { run(active, input, content, preprocessMs) }?.let { return it }
        releaseSession()
        if (active.backend == Backend.GPU) {
            val cpu = open(Backend.CPU)
            session = cpu
            if (cpu != null) {
                attempt("CPU inference") { run(cpu, input, content, preprocessMs) }?.let { return it }
                releaseSession()
            }
        }
        return unavailable(preprocessMs)
    }

    private fun prepare(source: Bitmap, content: Content): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(source, content.width, content.height, true)
        val pixels = IntArray(content.width * content.height)
        try {
            scaled.getPixels(pixels, 0, content.width, 0, 0, content.width, content.height)
        } finally {
            if (scaled !== source) scaled.recycle()
        }
        val input = ByteBuffer.allocateDirect(EDGE * EDGE * RGB * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val rgb = input.asFloatBuffer()
        for (y in 0 until EDGE) for (x in 0 until EDGE) {
            val cx = x - content.left
            val cy = y - content.top
            if (cx !in 0 until content.width || cy !in 0 until content.height) {
                rgb.put(0f).put(0f).put(0f)
            } else {
                val pixel = pixels[cy * content.width + cx]
                rgb.put(normalize(pixel ushr 16)).put(normalize(pixel ushr 8)).put(normalize(pixel))
            }
        }
        return input
    }

    private fun run(active: Session, input: ByteBuffer, content: Content, preprocessMs: Double): Result {
        val inferenceStart = System.nanoTime()
        input.rewind()
        active.interpreter.run(input, null)
        val output = active.interpreter.getOutputTensor(0).asReadOnlyBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val inferenceMs = elapsedMs(inferenceStart)
        val postprocessStart = System.nanoTime()
        val size = content.width * content.height
        val classes = ByteArray(size)
        val scores = FloatArray(size)
        val protection = FloatArray(size)
        val counts = IntArray(Category.entries.size)
        val logits = FloatArray(CLASS_COUNT)
        var protectedCount = 0
        for (y in 0 until content.height) {
            output.position(((y + content.top) * EDGE + content.left) * CLASS_COUNT)
            for (x in 0 until content.width) {
                output.get(logits)
                val index = y * content.width + x
                classify(logits) { category, confidence, margin ->
                    counts[category.ordinal]++
                    classes[index] = category.ordinal.toByte()
                    scores[index] = confidence
                    if (category.givesProtection && confidence >= MIN_CONFIDENCE && margin >= MIN_MARGIN) {
                        protectedCount++
                        protection[index] = confidence
                    }
                }
            }
        }
        val coverage = Category.entries.associateWith { counts[it.ordinal].toFloat() / size }
        return Result(content.width, content.height, classes, scores, protection, coverage,
            protectedCount.toFloat() / size, active.backend, preprocessMs, inferenceMs,
            elapsedMs(postprocessStart), fallbackReason)
    }

    private fun open(backend: Backend): Session? {
        val model = modelBuffer ?: return null
        return attempt("${backend} initialization") {
            val delegate = if (backend == Backend.GPU) {
                check(CompatibilityList().use { it.isDelegateSupportedOnThisDevice })
                GpuDelegate(GpuDelegateFactory.Options().setPrecisionLossAllowed(false)
                    .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED))
            } else null
            try {
                val options = Interpreter.Options().setNumThreads(4).setUseXNNPACK(delegate == null)
                if (delegate != null) options.addDelegate(delegate)
                val interpreter = Interpreter(model.duplicate().order(ByteOrder.nativeOrder()), options)
                try {
                    interpreter.allocateTensors()
                    check(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1)
                    val input = interpreter.getInputTensor(0)
                    val output = interpreter.getOutputTensor(0)
                    check(input.dataType() == DataType.FLOAT32 && output.dataType() == DataType.FLOAT32)
                    check(input.shape().contentEquals(intArrayOf(1, EDGE, EDGE, RGB)))
                    check(output.shape().contentEquals(intArrayOf(1, EDGE, EDGE, CLASS_COUNT)))
                    Session(interpreter, delegate, backend)
                } catch (e: Throwable) {
                    interpreter.close()
                    throw e
                }
            } catch (e: Throwable) {
                delegate?.close()
                throw e
            }
        }
    }

    private fun unavailable(preprocessMs: Double = 0.0) = Result(
        1, 1, byteArrayOf(Category.UNKNOWN.ordinal.toByte()), floatArrayOf(0f), floatArrayOf(0f),
        Category.entries.associateWith { if (it == Category.UNKNOWN) 1f else 0f },
        0f, Backend.NONE, preprocessMs, 0.0, 0.0, fallbackReason,
    )

    private fun <T> attempt(stage: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        noteFailure(stage, e)
        null
    } catch (e: LinkageError) {
        noteFailure(stage, e)
        null
    }

    private fun noteFailure(stage: String, error: Throwable) {
        failures.add("$stage (${error.javaClass.simpleName})")
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "Segmentation must stay on its owning thread." }

    private fun releaseSession() {
        val active = session ?: return
        session = null
        attempt("${active.backend} close") { active.close() }
    }

    override fun close() {
        checkOwner()
        if (closed) return
        closed = true
        releaseSession()
    }

    companion object {
        const val MODEL_SHA256 = "ff36e24d40547fe9e645e2f4e8745d1876d6e38b332d39a82f0bf0f5d1d561b3"
        private const val MODEL_BYTES = 2_780_176
        private const val EDGE = 257
        private const val RGB = 3
        private const val CLASS_COUNT = 21
        private const val MIN_CONFIDENCE = 0.65f
        private const val MIN_MARGIN = 0.20f

        private fun normalize(channel: Int) = ((channel and 255) - 127.5f) / 127.5f

        private fun readModel(file: File): ByteBuffer {
            require(file.length() == MODEL_BYTES.toLong())
            val bytes = ByteArray(MODEL_BYTES)
            file.inputStream().use { source ->
                var offset = 0
                while (offset < bytes.size) {
                    val read = source.read(bytes, offset, bytes.size - offset)
                    check(read > 0)
                    offset += read
                }
                check(source.read() == -1)
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            check(digest.joinToString("") { "%02x".format(it.toInt() and 255) } == MODEL_SHA256)
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }

        private inline fun classify(logits: FloatArray, match: (Category, Float, Float) -> Unit) {
            var winner = -1
            var best = Float.NEGATIVE_INFINITY
            var runnerUp = Float.NEGATIVE_INFINITY
            for (index in logits.indices) {
                val score = logits[index]
                check(score.isFinite()) { "Non-finite segmentation logit." }
                if (score > best) {
                    runnerUp = best
                    best = score
                    winner = index
                } else if (score > runnerUp) {
                    runnerUp = score
                }
            }
            var normalizer = 0.0
            for (score in logits) normalizer += exp(score.toDouble() - best)
            val confidence = (1.0 / normalizer).toFloat()
            val margin = ((1.0 - exp(runnerUp.toDouble() - best)) / normalizer).toFloat()
            val category = when (winner) {
                0 -> Category.BACKGROUND
                8 -> Category.CAT
                12 -> Category.DOG
                15 -> Category.PERSON
                16 -> Category.POTTED_PLANT
                else -> Category.UNKNOWN
            }
            match(category, confidence, margin)
        }

        private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000.0
    }
}
