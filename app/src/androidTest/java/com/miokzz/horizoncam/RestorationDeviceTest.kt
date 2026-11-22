package com.miokzz.horizoncam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.miokzz.horizoncam.ai.InferenceRuntime
import com.miokzz.horizoncam.ai.NativeRestorer
import com.miokzz.horizoncam.ai.PhotoPrior
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
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
                        if (zoom >= 7.8f) {
                            assertEquals(PhotoPrior.MAX.display, prediction.model)
                            assertTrue("Zoom $zoom: detail fell below the matched reference gate", restored > baseline + 0.1)
                        }
                        val line = "fixture=$scene zoom=$zoom originalPSNR=$baseline restoredPSNR=$restored " +
                            "tiles=${prediction.tiles} ms=${prediction.elapsedMs} model=${prediction.model} " +
                            "backend=${prediction.backend} neuralMs=${prediction.inferenceMs} invokeMs=${prediction.nativeInferenceMs}"
                        android.util.Log.i("HorizonQuality", line)
                        runner.sendStatus(0, Bundle().apply { putString("stream", "\n$line\n") })
                    } finally { prediction.close() }
                }
            } finally { input.recycle(); truth.recycle() }
        }
    }

    @Test fun zoomedTextAndEdgesComparedWithBilinear() {
        val runner = InstrumentationRegistry.getInstrumentation()
        val truth = Bitmap.createBitmap(192, 256, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(truth)
            canvas.drawColor(Color.rgb(220, 220, 220))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
            for ((index, size) in listOf(32f, 23f, 16f, 11f).withIndex()) {
                paint.color = if (index % 2 == 0) Color.rgb(25, 25, 25) else Color.rgb(125, 125, 125)
                paint.textSize = size
                canvas.drawText("HC 0285", 16f, 48f + 53f * index, paint)
            }
            for ((zoom, scale) in listOf(3f to 1, 10f to 2, 20f to 4, 30f to 4)) {
                val sample = Bitmap.createScaledBitmap(truth, truth.width / maxOf(2, scale), truth.height / maxOf(2, scale), true)
                val source = if (scale == 1) Bitmap.createScaledBitmap(sample, truth.width, truth.height, true) else sample
                val baseline = Bitmap.createScaledBitmap(source, truth.width, truth.height, true)
                try {
                    NativeRestorer.restore(runner.targetContext, source, zoom, 800, scale, {}, { false }).use { result ->
                        assertEquals(truth.width, result.bitmap.width)
                        assertEquals(truth.height, result.bitmap.height)
                        assertTrue(result.usedNeuralModel)
                        assertFalse("The neural result must differ from simple interpolation", baseline.sameAs(result.bitmap))
                        val line = "text zoom=$zoom source=${source.width}x${source.height} " +
                            "bilinearPSNR=${psnr(baseline, truth)} neuralPSNR=${psnr(result.bitmap, truth)} " +
                            "backend=${result.backend} restoreMs=${result.elapsedMs} pssKiB=${android.os.Debug.getPss()}"
                        runner.sendStatus(0, Bundle().apply { putString("stream", "\n$line\n") })
                    }
                } finally {
                    if (baseline !== source) baseline.recycle()
                    if (source !== sample) source.recycle()
                    sample.recycle()
                }
            }
        } finally { truth.recycle() }
    }

    @Test fun compareLocalStructureOnMatchedInputs() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("horizonStructure") == "yes")
        val runner = InstrumentationRegistry.getInstrumentation()
        val levels = listOf(0.6f to 1, 1f to 1, 3f to 1, 10f to 2, 20f to 4, 30f to 4)
        val fixtures = listOf("chart", "face", "detail")
        for (fixture in fixtures) {
            val dimensions = 192 to 256
            val truth = if (fixture == "chart") mixedContrastChart(dimensions.first, dimensions.second) else {
                val asset = runner.context.assets.open("quality/astronaut_$fixture-truth.png").use(BitmapFactory::decodeStream)
                val resized = Bitmap.createScaledBitmap(asset, dimensions.first, dimensions.second, true)
                if (asset !== resized) asset.recycle()
                resized
            }
            try {
                for ((zoom, scale) in levels) {
                    val source = degradedInput(truth, scale, if (zoom == 30f) 74 else 86)
                    val bilinear = Bitmap.createScaledBitmap(source, truth.width, truth.height, true)
                    try {
                        val modes = if (scale == 1) listOf(false) else listOf(false, true)
                        for (guard in modes) NativeRestorer.restore(runner.targetContext, source, zoom, 800, scale,
                            {}, { false }, protectLocalStructure = guard).use { result ->
                            assertEquals(truth.width, result.bitmap.width)
                            assertEquals(truth.height, result.bitmap.height)
                            assertTrue(result.usedNeuralModel)
                            val score = psnr(result.bitmap, truth)
                            assertTrue(score.isFinite())
                            val line = "HC_CONTEXT fixture=$fixture zoom=$zoom scale=$scale guard=$guard " +
                                "source=${source.width}x${source.height} psnr=$score bilinear=${psnr(bilinear, truth)} " +
                                "backend=${result.backend} tiles=${result.tiles} wallMs=${result.elapsedMs} " +
                                "inferMs=${result.inferenceMs} pssKiB=${android.os.Debug.getPss()}"
                            runner.sendStatus(0, Bundle().apply { putString("stream", "\n$line\n") })
                        }
                    } finally {
                        if (bilinear !== source) bilinear.recycle()
                        source.recycle()
                    }
                }
            } finally { truth.recycle() }
        }
    }

    private fun mixedContrastChart(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
        canvas.drawColor(Color.rgb(215, 221, 229))
        paint.color = Color.rgb(53, 62, 77)
        canvas.drawRect(0f, 0f, width.toFloat(), height / 2f, paint)
        val labels = listOf("HC 2958", "3B 6071", "Sz 1805", "qe 3284")
        for (index in labels.indices) {
            paint.color = listOf(Color.WHITE, Color.rgb(92, 105, 115), Color.BLACK, Color.rgb(176, 183, 193))[index]
            paint.textSize = listOf(23f, 15f, 18f, 12f)[index]
            canvas.drawText(labels[index], 12f, listOf(35f, 88f, 165f, 220f)[index], paint)
        }
        paint.color = Color.rgb(110, 36, 28)
        paint.strokeWidth = 1.5f
        for (i in 0..8) canvas.drawLine(115f + 6f * i, 144f, 102f + 6f * i, 243f, paint)
        return bitmap
    }

    private fun degradedInput(truth: Bitmap, scale: Int, jpegQuality: Int): Bitmap {
        val divisor = maxOf(2, scale)
        val sampled = Bitmap.createScaledBitmap(truth, truth.width / divisor, truth.height / divisor, true)
        val framed = if (scale == 1) Bitmap.createScaledBitmap(sampled, truth.width, truth.height, true) else sampled
        return try {
            val bytes = ByteArrayOutputStream().use { output ->
                check(framed.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output))
                output.toByteArray()
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            if (framed !== sampled) framed.recycle()
            sampled.recycle()
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
