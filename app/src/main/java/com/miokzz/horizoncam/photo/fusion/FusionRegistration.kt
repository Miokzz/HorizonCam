package com.miokzz.horizoncam.photo.fusion

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class PreparedFrame(
    val source: FusionFrame,
    val gray: FloatArray,
    val coarse: FloatArray,
    val coarseWidth: Int,
    val coarseHeight: Int,
    val coarseScale: Int,
    val noise: Double,
    val sharpness: Double,
    val mean: Double,
    val exposure: Double?,
)

internal object FusionRegistration {
    fun prepare(frame: FusionFrame): PreparedFrame {
        val gray = FusionMath.gray(frame)
        val scale = max(1, max(frame.width, frame.height) / 256)
        val width = frame.width / scale
        val height = frame.height / scale
        val coarse = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var total = 0f
            for (j in 0 until scale) for (i in 0 until scale) total +=
                gray[(y * scale + j) * frame.width + x * scale + i]
            coarse[y * width + x] = total / (scale * scale)
        }
        return PreparedFrame(
            frame,
            gray,
            coarse,
            width,
            height,
            scale,
            FusionMath.noise(gray, frame.width, frame.height),
            FusionMath.sharpness(gray, frame.width, frame.height),
            gray.average(),
            FusionMath.exposure(frame),
        )
    }

    fun reference(frames: List<PreparedFrame>): Int {
        val exposures = frames.mapNotNull { it.exposure }.toDoubleArray()
        val medianExposure = if (exposures.isNotEmpty()) FusionMath.median(exposures) else null
        val medianMean = FusionMath.median(frames.map { it.mean }.toDoubleArray())
        val normal =
            frames.indices
                .filter { index ->
                    val frame = frames[index]
                    if (medianExposure != null && frame.exposure != null)
                        frame.exposure / medianExposure in 0.8..1.25
                    else frame.mean / max(1.0, medianMean) in 0.8..1.25
                }
                .ifEmpty { frames.indices.toList() }
        fun quality(index: Int): Double {
            val frame = frames[index]
            return frame.sharpness / (1.0 + 0.5 * frame.noise * frame.noise)
        }
        val best = normal.maxOf(::quality)
        // Do not mistake random high-frequency noise for a sharp reference.
        // Prefer the first normal frame when noise-aware quality is tied.
        return normal.firstOrNull { quality(it) >= best * 0.93 } ?: normal.maxBy(::quality)
    }

    private fun correlation(
        reference: FloatArray,
        candidate: FloatArray,
        width: Int,
        height: Int,
        dx: Double,
        dy: Double,
        margin: Int,
        brightnessGain: Double,
    ): Double {
        val step = max(1, max(width, height) / 160)
        var sumReference = 0.0
        var sumCandidate = 0.0
        var squareReference = 0.0
        var squareCandidate = 0.0
        var product = 0.0
        var count = 0
        var possible = 0
        for (y in margin until height - margin step step) for (x in
            margin until width - margin step step) {
            val a = reference[y * width + x].toDouble()
            val b = FusionMath.sample(candidate, width, height, x + dx, y + dy)
            if (a < 8 || a > 247 || b < 4 || b > 251) continue
            possible++
            // Gross photometric disagreement is usually a moving object.
            // Trim it before it can drag a correct global translation.
            if (abs(a - b * brightnessGain) > 40.0) continue
            sumReference += a
            sumCandidate += b
            squareReference += a * a
            squareCandidate += b * b
            product += a * b
            count++
        }
        if (count < 32 || count < possible * 0.60) return -1.0
        val varianceReference = squareReference - sumReference * sumReference / count
        val varianceCandidate = squareCandidate - sumCandidate * sumCandidate / count
        if (varianceReference <= 1 || varianceCandidate <= 1) return -1.0
        val correlation =
            (product - sumReference * sumCandidate / count) /
                kotlin.math.sqrt(varianceReference * varianceCandidate)
        return correlation - 0.08 * (1.0 - count.toDouble() / max(1, possible))
    }

    private fun gain(
        reference: PreparedFrame,
        candidate: PreparedFrame,
        dx: Double,
        dy: Double,
    ): Double {
        val width = reference.source.width
        val height = reference.source.height
        val values = ArrayList<Double>()
        val step = max(1, max(width, height) / 96)
        for (y in 2 until height - 2 step step) for (x in 2 until width - 2 step step) {
            if (x + dx < 1 || y + dy < 1 || x + dx >= width - 1 || y + dy >= height - 1) continue
            val a = reference.gray[y * width + x].toDouble()
            val b = FusionMath.sample(candidate.gray, width, height, x + dx, y + dy)
            if (a in 16.0..235.0 && b in 8.0..245.0) values.add(a / b)
        }
        return if (values.size >= 16) FusionMath.median(values.toDoubleArray())
        else
            candidate.exposure?.let { candidateExposure ->
                reference.exposure?.div(candidateExposure)
            } ?: 1.0
    }

    fun align(
        reference: PreparedFrame,
        candidate: PreparedFrame,
        index: Int,
        maxShift: Int,
    ): FrameAlignment {
        val relativeExposure =
            if (reference.exposure != null && candidate.exposure != null)
                candidate.exposure / reference.exposure
            else null
        if (reference.source.pixels.contentEquals(candidate.source.pixels)) {
            return FrameAlignment(
                index,
                0.0,
                0.0,
                1.0,
                1.0,
                1.0,
                true,
                subpixelReliable = true,
                relativeExposure = relativeExposure,
            )
        }
        val lowTexture =
            FusionMath.grayVariance(reference.gray) <
                max(64.0, reference.noise * reference.noise * 3)
        if (lowTexture) {
            val gain = gain(reference, candidate, 0.0, 0.0)
            val similar =
                abs(candidate.mean * gain - reference.mean) < max(8.0, reference.noise * 3)
            return FrameAlignment(
                index,
                0.0,
                0.0,
                0.0,
                gain,
                1.0,
                similar,
                if (similar) "Sem textura: somente redução de ruído"
                else "Cena sem referência de alinhamento",
                relativeExposure = relativeExposure,
            )
        }
        val brightnessGain = (reference.mean / max(1.0, candidate.mean)).coerceIn(0.4, 2.5)
        val coarseRadius = ceil(maxShift.toDouble() / reference.coarseScale).toInt()
        val coarseMargin =
            min(coarseRadius + 2, min(reference.coarseWidth, reference.coarseHeight) / 4)
        var best = -2.0
        var coarseX = 0
        var coarseY = 0
        val coarseScores = mutableListOf<Triple<Int, Int, Double>>()
        for (dy in -coarseRadius..coarseRadius) for (dx in -coarseRadius..coarseRadius) {
            val score =
                correlation(
                    reference.coarse,
                    candidate.coarse,
                    reference.coarseWidth,
                    reference.coarseHeight,
                    dx.toDouble(),
                    dy.toDouble(),
                    coarseMargin,
                    brightnessGain,
                )
            coarseScores.add(Triple(dx, dy, score))
            if (score > best) {
                best = score
                coarseX = dx
                coarseY = dy
            }
        }
        val coarseBest = best
        val second =
            coarseScores
                .filter { abs(it.first - coarseX) > 1 || abs(it.second - coarseY) > 1 }
                .maxOfOrNull { it.third } ?: -1.0
        val width = reference.source.width
        val height = reference.source.height
        val margin = min(maxShift + 3, min(width, height) / 4)
        var bestX = (coarseX * reference.coarseScale).coerceIn(-maxShift, maxShift).toDouble()
        var bestY = (coarseY * reference.coarseScale).coerceIn(-maxShift, maxShift).toDouble()
        best = -2.0
        val radius = reference.coarseScale + 1
        val centerX = bestX.roundToInt()
        val centerY = bestY.roundToInt()
        for (dy in max(-maxShift, centerY - radius)..min(maxShift, centerY + radius)) {
            for (dx in max(-maxShift, centerX - radius)..min(maxShift, centerX + radius)) {
                val score =
                    correlation(
                        reference.gray,
                        candidate.gray,
                        width,
                        height,
                        dx.toDouble(),
                        dy.toDouble(),
                        margin,
                        brightnessGain,
                    )
                if (score > best) {
                    best = score
                    bestX = dx.toDouble()
                    bestY = dy.toDouble()
                }
            }
        }
        for (increment in listOf(0.25, 0.05)) {
            val anchorX = bestX
            val anchorY = bestY
            val steps = if (increment == 0.25) 3 else 2
            for (j in -steps..steps) for (i in -steps..steps) {
                val dx = anchorX + i * increment
                val dy = anchorY + j * increment
                if (abs(dx) > maxShift || abs(dy) > maxShift) continue
                val score =
                    correlation(
                        reference.gray,
                        candidate.gray,
                        width,
                        height,
                        dx,
                        dy,
                        margin,
                        brightnessGain,
                    )
                if (score > best) {
                    best = score
                    bestX = dx
                    bestY = dy
                }
            }
        }
        val gain = gain(reference, candidate, bestX, bestY)
        val sharpness =
            if (reference.sharpness > 0.001) candidate.sharpness * gain * gain / reference.sharpness
            else 1.0
        val ambiguous = second > coarseBest - 0.001
        val reason =
            when {
                best < 0.82 -> "Baixa confiança de alinhamento"
                abs(bestX) >= maxShift - 0.2 || abs(bestY) >= maxShift - 0.2 ->
                    "Movimento excede a faixa de busca"
                ambiguous -> "Alinhamento ambíguo"
                gain !in 0.4..2.5 -> "Exposição incompatível"
                sharpness < 0.40 -> "Quadro desfocado"
                else -> null
            }
        return FrameAlignment(
            index,
            bestX,
            bestY,
            best.coerceIn(0.0, 1.0),
            gain,
            sharpness,
            reason == null,
            reason,
            subpixelReliable = reason == null,
            relativeExposure = relativeExposure ?: (1.0 / gain),
        )
    }
}
