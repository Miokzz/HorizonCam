package com.miokzz.horizoncam.photo.fusion

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Local SDR JPEG burst fusion. No model, Android API, network or synthetic frame duplication. */
object BurstFusion {
    private val channelShifts = intArrayOf(16, 8, 0)

    private data class Registered(
        val frame: PreparedFrame,
        val alignment: FrameAlignment,
        val mask: ByteArray,
    )

    private data class Rendered(
        val pixels: IntArray,
        val measuredFraction: Float,
        val highlights: Int,
    )

    fun fuse(
        frames: List<FusionFrame>,
        options: FusionOptions = FusionOptions(),
        onProgress: (FusionProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): FusionResult {
        require(options.maxFrames in 1..8 && options.maxShiftPixels in 1..32)
        require(options.maxLongEdge in 16..1280 && options.srMaxLongEdge in 16..960)
        require(frames.isNotEmpty() && frames.size <= options.maxFrames) {
            "Use de um a oito quadros reais."
        }
        val width = frames.first().width
        val height = frames.first().height
        val limit = if (options.superResolution) options.srMaxLongEdge else options.maxLongEdge
        require(width >= 16 && height >= 16 && max(width, height) <= limit) {
            "Reduza os quadros antes da fusão."
        }
        require(
            frames.all {
                it.width == width && it.height == height && it.pixels.size == width * height
            }
        ) {
            "Os quadros devem ter as mesmas dimensões e campo de visão."
        }
        val outputScale = if (options.superResolution) 2 else 1
        val estimate =
            width.toLong() * height * (frames.size * 9 + outputScale * outputScale * 4) +
                16L * 1024 * 1024
        require(estimate <= 192L * 1024 * 1024) { "Limite de memória da fusão excedido." }
        fun checkCancel() {
            if (isCancelled() || Thread.currentThread().isInterrupted)
                throw CancellationException("Fusão cancelada")
        }
        checkCancel()
        val prepared =
            frames.map { frame ->
                checkCancel()
                FusionRegistration.prepare(frame)
            }
        val referenceIndex = FusionRegistration.reference(prepared)
        val reference = prepared[referenceIndex]
        val registered = mutableListOf<Registered>()
        val alignments = ArrayList<FrameAlignment>()
        onProgress(FusionProgress(0, frames.size, "Alinhando"))
        for (index in frames.indices) {
            checkCancel()
            var alignment =
                if (index == referenceIndex)
                    FrameAlignment(
                        index,
                        0.0,
                        0.0,
                        1.0,
                        1.0,
                        1.0,
                        true,
                        subpixelReliable = true,
                        relativeExposure = 1.0,
                    )
                else
                    FusionRegistration.align(
                        reference,
                        prepared[index],
                        index,
                        options.maxShiftPixels,
                    )
            if (alignment.accepted) {
                val mask =
                    if (index == referenceIndex) ByteArray(width * height) { 1 }
                    else motionMask(reference, prepared[index], alignment, ::checkCancel)
                val rejected = mask.count { it.toInt() == 0 }.toFloat() / mask.size
                alignment = alignment.copy(motionRejectedFraction = rejected)
                if (index != referenceIndex && rejected > 0.45f) {
                    alignment =
                        alignment.copy(
                            accepted = false,
                            subpixelReliable = false,
                            reason = "Movimento ou exposição local incompatível",
                        )
                } else registered.add(Registered(prepared[index], alignment, mask))
            }
            alignments.add(alignment)
            onProgress(FusionProgress(index + 1, frames.size, "Alinhando"))
        }
        val phaseBins =
            registered
                .filter { it.alignment.subpixelReliable }
                .map { phase(it.alignment.dx) + 2 * phase(it.alignment.dy) }
                .toSet()
        val coverage = phaseBins.size / 4f
        val eligible =
            options.superResolution &&
                registered.size >= 4 &&
                phaseBins.size == 4 &&
                registered
                    .filter { it.alignment.subpixelReliable }
                    .all { it.alignment.motionRejectedFraction <= 0.25f }
        var superResolved = eligible
        var rendered =
            render(
                reference,
                registered,
                if (eligible) 2 else 1,
                frames.size,
                onProgress,
                ::checkCancel,
            )
        // Distinct shifts alone are insufficient if local motion removed most
        // of their measurements. Never label a predominantly interpolated image SR.
        if (superResolved && rendered.measuredFraction < 0.70f) {
            superResolved = false
            rendered = render(reference, registered, 1, frames.size, onProgress, ::checkCancel)
        }
        checkCancel()
        return FusionResult(
            rendered.pixels,
            width * (if (superResolved) 2 else 1),
            height * (if (superResolved) 2 else 1),
            referenceIndex,
            registered.size,
            alignments,
            superResolved,
            coverage,
            rendered.measuredFraction,
            rendered.highlights,
        )
    }

    private fun phase(shift: Double): Int {
        val fraction = shift - floor(shift)
        return if (fraction in 0.25..0.75) 1 else 0
    }

    private fun motionMask(
        reference: PreparedFrame,
        candidate: PreparedFrame,
        alignment: FrameAlignment,
        checkCancel: () -> Unit,
    ): ByteArray {
        val width = reference.source.width
        val height = reference.source.height
        val result = ByteArray(width * height)
        val threshold =
            FusionMath.motionThreshold(reference.noise, candidate.noise * alignment.exposureGain)
        val shorter = (alignment.relativeExposure ?: 1.0) < 0.8 || alignment.exposureGain > 1.15
        for (y in 0 until height) {
            checkCancel()
            for (x in 0 until width) {
                val xx = x + alignment.dx
                val yy = y + alignment.dy
                if (xx < 0 || yy < 0 || xx > width - 1 || yy > height - 1) continue
                val ref = reference.source.pixels[y * width + x]
                val raw = FusionMath.sample(candidate.gray, width, height, xx, yy)
                val normalized = raw * alignment.exposureGain
                val refLuma = reference.gray[y * width + x]
                val saturated =
                    maxOf(FusionMath.red(ref), FusionMath.green(ref), FusionMath.blue(ref)) >= 248
                val maxRaw =
                    maxOf(
                        FusionMath.channel(candidate.source, xx, yy, 16),
                        FusionMath.channel(candidate.source, xx, yy, 8),
                        FusionMath.channel(candidate.source, xx, yy, 0),
                    )
                if (saturated && shorter && maxRaw < 245 && normalized >= refLuma - threshold) {
                    result[y * width + x] = 2
                } else if (maxRaw < 253 && abs(normalized - refLuma) <= threshold) {
                    var compatible = true
                    for (channel in channelShifts) {
                        val base = (ref ushr channel) and 255
                        if (
                            base in 8..244 &&
                                abs(
                                    FusionMath.channel(candidate.source, xx, yy, channel) *
                                        alignment.exposureGain - base
                                ) > threshold * 1.5
                        )
                            compatible = false
                    }
                    if (compatible) result[y * width + x] = 1
                }
            }
        }
        return result
    }

    private fun recoverHighlight(
        reference: PreparedFrame,
        registered: List<Registered>,
        x: Double,
        y: Double,
        maskIndex: Int,
    ): Int? {
        val base = reference.source.pixels[maskIndex]
        if (maxOf(FusionMath.red(base), FusionMath.green(base), FusionMath.blue(base)) < 248)
            return null
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var weight = 0.0
        for (candidate in registered) {
            if (candidate.mask[maskIndex].toInt() != 2) continue
            val alignment = candidate.alignment
            val gain = alignment.exposureGain
            val xx = x + alignment.dx
            val yy = y + alignment.dy
            fun shoulder(value: Double): Double =
                if (value <= 240) value else 240 + 15 * (1 - exp(-(value - 240) / 48))
            val amount = max(0.2, alignment.confidence)
            red += shoulder(FusionMath.channel(candidate.frame.source, xx, yy, 16) * gain) * amount
            green += shoulder(FusionMath.channel(candidate.frame.source, xx, yy, 8) * gain) * amount
            blue += shoulder(FusionMath.channel(candidate.frame.source, xx, yy, 0) * gain) * amount
            weight += amount
        }
        return if (weight > 0) FusionMath.pack(red / weight, green / weight, blue / weight)
        else null
    }

    private fun render(
        reference: PreparedFrame,
        registered: List<Registered>,
        scale: Int,
        totalFrames: Int,
        onProgress: (FusionProgress) -> Unit,
        checkCancel: () -> Unit,
    ): Rendered {
        val width = reference.source.width
        val height = reference.source.height
        val outputWidth = width * scale
        val outputHeight = height * scale
        val output = IntArray(outputWidth * outputHeight)
        var measured = 0
        var highlights = 0
        for (y in 0 until outputHeight) {
            checkCancel()
            val refY = y.toDouble() / scale
            for (x in 0 until outputWidth) {
                val refX = x.toDouble() / scale
                val nativeX = refX.roundToInt().coerceIn(0, width - 1)
                val nativeY = refY.roundToInt().coerceIn(0, height - 1)
                val maskIndex = nativeY * width + nativeX
                val recovered = recoverHighlight(reference, registered, refX, refY, maskIndex)
                if (recovered != null) {
                    output[y * outputWidth + x] = recovered
                    highlights++
                    continue
                }
                var red = 0.0
                var green = 0.0
                var blue = 0.0
                var weights = 0.0
                var measuredSupport = false
                for (candidate in registered) {
                    if (candidate.mask[maskIndex].toInt() != 1) continue
                    val alignment = candidate.alignment
                    val sx = refX + alignment.dx
                    val sy = refY + alignment.dy
                    if (sx < 0 || sy < 0 || sx > width - 1 || sy > height - 1) continue
                    val confidence = max(0.5, alignment.confidence)
                    var weight =
                        confidence /
                            max(
                                1.0,
                                candidate.frame.noise *
                                    candidate.frame.noise *
                                    alignment.exposureGain *
                                    alignment.exposureGain,
                            )
                    val candidateRed: Double
                    val candidateGreen: Double
                    val candidateBlue: Double
                    if (scale == 2) {
                        // True multi-frame samples, not interpolated 2x copies:
                        // each candidate's native sample is located at (j-dx,k-dy)
                        // in reference coordinates. Close phases dominate the grid.
                        val sourceX = sx.roundToInt()
                        val sourceY = sy.roundToInt()
                        val distanceX = sourceX - alignment.dx - refX
                        val distanceY = sourceY - alignment.dy - refY
                        if (abs(distanceX) > 0.65 || abs(distanceY) > 0.65) continue
                        weight *=
                            exp(
                                -(distanceX * distanceX + distanceY * distanceY) / (2 * 0.18 * 0.18)
                            )
                        if (
                            abs(distanceX) < 0.2 &&
                                abs(distanceY) < 0.2 &&
                                alignment.subpixelReliable
                        )
                            measuredSupport = true
                        val pixel = candidate.frame.source.pixels[sourceY * width + sourceX]
                        candidateRed = FusionMath.red(pixel) * alignment.exposureGain
                        candidateGreen = FusionMath.green(pixel) * alignment.exposureGain
                        candidateBlue = FusionMath.blue(pixel) * alignment.exposureGain
                    } else {
                        candidateRed =
                            FusionMath.channel(candidate.frame.source, sx, sy, 16) *
                                alignment.exposureGain
                        candidateGreen =
                            FusionMath.channel(candidate.frame.source, sx, sy, 8) *
                                alignment.exposureGain
                        candidateBlue =
                            FusionMath.channel(candidate.frame.source, sx, sy, 0) *
                                alignment.exposureGain
                    }
                    red += candidateRed * weight
                    green += candidateGreen * weight
                    blue += candidateBlue * weight
                    weights += weight
                }
                if (weights > 1e-9)
                    output[y * outputWidth + x] =
                        FusionMath.pack(red / weights, green / weights, blue / weights)
                else
                    output[y * outputWidth + x] =
                        FusionMath.pack(
                            FusionMath.channel(reference.source, refX, refY, 16),
                            FusionMath.channel(reference.source, refX, refY, 8),
                            FusionMath.channel(reference.source, refX, refY, 0),
                        )
                if (measuredSupport) measured++
            }
            if (y % 16 == 0 || y == outputHeight - 1)
                onProgress(
                    FusionProgress(totalFrames, totalFrames, "Fundindo", y + 1, outputHeight)
                )
        }
        return Rendered(
            output,
            if (scale == 2) measured.toFloat() / output.size else 0f,
            highlights,
        )
    }
}
