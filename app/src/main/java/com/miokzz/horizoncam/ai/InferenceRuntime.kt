package com.miokzz.horizoncam.ai

import android.content.Context
import android.os.Build
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Model, delegate and tensor views belong to the inference thread. */
internal object InferenceRuntime {
    const val INPUT_EDGE = 128
    const val OUTPUT_EDGE = 512
    private const val TAG = "HorizonBackend"

    enum class Backend(val label: String, val threads: Int = 4, val gpu: Boolean = false) {
        CPU_2("CPU-2", 2), CPU_4("CPU-4"),
        GPU_FAST("GPU-fp16-allowed", gpu = true), GPU_STRICT("GPU-fp32-requested", gpu = true),
    }

    data class Measurement(
        val backend: Backend,
        val initMs: Double,
        val firstTileMs: Double,
        val medianTileMs: Double,
        val medianNativeMs: Double,
        val mae: Double,
        val maxError: Float,
        val overFourFraction: Double,
    ) {
        val matchesCpu get() = mae <= 1.3 && maxError <= 12f && overFourFraction <= 0.01
        fun logLine(prior: PhotoPrior) = "${prior.display} ${backend.label} initMs=$initMs " +
            "firstTileMs=$firstTileMs tileMs=$medianTileMs nativeMs=$medianNativeMs " +
            "parityMAE=$mae maxError=$maxError overFour=$overFourFraction matchesCpu=$matchesCpu"
    }

    internal class Engine(
        private val interpreter: Interpreter,
        private val delegate: GpuDelegate?,
        val backend: Backend,
    ) : AutoCloseable {
        private val input = direct(INPUT_EDGE * INPUT_EDGE * 3 * Float.SIZE_BYTES)
        private val outputTensor = interpreter.getOutputTensor(0)
        val rgb: FloatBuffer = input.asFloatBuffer()
        var nativeNs: Long = 0
            private set

        /** The result refers to LiteRT's output tensor and must be consumed before another invoke. */
        fun invoke(): FloatBuffer = try {
            input.rewind()
            interpreter.run(input, null)
            nativeNs = interpreter.lastNativeInferenceDurationNanoseconds ?: 0L
            outputTensor.asReadOnlyBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer()
        } catch (e: RuntimeException) {
            throw InferenceFailure("Falha na inferência LiteRT", e)
        }

        /** Independent reference for proving the tensor view matches the copied output. */
        fun invokeCopied(output: ByteBuffer): FloatBuffer {
            input.rewind()
            output.rewind()
            interpreter.run(input, output)
            return output.asFloatBuffer()
        }

        override fun close() { try { interpreter.close() } finally { delegate?.close() } }
    }

