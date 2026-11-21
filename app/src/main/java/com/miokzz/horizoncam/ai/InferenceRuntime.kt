package com.miokzz.horizoncam.ai

import android.content.Context
import android.os.Build
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** The interpreter, GPU context and reusable buffers are owned by exactly one thread. */
internal object InferenceRuntime {
    internal class Engine(val interpreter: Interpreter, val gpu: GpuDelegate?, val backend: String) : AutoCloseable {
        val input: ByteBuffer = direct(128*128*3*4)
        val output: ByteBuffer = direct(512*512*3*4)
        val rgb: FloatBuffer = input.asFloatBuffer()
        val prediction: FloatBuffer = output.asFloatBuffer()
        fun invoke() { input.rewind(); output.rewind(); interpreter.run(input,output) }
        override fun close() { try { interpreter.close() } finally { gpu?.close() } }
    }
    private val thread = ScheduledThreadPoolExecutor(1) { r -> Thread(r,"Horizon-AI-inference") }.apply {
        removeOnCancelPolicy = true
    }
    private var current: Engine? = null
    private var active: PhotoPrior? = null
    private var timeout: java.util.concurrent.ScheduledFuture<*>? = null
    fun <T> run(context: Context, model: PhotoPrior, work: (Engine)->T): T {
        val task = thread.submit(Callable {
            timeout?.cancel(false)
            try {
                if (active != model || current == null) { clear(); current = choose(context,model); active = model }
                try { work(requireNotNull(current)) }
                catch (e: RuntimeException) {
                    if (e is java.util.concurrent.CancellationException || current?.gpu == null) throw e
                    Log.w("HorizonBackend","GPU execution interrupted, retrying on CPU",e)
                    clear()
                    val prefs=context.getSharedPreferences("horizon-inference",Context.MODE_PRIVATE)
                    prefs.edit().putString(key(model),"CPU-4").apply()
                    current=open(bytes(context,model),"CPU-4")
                    active=model
                    work(requireNotNull(current))
                }
            } finally { timeout=thread.schedule({ clear() },45,TimeUnit.SECONDS) }
        })
        try { return task.get() }
        catch (e: ExecutionException) { throw (e.cause ?: e) }
        catch (e: InterruptedException) { task.cancel(true); Thread.currentThread().interrupt(); throw java.util.concurrent.CancellationException() }
    }
    private fun clear() { current?.close(); current=null; active=null }
    private fun direct(size: Int) = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
    private fun key(m: PhotoPrior) = "v3-${m.hash}-${Build.FINGERPRINT}"
    private fun bytes(context: Context, model: PhotoPrior): ByteBuffer {
        val data=context.assets.open(model.asset).use { it.readBytes() }
        val digest=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(digest==model.hash) { "Modelo de restauração inválido" }
        return direct(data.size).apply { put(data); rewind() }
    }
    private fun open(model: ByteBuffer, label: String): Engine {
        val gpu = when (label) {
            "GPU-default" -> GpuDelegate()
            "GPU-fp32-requested" -> GpuDelegate(GpuDelegateFactory.Options()
                .setPrecisionLossAllowed(false)
                .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED))
            else -> null
        }
        try {
            val options=Interpreter.Options().setNumThreads(if (label=="CPU-2") 2 else 4).setUseXNNPACK(gpu==null)
            if (gpu!=null) options.addDelegate(gpu)
            val interpreter=Interpreter(model,options)
            try {
                require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1,128,128,3)))
                require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1,512,512,3)))
                return Engine(interpreter,gpu,label)
            } catch (e: Throwable) { interpreter.close(); throw e }
        } catch (e: Throwable) { gpu?.close(); throw e }
    }
    private fun choose(context: Context, prior: PhotoPrior): Engine {
        val prefs=context.getSharedPreferences("horizon-inference",Context.MODE_PRIVATE)
        val key=key(prior)
        val model=bytes(context,prior)
        val cached=prefs.getString(key,null)
        if (cached!=null) runCatching { open(model,cached) }.getOrNull()?.let { return it }
        val options=mutableListOf("CPU-2","CPU-4")
        val gpuOk=runCatching { CompatibilityList().use { it.isDelegateSupportedOnThisDevice } }.getOrDefault(false)
        if (gpuOk) options.addAll(listOf("GPU-default", "GPU-fp32-requested"))
        var reference: FloatArray?=null
        var best="CPU-2"
        var fastest=Double.POSITIVE_INFINITY
        val input=FloatArray(128*128*3) { i -> ((i*31 xor (i/384*67)) and 255).toFloat() }
        for (label in options) {
            try {
                open(model,label).use { engine ->
                    engine.rgb.rewind(); engine.rgb.put(input); engine.invoke()
                    val sample=FloatArray(4096) { engine.prediction.get(it * 191) }
                    if (reference == null && engine.gpu != null) return@use
                    val ref=reference ?: sample.also { reference=it }
                    var total=0.0
                    var peak=0f
                    for (i in sample.indices) {
                        val err=abs(sample[i]-ref[i]); total+=err; peak=maxOf(peak,err)
                    }
                    val start=System.nanoTime()
                    repeat(2) { engine.invoke() }
                    val ms=(System.nanoTime()-start)/2e6
                    val mean=total/sample.size
                    Log.i("HorizonBackend","${prior.display} $label tileMs=$ms parityMAE=$mean maxError=$peak")
                    if (mean.isFinite() && mean<=1.3 && peak<=12f && ms<fastest) { best=label; fastest=ms }
                }
            } catch (e: Exception) { Log.w("HorizonBackend","$label indisponível",e) }
            catch (e: LinkageError) { Log.w("HorizonBackend","$label indisponível",e) }
        }
        check(fastest.isFinite())
        prefs.edit().putString(key,best).apply()
        Log.i("HorizonBackend","Selecionado ${prior.display}: $best ($fastest ms/bloco)")
        return open(model,best)
    }
}
