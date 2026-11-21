package com.miokzz.horizoncam.ui

import kotlin.math.min

/** Pixel-space FIT mapping; independent of Android for deterministic geometry tests. */
object PreviewFitTransform {
    data class Crop(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width get() = right - left
        val height get() = bottom - top
    }
    data class Affine(val a: Double, val b: Double, val tx: Double,
                      val c: Double, val d: Double, val ty: Double) {
        fun map(x: Double, y: Double) = Pair(a*x + b*y + tx, c*x + d*y + ty)
        fun values() = floatArrayOf(a.toFloat(), b.toFloat(), tx.toFloat(),
            c.toFloat(), d.toFloat(), ty.toFloat(), 0f, 0f, 1f)
        fun times(o: Affine) = Affine(a*o.a+b*o.c, a*o.b+b*o.d, a*o.tx+b*o.ty+tx,
            c*o.a+d*o.c, c*o.b+d*o.d, c*o.tx+d*o.ty+ty)
        fun inverse(): Affine {
            val determinant = a*d-b*c
            require(kotlin.math.abs(determinant) > 1e-12)
            return Affine(d/determinant, -b/determinant, (b*ty-d*tx)/determinant,
                -c/determinant, a/determinant, (c*tx-a*ty)/determinant)
        }
    }
    data class Fit(val bufferToView: Affine, val textureViewTransform: Affine, val visible: Crop)

    private fun quarterTurn(degrees: Int): Pair<Double, Double> = when (((degrees % 360)+360)%360) {
        0 -> Pair(1.0, 0.0)
        90 -> Pair(0.0, 1.0)
        180 -> Pair(-1.0, 0.0)
        270 -> Pair(0.0, -1.0)
        else -> error("CameraX remaining rotation must be a quarter turn")
    }

    /** Rotation of pixels inside a buffer whose outer rectangle keeps its size. */
    fun implicitCameraTransform(width: Int, height: Int, degrees: Int): Affine {
        val (co, si) = quarterTurn(degrees)
        val swap = co == 0.0
        val sx = if (swap) width.toDouble()/height else 1.0
        val sy = if (swap) height.toDouble()/width else 1.0
        val a = co*sx; val b = -si*sx; val c = si*sy; val d = co*sy
        return Affine(a,b,width/2.0-a*width/2.0-b*height/2.0,
            c,d,height/2.0-c*width/2.0-d*height/2.0)
    }

    fun calculate(bufferWidth: Int, bufferHeight: Int, crop: Crop,
                  rotationDegrees: Int, viewWidth: Int, viewHeight: Int,
                  cameraTransformDegrees: Int? = null): Fit {
        require(bufferWidth > 0 && bufferHeight > 0 && viewWidth > 0 && viewHeight > 0)
        require(crop.width > 0 && crop.height > 0)
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= bufferWidth && crop.bottom <= bufferHeight)
        val (co, si) = quarterTurn(rotationDegrees)
        val rotatedWidth = if (co == 0.0) crop.height else crop.width
        val rotatedHeight = if (co == 0.0) crop.width else crop.height
        val scale = min(viewWidth/rotatedWidth, viewHeight/rotatedHeight)
        val cx = (crop.left+crop.right)/2.0
        val cy = (crop.top+crop.bottom)/2.0
        val desired = Affine(scale*co, -scale*si, viewWidth/2.0-scale*co*cx+scale*si*cy,
            scale*si, scale*co, viewHeight/2.0-scale*si*cx-scale*co*cy)
        // TextureView initially stretches the full buffer to the view bounds.
        // Undo that before applying an isotropic, centered crop/rotation/FIT.
        val undoStretch = Affine(bufferWidth.toDouble()/viewWidth,0.0,0.0,
            0.0,bufferHeight.toDouble()/viewHeight,0.0)
        val undoCamera = cameraTransformDegrees?.let {
            implicitCameraTransform(bufferWidth, bufferHeight, it).inverse()
        } ?: Affine(1.0,0.0,0.0,0.0,1.0,0.0)
        val imageWidth = rotatedWidth*scale
        val imageHeight = rotatedHeight*scale
        val visible = Crop((viewWidth-imageWidth)/2.0,(viewHeight-imageHeight)/2.0,
            (viewWidth+imageWidth)/2.0,(viewHeight+imageHeight)/2.0)
        return Fit(desired, desired.times(undoCamera).times(undoStretch), visible)
    }
}
