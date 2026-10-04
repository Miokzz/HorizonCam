package com.miokzz.horizoncam

import android.graphics.Matrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation

@UnstableApi
class HorizonMatrixEffect(
    private val state: HorizonState
) : MatrixTransformation {

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val correction = state.correctionDegrees()
        val scale = state.safeCropScale()

        // MatrixTransformation operates in normalized device coordinates.
        // Keep this deliberately simple: rotate around origin, then uniformly
        // enlarge enough to keep the output frame covered.
        return Matrix().apply {
            postRotate(correction)
            postScale(scale, scale)
        }
    }
}
