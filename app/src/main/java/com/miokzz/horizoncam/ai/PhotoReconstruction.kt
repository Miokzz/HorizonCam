package com.miokzz.horizoncam.ai

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class PhotoPrior(val asset: String, val hash: String, val display: String, val halo: Int) {
    FAST("ai/QuickSRNet-small-x4-128.tflite", "523609151d17484a0bfef7d60a2e9ddd762604138bf3067b9248aa436f831f3f", "QuickSRNet-small", 4),
    MAX("ai/RealESRGAN-context-f16.tflite", "1d8860d0608528a669562b431c654ac1616ab943ae4048b27b6211381b3fd869", "Real-ESRGAN general-x4v3", 34);
    val core get() = 128 - 2 * halo
}

data class SceneEvidence(val noise: Float, val texture: Float, val sharpFraction: Float, val meanY: Float,
    val shutterSeconds: Double, val iso: Int)

/** The reference is the captured window. Resolution selection never changes its origin or field. */
data class ReconstructionPlan(val prior: PhotoPrior, val guideW: Int, val guideH: Int, val neuralScale: Int) {
    val tileCount get() = ceil(guideW.toDouble() / prior.core).toInt() * ceil(guideH.toDouble() / prior.core).toInt()
    companion object {
        fun choose(w: Int, h: Int, outW: Int, outH: Int, zoom: Float, scene: SceneEvidence): ReconstructionPlan {
            val dark = scene.iso >= 900 || scene.noise >= 3.4f || (scene.meanY < 65f && scene.shutterSeconds >= 1.0/40)
            val degraded = zoom >= 4f || dark || (zoom >= 2f && scene.sharpFraction < 0.18f && scene.texture > 0.08f)
            val prior = if (degraded) PhotoPrior.MAX else PhotoPrior.FAST
            val limit = when {
                prior == PhotoPrior.FAST -> 1152
                zoom >= 8f -> 656
                else -> 560
            }
            val ratio = min(1.0, limit.toDouble() / max(w,h))
            val gw = (w * ratio + 0.5).toInt().coerceAtLeast(1)
            val gh = (h * ratio + 0.5).toInt().coerceAtLeast(1)
            val scale = when { max(outW,outH) > 2 * max(gw,gh) -> 4; max(outW,outH) > max(gw,gh) -> 2; else -> 1 }
            return ReconstructionPlan(prior,gw,gh,scale)
        }
    }
}

/** Local gradients are calculated on original upright pixels at the output sampling scale.
 * The residual against the measured low pass transfers neural content while retaining native
 * structure. Color/chroma is constrained using local signal, not a single global blend.
 */
object DetailTransfer {
    fun pixel(base: Int, low: Int, prediction: Int, leftY: Int, rightY: Int, upY: Int, downY: Int,
              zoom: Float, scene: SceneEvidence, blind: Boolean): Int {
        val y = y(base)
        val lx = abs(leftY-rightY)
        val ly = abs(upY-downY)
        val contrast = max(lx,ly)
        val lap = abs(4*y-leftY-rightY-upY-downY)
        val highFrequency = y - y(low)
        val r = (base ushr 16) and 255
        val g = (base ushr 8) and 255
        val b = base and 255
        val skin = r >= g && g >= b && r-b in 16..104 && r >= 56 && g-b < 50
        val hardEdge = contrast > 42 || abs(highFrequency) > 27
        val reliable = hardEdge && lap > 12
        val predictionY = y(prediction)
        val delta = predictionY - y(low)
        val noise = (scene.noise / 7f + scene.iso / 4200f).coerceIn(0f,1f)
        val strength = when {
            reliable -> if (skin) 0.28f else 0.40f
            skin -> 0.43f + 0.10f*noise
            blind -> (0.65f + 0.16f * min(zoom/10f,1f) + 0.10f*noise)
            else -> 0.50f + 0.12f * noise
        }
        val cap = when {
            skin -> 17
            reliable -> 24
            contrast < 3 && abs(highFrequency) < 2 && abs(delta) > 32 -> 22
            blind -> 62
            else -> 38
        }
        val remove = when {
            reliable -> 0.04f
            skin -> 0.12f + 0.20f*noise
            blind -> 0.20f + 0.42f*noise
            else -> 0.08f + 0.32f*noise
        }
        val correction = delta.coerceIn(-cap,cap) * strength - highFrequency * remove
        fun channel(value: Int, shift: Int): Int {
            val predicted = (prediction ushr shift) and 255
            val originalLow = (low ushr shift) and 255
            val chroma = (predicted-originalLow-delta).coerceIn(-14,14)
            return (value + correction + strength*0.46f*chroma + 0.5f).toInt().coerceIn(0,255)
        }
        return (255 shl 24) or (channel(r,16) shl 16) or (channel(g,8) shl 8) or channel(b,0)
    }

    fun y(pixel: Int): Int = (77 * ((pixel ushr 16) and 255) + 150 * ((pixel ushr 8) and 255) + 29 * (pixel and 255) + 128) shr 8
}
