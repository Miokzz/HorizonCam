package com.miokzz.horizoncam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.miokzz.horizoncam.ai.InferenceRuntime
import com.miokzz.horizoncam.ai.NativeRestorer
import com.miokzz.horizoncam.ai.PhotoPrior
import com.miokzz.horizoncam.bench.ExperimentalDenoiser
import com.miokzz.horizoncam.bench.ExperimentalSegmentation
import com.miokzz.horizoncam.camera.StillFraming
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlin.math.log10
import kotlin.math.roundToInt

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
            val truth = matchedTruth(runner, fixture)
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

    @Test fun compareSemanticProtectionOnMatchedInputs() {
        val runner = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("horizonSemantic") == "yes")
        val model = File(runner.targetContext.filesDir, "quality-deeplab-v3.tflite")
        assertTrue("O modelo de teste precisa ser colocado no diretório privado de campo.", model.isFile)
        val optionalFixtures = if (args.getString("semanticFixtures") == "yes") listOf("cat", "grass", "brick") else emptyList()
        ExperimentalSegmentation(model).use { cpu ->
            ExperimentalSegmentation(model, ExperimentalSegmentation.Backend.GPU).use { gpu ->
                sendEvidence(runner, "HC_SEMANTIC_INIT cpuMs=${cpu.initializationMs} gpuMs=${gpu.initializationMs}")
                for (fixture in listOf("chart", "face", "detail") + optionalFixtures) {
                    val truth = if (fixture in optionalFixtures) {
                        val file = File(runner.targetContext.filesDir, "quality-matched-$fixture.png")
                        requireNotNull(BitmapFactory.decodeFile(file.path)) { "Fixture privada ausente: $fixture" }
                    } else matchedTruth(runner, fixture)
                    try {
                        for ((zoom, scale) in listOf(10f to 2, 20f to 4, 30f to 4)) {
                            val source = degradedInput(truth, scale, if (zoom == 30f) 74 else 86)
                            try {
                                val onCpu = cpu.segment(source)
                                val onGpu = gpu.segment(source)
                                assertEquals(onCpu.fallbackReason, ExperimentalSegmentation.Backend.CPU, onCpu.backend)
                                var disagreement = 0
                                var compared = 0
                                for (y in 0 until 16) for (x in 0 until 16) {
                                    val px = (x + 0.5f) / 16f
                                    val py = (y + 0.5f) / 16f
                                    val a = onCpu.protectionAt(px, py)
                                    val b = onGpu.protectionAt(px, py)
                                    assertTrue(a in 0f..1f && b in 0f..1f)
                                    if (a > 0f || b > 0f) {
                                        compared++
                                        if (kotlin.math.abs(a - b) > 0.10f) disagreement++
                                    }
                                }
                                sendEvidence(runner, "HC_SEMANTIC fixture=$fixture zoom=$zoom " +
                                    "cpu=${onCpu.backend} coveredCpu=${onCpu.protectedFraction} " +
                                    "cpuMs=${onCpu.preprocessMs + onCpu.inferenceMs + onCpu.postprocessMs} " +
                                    "gpu=${onGpu.backend} coveredGpu=${onGpu.protectedFraction} " +
                                    "gpuMs=${onGpu.preprocessMs + onGpu.inferenceMs + onGpu.postprocessMs} " +
                                    "gpuFallback=${onGpu.fallbackReason ?: "none"} " +
                                    "maskDisagreement=$disagreement/$compared center=${onCpu.classAt(0.5f, 0.5f)}")
                                val masks = linkedMapOf<String, ((Float, Float) -> Float)?>(
                                    "pixel" to null, "semantic" to onCpu::protectionAt)
                                var totalWeight = 0.0
                                for (y in 0 until source.height) for (x in 0 until source.width) {
                                    totalWeight += onCpu.protectionAt((x + 0.5f) / source.width, (y + 0.5f) / source.height)
                                }
                                val meanWeight = (totalWeight / (source.width * source.height)).toFloat()
                                if (meanWeight > 0f) masks["uniform"] = { _, _ -> meanWeight }
                                for ((variant, mask) in masks) NativeRestorer.restore(runner.targetContext,
                                    source, zoom, 800, scale, {}, { false }, protectLocalStructure = true,
                                    semanticProtection = mask).use { result ->
                                    assertEquals(truth.width, result.bitmap.width)
                                    assertEquals(truth.height, result.bitmap.height)
                                    assertTrue(result.usedNeuralModel)
                                    sendEvidence(runner, "HC_SEMANTIC_QUALITY fixture=$fixture zoom=$zoom variant=$variant " +
                                        "psnr=${psnr(result.bitmap, truth)} backend=${result.backend} " +
                                        "wallMs=${result.elapsedMs} pssKiB=${android.os.Debug.getPss()}")
                                }
                            } finally { source.recycle() }
                        }
                    } finally { truth.recycle() }
                }
            }
        }
    }

    @Test fun compareNeuralDenoisingOnMatchedInputs() {
        val runner = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("horizonDenoise") == "yes")
        val model = File(runner.targetContext.filesDir, "quality-denoiser-f32.tflite")
        val sha = requireNotNull(args.getString("denoiserSha"))
        assertTrue(model.isFile)
        ExperimentalDenoiser(model, sha, false).use { cpu ->
            val gpu = runCatching { ExperimentalDenoiser(model, sha, true) }.getOrElse {
                sendEvidence(runner, "HC_DENOISE gpuUnavailable=${it.javaClass.simpleName}")
                null
            }
            try {
                for (fixture in listOf("chart", "face", "detail", "cat", "grass", "brick")) {
                    val truth = if (fixture in listOf("cat", "grass", "brick")) {
                        requireNotNull(BitmapFactory.decodeFile(File(runner.targetContext.filesDir,
                            "quality-matched-$fixture.png").path))
                    } else matchedTruth(runner, fixture)
                    try {
                        for ((zoom, scale) in listOf(10f to 2, 20f to 4, 30f to 4)) {
                            val encoded = degradedInput(truth, scale, if (zoom == 30f) 74 else 86)
                            val source = withNoise(encoded, seed = fixture.hashCode().toLong() + zoom.toLong(),
                                sigma = if (zoom == 30f) 13.0 else 8.0)
                            encoded.recycle()
                            try {
                                cpu.restore(source).use { denoised ->
                                    gpu?.restore(source)?.use { accelerated ->
                                        sendEvidence(runner, "HC_DENOISE_PARITY fixture=$fixture zoom=$zoom " +
                                            "cpuGpuPsnr=${psnr(denoised.bitmap, accelerated.bitmap)} " +
                                            "cpuMs=${denoised.wallMs} gpuMs=${accelerated.wallMs} tiles=${denoised.tiles}")
                                    }
                                    val variants = mapOf("source" to source, "denoise" to denoised.bitmap)
                                    for ((name, input) in variants) {
                                        val resize = Bitmap.createScaledBitmap(input, truth.width, truth.height, true)
                                        try {
                                            NativeRestorer.restore(runner.targetContext, input, zoom, 1000, scale,
                                                {}, { false }).use { result ->
                                                assertEquals(truth.width, result.bitmap.width)
                                                assertEquals(truth.height, result.bitmap.height)
                                                sendEvidence(runner, "HC_DENOISE fixture=$fixture zoom=$zoom variant=$name " +
                                                    "bilinear=${psnr(resize, truth)} neural=${psnr(result.bitmap, truth)} " +
                                                    "srMs=${result.elapsedMs} denoiseMs=${if (name == "denoise") denoised.wallMs else 0.0} " +
                                                    "backend=${result.backend} pssKiB=${android.os.Debug.getPss()}")
                                            }
                                        } finally { if (resize !== input) resize.recycle() }
                                    }
                                }
                            } finally { source.recycle() }
                        }
                    } finally { truth.recycle() }
                }
            } finally { gpu?.close() }
        }
    }

    @Test fun compareDenoiserOnTeleCalibration() {
        val runner = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("realCalibration")
        assumeTrue(token != null)
        require(checkNotNull(token).matches(Regex("[0-9a-f-]{36}")))
        val directory = File(runner.targetContext.cacheDir, "quality-pair-$token")
        val raw = File(directory, "source.dng")
        val jpeg = File(directory, "source.jpg")
        check(raw.isFile && jpeg.isFile)
        val (iso, exposure) = File(directory, "exposure.txt").readLines().take(2).map(String::toLong)
        val model = File(runner.targetContext.filesDir, "quality-denoiser-f32.tflite")
        val sha = requireNotNull(args.getString("denoiserSha"))
        ExperimentalDenoiser(model, sha, false).use { denoiser ->
            for (zoom in listOf(20f, 30f)) {
                val crop = StillFraming.digitalRatio(zoom, 3f)
                val source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(jpeg)) { decoder, info, _ ->
                    val rect = StillFraming.centerCrop(info.size.width, info.size.height, crop)
                    decoder.crop = Rect(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                }
                try {
                    val scale = StillFraming.reconstructionScale(source.width, source.height, crop, NativeRestorer.MAX_PIXELS)
                    fun save(bitmap: Bitmap, tag: String) {
                        File(directory, "${zoom.toInt()}-$tag.png").outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    }
                    val resize = Bitmap.createScaledBitmap(source, source.width * scale, source.height * scale, true)
                    try { save(resize, "bilinear") } finally { if (resize !== source) resize.recycle() }
                    denoiser.restore(source).use { filtered ->
                        save(filtered.bitmap, "denoise-native")
                        val variants = listOf(Triple("current", source, false), Triple("structure", source, true),
                            Triple("denoise-sr", filtered.bitmap, false))
                        for ((name, input, guard) in variants) {
                            NativeRestorer.restore(runner.targetContext, input, zoom, iso.toInt(), scale,
                                {}, { false }, exposureSeconds = exposure / 1e9, protectLocalStructure = guard).use { result ->
                                assertEquals(source.width * scale, result.bitmap.width)
                                assertEquals(source.height * scale, result.bitmap.height)
                                save(result.bitmap, name)
                                sendEvidence(runner, "HC_DENOISE_REAL zoom=$zoom source=${source.width}x${source.height} " +
                                    "iso=$iso exposureNs=$exposure scale=$scale variant=$name " +
                                    "denoiseMs=${if (name == "denoise-sr") filtered.wallMs else 0.0} " +
                                    "srMs=${result.elapsedMs} backend=${result.backend} pssKiB=${android.os.Debug.getPss()}")
                            }
                        }
                    }
                } finally { source.recycle() }
            }
        }
    }

    private fun withNoise(source: Bitmap, seed: Long, sigma: Double): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val random = Random(seed)
        for (index in pixels.indices) {
            val color = pixels[index]
            val correlated = random.nextGaussian() * sigma
            fun channel(shift: Int): Int = (((color ushr shift) and 255) + correlated +
                random.nextGaussian() * sigma * 0.30).roundToInt().coerceIn(0, 255)
            pixels[index] = (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun sendEvidence(runner: android.app.Instrumentation, line: String) =
        runner.sendStatus(0, Bundle().apply { putString("stream", "\n$line\n") })

    private fun matchedTruth(runner: android.app.Instrumentation, fixture: String): Bitmap {
        if (fixture == "chart") return mixedContrastChart(192, 256)
        val asset = runner.context.assets.open("quality/astronaut_$fixture-truth.png").use(BitmapFactory::decodeStream)
        val resized = Bitmap.createScaledBitmap(asset, 192, 256, true)
        if (asset !== resized) asset.recycle()
        return resized
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
