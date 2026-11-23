package com.miokzz.horizoncam.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max

object NativeRestorer {
    const val MAX_PIXELS = 12_600_000
    /** Close the result before recycling the source. A native result borrows that source. */
    data class Result(val bitmap: Bitmap, val modelWidth: Int, val modelHeight: Int, val elapsedMs: Long,
        val model: String, val backend: String, val tiles: Int, val inferenceMs: Long, val scene: SceneEvidence,
        val nativeInferenceMs: Long) : AutoCloseable {
        val usedNeuralModel get() = tiles > 0
        override fun close() {
            if (usedNeuralModel) bitmap.recycle()
        }
    }

    fun restore(context: Context, original: Bitmap, zoom: Float, iso: Int, upscale: Int,
                onProgress: (AiProgress) -> Unit, cancelled: () -> Boolean, exposureSeconds: Double = 0.0,
                protectLocalStructure: Boolean = false,
                semanticProtection: ((xNormalized: Float, yNormalized: Float) -> Float)? = null): Result {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(upscale == 1 || upscale == 2 || upscale == 4)
        val width = original.width * upscale
        val height = original.height * upscale
        require(width.toLong() * height <= MAX_PIXELS)
        fun checkActive() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException() }
        checkActive()
        val start = System.nanoTime()
        val scene = analyze(original, iso, exposureSeconds)
        if (upscale == 1 && scene.preserveNative(zoom)) {
            onProgress(AiProgress(0, 1))
            checkActive()
            onProgress(AiProgress(1, 1))
            checkActive()
            return Result(original, 0, 0, (System.nanoTime()-start)/1_000_000,
                "Detalhe nativo", "Nenhum", 0, 0, scene, 0)
        }
        val plan = ReconstructionPlan.choose(original.width,original.height,width,height,zoom,scene)
        val guide = if (plan.guideW == original.width && plan.guideH == original.height) original
            else Bitmap.createScaledBitmap(original,plan.guideW,plan.guideH,true)
        // Whole scene uses the same normalized coordinates at every scale. Each tile owns the
        // valid interior of its overlapping input support (overlap-save, no squared weight map).
        var assembled: Bitmap? = null
        var reduced: Bitmap? = null
        val stripes = (height + BAND - 1)/BAND
        val total = plan.tileCount + stripes
        try {
            val neural = Bitmap.createBitmap(plan.guideW * plan.neuralScale,
                plan.guideH * plan.neuralScale, Bitmap.Config.ARGB_8888).also { assembled = it }
            // Only anchor to the original when the prior has the same guide and capture grids.
            val priorMean = if (upscale > 1 && guide === original) {
                Bitmap.createBitmap(guide.width, guide.height, Bitmap.Config.ARGB_8888).also { reduced = it }
            } else null
            onProgress(AiProgress(0,total))
            var backend = ""
            var nativeNs = 0L
            var reportedTiles = 0
            val inferenceStart = System.nanoTime()
            InferenceRuntime.run(context,plan.prior,::checkActive) { engine, ensureActive ->
                backend = engine.backend.label
                val inputEdge = InferenceRuntime.INPUT_EDGE
                val outputEdge = InferenceRuntime.OUTPUT_EDGE
                val inputValues = FloatArray(inputEdge * inputEdge * 3)
                val tilePixels = IntArray(inputEdge * inputEdge)
                val stride = 4 / plan.neuralScale
                val outputRows = FloatArray(plan.prior.core * 4 * 3 * stride)
                val predicted = IntArray(plan.prior.core * plan.prior.core * plan.neuralScale * plan.neuralScale)
                val means = if (priorMean != null) IntArray(plan.prior.core * plan.prior.core) else null
                var finished = 0
                for (y in 0 until guide.height step plan.prior.core) {
                    val iy = IntArray(inputEdge) { AiGeometry.mirror(y - plan.prior.halo + it, guide.height) }
                    val minY = iy.min()
                    val spanY = iy.max() - minY + 1
                    for (x in 0 until guide.width step plan.prior.core) {
                        ensureActive()
                        val ix = IntArray(inputEdge) { AiGeometry.mirror(x - plan.prior.halo + it, guide.width) }
                        val minX = ix.min()
                        val spanX = ix.max() - minX + 1
                        guide.getPixels(tilePixels, 0, spanX, minX, minY, spanX, spanY)
                        var offset = 0
                        for (sourceY in iy) {
                            val row = (sourceY - minY) * spanX - minX
                            for (sourceX in ix) {
                                val pixel = tilePixels[row + sourceX]
                                inputValues[offset++] = (pixel ushr 16 and 255).toFloat()
                                inputValues[offset++] = (pixel ushr 8 and 255).toFloat()
                                inputValues[offset++] = (pixel and 255).toFloat()
                            }
                        }
                        engine.rgb.clear()
                        engine.rgb.put(inputValues)
                        val output = engine.invoke()
                        nativeNs += engine.nativeNs
                        ensureActive()
                        val w = minOf(plan.prior.core, guide.width - x) * plan.neuralScale
                        val h = minOf(plan.prior.core, guide.height - y) * plan.neuralScale
                        val left = plan.prior.halo * 4
                        val rowSize = w * stride * 3
                        for (j in 0 until h) {
                            for (sy in 0 until stride) {
                                output.position(((j * stride + sy + left) * outputEdge + left) * 3)
                                output.get(outputRows, sy * rowSize, rowSize)
                            }
                            for (i in 0 until w) {
                                val start = i * stride * 3
                                if (stride == 1) {
                                    predicted[j * w + i] = packPrediction(outputRows[start], outputRows[start + 1], outputRows[start + 2])
                                } else {
                                    var red = 0f
                                    var green = 0f
                                    var blue = 0f
                                    for (sy in 0 until stride) for (sx in 0 until stride) {
                                        val pos = start + sy * rowSize + sx * 3
                                        red += outputRows[pos]
                                        green += outputRows[pos + 1]
                                        blue += outputRows[pos + 2]
                                    }
                                    val area = stride * stride
                                    predicted[j * w + i] = packPrediction(red / area, green / area, blue / area)
                                }
                            }
                        }
                        neural.setPixels(predicted, 0, w, x * plan.neuralScale, y * plan.neuralScale, w, h)
                        if (priorMean != null && means != null) {
                            val scale = plan.neuralScale
                            meanLuminance(predicted, w, h, scale, means)
                            priorMean.setPixels(means, 0, w / scale, x, y, w / scale, h / scale)
                        }
                        finished++
                        if (finished > reportedTiles) {
                            reportedTiles = finished
                            onProgress(AiProgress(finished, total))
                        }
                    }
                }
            }
            checkActive()
            val ms=(System.nanoTime()-inferenceStart)/1_000_000
            val result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            try {
                compose(original,guide,neural,priorMean,result,scene,zoom,plan.prior==PhotoPrior.MAX,
                    protectLocalStructure, semanticProtection) { stripe ->
                    checkActive(); onProgress(AiProgress(plan.tileCount+stripe,total))
                }
                return Result(result,guide.width,guide.height,(System.nanoTime()-start)/1_000_000,
                    plan.prior.display,backend,plan.tileCount,ms,scene,nativeNs/1_000_000)
            } catch (t: Throwable) { result.recycle(); throw t }
        } finally {
            reduced?.recycle()
            assembled?.recycle()
            if (guide !== original) guide.recycle()
        }
    }

    /** Average the integer output on each original pixel footprint without crossing a tile seam. */
    private fun meanLuminance(pixels: IntArray, width: Int, height: Int, scale: Int, out: IntArray) {
        val area = scale * scale
        var i = 0
        for (y in 0 until height step scale) for (x in 0 until width step scale) {
            var sum = 0
            for (sy in y until y + scale) for (sx in x until x + scale) {
                sum += DetailTransfer.y(pixels[sy * width + sx])
            }
            val gray = (sum + area / 2) / area
            out[i++] = (255 shl 24) or (gray * 0x010101)
        }
    }

    private fun packPrediction(red: Float, green: Float, blue: Float): Int {
        if (!red.isFinite() || !green.isFinite() || !blue.isFinite()) {
            throw InferenceRuntime.InferenceFailure("Modelo produziu pixels inválidos")
        }
        return AiColor.pack(red, green, blue)
    }

    private const val BAND=48
    private fun compose(source: Bitmap, low: Bitmap, prediction: Bitmap, priorMean: Bitmap?, out: Bitmap, scene: SceneEvidence,
                        zoom: Float, blind: Boolean, protectLocalStructure: Boolean,
                        semanticProtection: ((Float, Float) -> Float)?, progress: (Int)->Unit) {
        val w=out.width
        val rows=Bitmap.createBitmap(w,BAND+2,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(rows)
            val nativeSampling = source.width == out.width && source.height == out.height
            val filter=Paint(Paint.FILTER_BITMAP_FLAG)
            val measured=IntArray(w*(BAND+2))
            val lp = if (low !== source) IntArray(w * BAND) else null
            val lowPixels = lp ?: measured
            val prior=IntArray(w*BAND)
            val mean = priorMean?.let { IntArray(w * BAND) }
            val result=IntArray(w*BAND)
            val lum=IntArray(measured.size)
            val sourceScale = out.width / source.width
            val sourceRows = if(nativeSampling) 0 else (BAND+sourceScale-1)/sourceScale+3
            val sourcePixels = IntArray(source.width*sourceRows)
            val sourceLum = IntArray(sourcePixels.size)
            val envelopeMin = IntArray(sourcePixels.size)
            val envelopeMax = IntArray(sourcePixels.size)
            val structures = if (protectLocalStructure && !nativeSampling) {
                Array(sourcePixels.size) { DetailTransfer.LocalStructure.UNKNOWN }
            } else null
            val semanticWeights = if (semanticProtection != null && !nativeSampling) FloatArray(sourcePixels.size) else null
            val noiseFloor = (scene.noise * 3f).toInt().coerceIn(8, 24)
            fun read(map: Bitmap, y: Int, h: Int, buf: IntArray) {
                canvas.drawBitmap(map,null,RectF(0f,-y.toFloat(),w.toFloat(),(out.height-y).toFloat()),filter)
                rows.getPixels(buf,0,w,0,0,w,h)
            }
            var band=0
            for (y in 0 until out.height step BAND) {
                val h=minOf(BAND,out.height-y)
                val top=maxOf(0,y-1)
                val bottom=minOf(out.height,y+h+1)
                read(source,top,bottom-top,measured)
                for (i in 0 until (bottom-top)*w) lum[i]=DetailTransfer.y(measured[i])
                if (lp != null) read(low,y,h,lp)
                read(prediction,y,h,prior)
                if (priorMean != null && mean != null) read(priorMean, y, h, mean)
                val sourceTop = if(nativeSampling) 0 else maxOf(0,y/sourceScale-1)
                if(!nativeSampling) {
                    val sourceBottom = minOf(source.height,(y+h-1)/sourceScale+2)
                    val count = sourceBottom-sourceTop
                    source.getPixels(sourcePixels,0,source.width,0,sourceTop,source.width,count)
                    for(i in 0 until count*source.width) sourceLum[i]=DetailTransfer.y(sourcePixels[i])
                    for(sy in 0 until count) for(sx in 0 until source.width) {
                        var lo=255
                        var hi=0
                        for(dy in -1..1) {
                            val sourceRow=(sy+dy).coerceIn(0,count-1)*source.width
                            for(dx in -1..1) {
                                val value=sourceLum[sourceRow+(sx+dx).coerceIn(0,source.width-1)]
                                lo=minOf(lo,value)
                                hi=maxOf(hi,value)
                            }
                        }
                        val index=sy*source.width+sx
                        envelopeMin[index]=lo
                        envelopeMax[index]=hi
                        if (structures != null) {
                            val above = maxOf(0, sy - 1) * source.width
                            val below = minOf(count - 1, sy + 1) * source.width
                            val left = maxOf(0, sx - 1)
                            val right = minOf(source.width - 1, sx + 1)
                            structures[index] = DetailTransfer.structure(sourceLum[above + left],
                                sourceLum[above + right], sourceLum[below + left], sourceLum[below + right],
                                hi - lo, noiseFloor)
                        }
                        if (semanticProtection != null && semanticWeights != null) {
                            val weight = semanticProtection((sx + 0.5f) / source.width,
                                (sourceTop + sy + 0.5f) / source.height)
                            semanticWeights[index] = if (weight.isFinite()) weight.coerceIn(0f, 1f) else 0f
                        }
                    }
                }
                for (j in 0 until h) {
                    val row=(j+y-top)*w
                    val lowRow = if (lp == null) row else j * w
                    val prev=max(0,j+y-top-1)*w
                    val next=minOf(bottom-top-1,j+y-top+1)*w
                    for (x in 0 until w) {
                        val i=j*w+x
                        val envelopeIndex = if(nativeSampling) 0 else
                            ((y+j)/sourceScale-sourceTop)*source.width+x/sourceScale
                        result[i]=DetailTransfer.pixel(measured[row+x],lowPixels[lowRow+x],prior[i],lum[row+max(0,x-1)],
                            lum[row+minOf(w-1,x+1)],lum[prev+x],lum[next+x],zoom,scene,blind,nativeSampling,
                            if(nativeSampling) 0 else envelopeMin[envelopeIndex],
                            if(nativeSampling) 255 else envelopeMax[envelopeIndex],
                            if (mean == null) -1 else mean[i] and 255,
                            structures?.get(envelopeIndex) ?: DetailTransfer.LocalStructure.UNKNOWN,
                            semanticWeights?.get(envelopeIndex) ?: 0f)
                    }
                }
                out.setPixels(result,0,w,0,y,w,h)
                progress(++band)
            }
        } finally { rows.recycle() }
    }

    /** Observe native pixel neighborhoods across the image; a resized thumbnail hides ISP noise. */
    private fun analyze(b: Bitmap, iso: Int, seconds: Double): SceneEvidence {
        val side=minOf(b.width,b.height,15)
        if (side<3) return SceneEvidence(0f,0f,0f,100f,seconds,iso)
        val patch=IntArray(side*side)
        val noiseHist=IntArray(256)
        var flat=0; var textured=0; var sharp=0; var samples=0; var sum=0L
        for (gy in 0..7) for (gx in 0..7) {
            b.getPixels(patch,0,side,(b.width-side)*gx/7,(b.height-side)*gy/7,side,side)
            for (y in 1 until side-1) for (x in 1 until side-1) {
                val i=y*side+x
                val center=DetailTransfer.y(patch[i])
                val l=DetailTransfer.y(patch[i-1]); val r=DetailTransfer.y(patch[i+1])
                val u=DetailTransfer.y(patch[i-side]); val d=DetailTransfer.y(patch[i+side])
                val grad=abs(r-l)+abs(d-u)
                val lap=abs(4*center-l-r-u-d)
                if (grad<18) { noiseHist[minOf(255,lap)]++; flat++ }
                if (grad>8) { textured++; if (lap>8 && grad>20) sharp++ }
                sum+=center; samples++
            }
        }
        var median=0; var n=0
        while (median<255 && n+noiseHist[median]<flat/2) { n+=noiseHist[median]; median++ }
        return SceneEvidence(if (flat>0) median/3.016f else 0f,textured.toFloat()/samples,
            sharp.toFloat()/max(1,textured),sum.toFloat()/samples,seconds,iso)
    }
}