    class InferenceFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    private val thread = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "Horizon-AI-inference") }.apply {
        removeOnCancelPolicy = true
    }
    private var current: Engine? = null
    private var active: PhotoPrior? = null
    private var timeout: java.util.concurrent.ScheduledFuture<*>? = null

    fun <T> run(context: Context, prior: PhotoPrior, checkActive: () -> Unit, work: (Engine, () -> Unit) -> T): T =
        onInferenceThread { checkWaiter ->
            fun ensureActive() {
                checkWaiter()
                checkActive()
            }
            timeout?.cancel(false)
            try {
                ensureActive()
                if (active != prior || current == null) {
                    clear()
                    current = choose(context, prior, ::ensureActive)
                    active = prior
                }
                try {
                    work(requireNotNull(current), ::ensureActive)
                } catch (e: InferenceFailure) {
                    if (current?.backend?.gpu != true) throw e
                    ensureActive()
                    Log.w(TAG, "GPU execution interrupted, retrying on CPU", e)
                    clear()
                    current = open(bytes(context, prior), Backend.CPU_4)
                    active = prior
                    val result = work(requireNotNull(current), ::ensureActive)
                    preferences(context).edit().putString(key(prior), Backend.CPU_4.name).apply()
                    result
                }
            } finally {
                timeout = thread.schedule({ clear() }, 45, TimeUnit.SECONDS)
            }
        }

    /** Uses the production setup and does not override the app's stored backend selection. */
    fun benchmark(context: Context, prior: PhotoPrior, probes: List<FloatArray>): List<Measurement> =
        onInferenceThread { checkWaiter ->
            timeout?.cancel(false)
            clear()
            measure(bytes(context, prior), prior, candidates(), probes, checkWaiter)
        }

    private fun clear() {
        val engine = current
        current = null
        active = null
        engine?.close()
    }

    private fun <T> onInferenceThread(work: (() -> Unit) -> T): T {
        val stopped = AtomicBoolean()
        fun checkWaiter() {
            if (stopped.get() || Thread.currentThread().isInterrupted) throw CancellationException()
        }
        val task = thread.submit(Callable { checkWaiter(); work(::checkWaiter) })
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        } catch (_: InterruptedException) {
            stopped.set(true)
            // The caller still owns the input and assembly bitmaps. A running delegate
            // must finish the current native call before the caller can recycle them.
            try {
                while (!task.isDone) {
                    try {
                        task.get()
                    } catch (_: InterruptedException) {
                        continue
                    } catch (_: ExecutionException) {
                        break
                    }
                }
            } finally {
                Thread.currentThread().interrupt()
            }
            throw CancellationException()
        }
    }

    private fun direct(size: Int) = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
    private fun key(prior: PhotoPrior) = "v4-${prior.hash}-${Build.FINGERPRINT}"
    private fun preferences(context: Context) =
        context.getSharedPreferences("horizon-inference", Context.MODE_PRIVATE)

    private fun bytes(context: Context, prior: PhotoPrior): ByteBuffer {
        val data = context.assets.open(prior.asset).use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        check(digest == prior.hash) { "Modelo de restauração inválido" }
        return direct(data.size).apply { put(data); rewind() }
    }

    private fun open(model: ByteBuffer, backend: Backend): Engine {
        val gpu = if (backend.gpu) GpuDelegate(GpuDelegateFactory.Options()
            .setPrecisionLossAllowed(backend == Backend.GPU_FAST)
            .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)) else null
        try {
            val options = Interpreter.Options().setNumThreads(backend.threads).setUseXNNPACK(!backend.gpu)
            if (gpu != null) options.addDelegate(gpu)
            val interpreter = Interpreter(model, options)
            try {
                interpreter.allocateTensors()
                require(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1)
                val input = interpreter.getInputTensor(0)
                val output = interpreter.getOutputTensor(0)
                require(input.dataType() == DataType.FLOAT32 && output.dataType() == DataType.FLOAT32)
                require(input.shape().contentEquals(intArrayOf(1, INPUT_EDGE, INPUT_EDGE, 3)))
                require(output.shape().contentEquals(intArrayOf(1, OUTPUT_EDGE, OUTPUT_EDGE, 3)))
                return Engine(interpreter, gpu, backend)
            } catch (e: Throwable) {
                interpreter.close()
                throw e
            }
        } catch (e: Throwable) {
            gpu?.close()
            throw e
        }
    }

    private fun candidates(): List<Backend> {
        val supported = runCatching {
            CompatibilityList().use { it.isDelegateSupportedOnThisDevice }
        }.getOrDefault(false)
        return if (supported) Backend.entries else listOf(Backend.CPU_2, Backend.CPU_4)
    }

    private fun choose(context: Context, prior: PhotoPrior, checkActive: () -> Unit): Engine {
        val model = bytes(context, prior)
        val prefs = preferences(context)
        Backend.entries.firstOrNull { it.name == prefs.getString(key(prior), null) }?.let { cached ->
            runCatching { open(model, cached) }.getOrNull()?.let { return it }
        }
        val timings = measure(model, prior, candidates(), calibrationProbes(), checkActive)
        val cpu = timings.filter { !it.backend.gpu && it.matchesCpu }.minByOrNull { it.medianTileMs }
        checkNotNull(cpu) { "Nenhum backend CPU validado" }
        val gpu = timings.filter { it.backend.gpu && it.matchesCpu }.minByOrNull { it.medianTileMs }
        val preferred = if (gpu != null && gpu.medianTileMs < cpu.medianTileMs * 0.95) gpu.backend else cpu.backend
        checkActive()
        val engine = runCatching { open(model, preferred) }.getOrElse {
            if (preferred == cpu.backend) throw it
            Log.w(TAG, "Failed to reopen GPU, using CPU", it)
            open(model, cpu.backend)
        }
        prefs.edit().putString(key(prior), engine.backend.name).apply()
        Log.i(TAG, "Selecionado ${prior.display}: ${engine.backend.label}")
        return engine
    }

    private fun calibrationProbes(): List<FloatArray> = listOf(
        FloatArray(INPUT_EDGE * INPUT_EDGE * 3) { i ->
            val x = i / 3 % INPUT_EDGE
            val y = i / (3 * INPUT_EDGE)
            val stripe = if ((x / 17 + y / 29) % 2 == 0) 36 else 164
            (stripe + x / 3 + 16 * (i % 3)).toFloat()
        },
        FloatArray(INPUT_EDGE * INPUT_EDGE * 3) { i ->
            val x = i / 3 % INPUT_EDGE
            val y = i / (3 * INPUT_EDGE)
            val signal = ((x * 31 xor (y * 67) xor (x * y)) and 127) + 64
            val value = if (x < INPUT_EDGE / 2) signal else 98 + (y + x) / 8 + (signal % 5)
            (value + 12 * (i % 3)).toFloat()
        },
    )

    private fun measure(
        model: ByteBuffer,
        prior: PhotoPrior,
        backends: List<Backend>,
        probes: List<FloatArray>,
        checkActive: () -> Unit,
    ): List<Measurement> {
        require(probes.size >= 2 && probes.all { it.size == INPUT_EDGE * INPUT_EDGE * 3 })
        val core = prior.core * 4
        val origin = prior.halo * 4
        val reference = mutableListOf<FloatArray>()
        return backends.mapNotNull { backend ->
            checkActive()
            try {
                val initStart = System.nanoTime()
                open(model, backend).use { engine ->
                    val initMs = ms(System.nanoTime() - initStart)
                    val establishesReference = reference.isEmpty()
                    check(!establishesReference || !backend.gpu)
                    val measured = mutableListOf<FloatArray>()
                    var total = 0.0
                    var peak = 0f
                    var overFour = 0
                    var count = 0
                    val wall = LongArray(probes.size + 2)
                    val native = LongArray(wall.size)
                    for (i in wall.indices) {
                        checkActive()
                        val probe = (i - 1).coerceAtLeast(0) % probes.size
                        val input = probes[probe]
                        val start = System.nanoTime()
                        engine.rgb.clear()
                        engine.rgb.put(input)
                        val output = engine.invoke()
                        wall[i] = System.nanoTime() - start
                        native[i] = engine.nativeNs
                        if (i !in 1..probes.size) continue
                        measured.add(copyCore(output, core, origin))
                    }
                    val expected = if (!establishesReference) reference else {
                        val copied = direct(OUTPUT_EDGE * OUTPUT_EDGE * 3 * Float.SIZE_BYTES)
                        probes.map { input ->
                            checkActive()
                            engine.rgb.clear()
                            engine.rgb.put(input)
                            copyCore(engine.invokeCopied(copied), core, origin)
                        }
                    }
                    for ((probe, output) in measured.withIndex()) {
                        val target = expected[probe]
                        for (i in output.indices) {
                            val error = abs(output[i] - target[i])
                            check(error.isFinite()) { "Nonfinite tile output" }
                            total += error
                            peak = maxOf(peak, error)
                            if (error > 4f) overFour++
                        }
                        count += output.size
                    }
                    Measurement(backend, initMs, ms(wall[0]), medianWarm(wall), medianWarm(native),
                        total / count, peak, overFour.toDouble() / count).also { measurement ->
                        if (establishesReference) {
                            check(measurement.matchesCpu) { "CPU tensor view differs from copied reference" }
                            reference.addAll(expected)
                        }
                        Log.i(TAG, measurement.logLine(prior))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${backend.label} indisponível", e)
                null
            } catch (e: LinkageError) {
                Log.w(TAG, "${backend.label} indisponível", e)
                null
            }
        }
    }

    private fun copyCore(output: FloatBuffer, width: Int, origin: Int): FloatArray {
        val rowSize = width * 3
        return FloatArray(width * rowSize).also { dst ->
            for (y in 0 until width) {
                output.position(((y + origin) * OUTPUT_EDGE + origin) * 3)
                output.get(dst, y * rowSize, rowSize)
            }
        }
    }

    private fun ms(ns: Long) = ns / 1e6

    private fun medianWarm(times: LongArray): Double {
        val warm = times.copyOfRange(1, times.size).sorted()
        val mid = warm.size / 2
        return if (warm.size % 2 == 1) ms(warm[mid]) else (ms(warm[mid - 1]) + ms(warm[mid])) * 0.5
    }
}
