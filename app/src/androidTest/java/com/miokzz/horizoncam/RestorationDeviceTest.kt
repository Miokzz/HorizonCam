package com.miokzz.horizoncam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.miokzz.horizoncam.ai.InferenceRuntime
import com.miokzz.horizoncam.ai.NativeRestorer
import com.miokzz.horizoncam.ai.PhotoPrior
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.log10

/** Matched public-domain crops with fixed blur, noise and JPEG degradation.
 * Runs the production engine without writing to the gallery.
 */
class RestorationDeviceTest {
    @Test fun compareBackendsOnTheSamePhotographicInputs() {
        val runner = InstrumentationRegistry.getInstrumentation()
        val probes = listOf("face", "detail").map { scene ->
            val input = runner.context.assets.open("quality/astronaut_$scene-degraded.png").use {
                BitmapFactory.decodeStream(it)
            }
            val edge = InferenceRuntime.INPUT_EDGE
            val resized = Bitmap.createScaledBitmap(input, edge, edge, true)
            try {
                val pixels = IntArray(edge * edge)
                resized.getPixels(pixels, 0, edge, 0, 0, edge, edge)
                FloatArray(pixels.size * 3) { i ->
                    ((pixels[i / 3] ushr (16 - 8 * (i % 3))) and 255).toFloat()
                }
            } finally {
                if (resized !== input) resized.recycle()
                input.recycle()
            }
        }
        for (prior in PhotoPrior.entries) {
            val measurements = InferenceRuntime.benchmark(runner.targetContext, prior, probes)
            assertTrue(measurements.any { !it.backend.gpu && it.matchesCpu })
            measurements.forEach { measurement ->
                val line = measurement.logLine(prior)
                android.util.Log.i("HorizonQuality", line)
                runner.sendStatus(0, Bundle().apply { putString("stream", "\n$line\n") })
            }
        }
    }

    @Test fun compareMatchedFramesAgainstKnownReference() {
        val runner = InstrumentationRegistry.getInstrumentation()
        fun fixture(name: String) = runner.context.assets.open("quality/$name.png").use { BitmapFactory.decodeStream(it) }
        for (scene in listOf("face", "detail")) {
            val input = fixture("astronaut_$scene-degraded")
            val truth = fixture("astronaut_$scene-truth")
            try {
                for (zoom in listOf(1f, 2.3f, 7.8f, 16.5f, 30f)) {
                    var completed = 0
                    var total = 0
                    val prediction = NativeRestorer.restore(runner.targetContext, input, zoom, 400, 1, {
                        completed = it.completedTiles; total = it.totalTiles
                    }, { false })
                    try {
                        val out = prediction.bitmap
                        assertEquals(input.width, out.width)
                        assertEquals(input.height, out.height)
                        assertTrue(total > 0 && completed == total)
                        val baseline = psnr(input, truth)
                        val restored = psnr(out, truth)
                        assertTrue(restored.isFinite() && baseline.isFinite())
                        val line = "fixture=$scene zoom=$zoom originalPSNR=$baseline restoredPSNR=$restored " +
                            "tiles=${prediction.tiles} ms=${prediction.elapsedMs} model=${prediction.model} " +
                            "backend=${prediction.backend} neuralMs=${prediction.inferenceMs} invokeMs=${prediction.nativeInferenceMs}"
                        android.util.Log.i("HorizonQuality", line)
                        runner.sendStatus(0, Bundle().apply { putString("stream", "\n$line\n") })
                    } finally { prediction.bitmap.recycle() }
                }
            } finally { input.recycle(); truth.recycle() }
        }
    }

    private fun psnr(a: Bitmap, b: Bitmap): Double {
        require(a.width == b.width && a.height == b.height)
        var total = 0.0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val pa = a.getPixel(x,y)
            val pb = b.getPixel(x,y)
            for (shift in listOf(0,8,16)) {
                val d = (pa shr shift and 255) - (pb shr shift and 255)
                total += d * d
            }
        }
        return 10 * log10(255.0 * 255 * 3 * a.width * a.height / total.coerceAtLeast(1e-9))
    }
}
