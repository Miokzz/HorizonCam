package com.miokzz.horizoncam

import android.graphics.Matrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import kotlin.math.cos
import kotlin.math.sin

@UnstableApi
class HorizonMatrixEffect(
    private val state: HorizonState,
    private val aspectRatio: Float = 16f / 9f
) : MatrixTransformation {

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val degrees = state.correctionDegrees()
        val radians = Math.toRadians(degrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val scale = state.safeCropScale(aspectRatio)

        return Matrix().apply {
            setValues(
                floatArrayOf(
                    scale * c, -scale * s / aspectRatio, 0f,
                    scale * s * aspectRatio, scale * c, 0f,
                    0f, 0f, 1f
                )
            )
        }
    }
}
