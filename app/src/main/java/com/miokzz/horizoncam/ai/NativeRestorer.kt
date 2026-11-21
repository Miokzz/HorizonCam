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
    data class Result(val bitmap: Bitmap, val modelWidth: Int, val modelHeight: Int, val elapsedMs: Long,
        val model: String, val backend: String, val tiles: Int, val inferenceMs: Long, val scene: SceneEvidence)

    fun restore(context: Context, original: Bitmap, zoom: Float, iso: Int, upscale: Int,
                onProgress: (AiProgress) -> Unit, cancelled: () -> Boolean, exposureSeconds: Double = 0.0): Result {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(upscale == 1 || upscale == 2 || upscale == 4)
        val width = original.width * upscale
        val height = original.height * upscale
        require(width.toLong() * height <= MAX_PIXELS)
        val start = System.nanoTime()
        val scene = analyze(original, iso, exposureSeconds)
        val plan = ReconstructionPlan.choose(original.width,original.height,width,height,zoom,scene)
        val guide = if (plan.guideW == original.width && plan.guideH == original.height) original
            else Bitmap.createScaledBitmap(original,plan.guideW,plan.guideH,true)
        // Whole scene uses the same normalized coordinates at every scale. Each tile owns the
        // valid interior of its overlapping input support (overlap-save, no squared weight map).
        val neural = Bitmap.createBitmap(plan.guideW * plan.neuralScale,plan.guideH * plan.neuralScale,Bitmap.Config.ARGB_8888)
        val stripes = (height + BAND - 1)/BAND
        val total = plan.tileCount + stripes
        fun checkActive() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException() }
        try {
            onProgress(AiProgress(0,total))
            var backend = ""
            val inferenceStart = System.nanoTime()
            InferenceRuntime.run(context,plan.prior) { engine ->
                backend = engine.backend
                val inputValues = FloatArray(128*128*3)
                val outputValues = FloatArray(512*512*3)
                val row = IntArray(128)
                val predicted = IntArray(plan.prior.core * plan.prior.core * plan.neuralScale * plan.neuralScale)
                var finished = 0
                for (y in 0 until guide.height step plan.prior.core) for (x in 0 until guide.width step plan.prior.core) {
                    checkActive()
                    val ix = IntArray(128) { AiGeometry.mirror(x - plan.prior.halo + it, guide.width) }
                    val minX=ix.min()
                    val span=ix.max()-minX+1
                    for (j in 0 until 128) {
                        val sourceY=AiGeometry.mirror(y-plan.prior.halo+j,guide.height)
                        guide.getPixels(row,0,span,minX,sourceY,span,1)
                        var offset = j*128*3
                        for (i in 0 until 128) {
                            val c = row[ix[i]-minX]
                            inputValues[offset++] = (c ushr 16 and 255).toFloat()
                            inputValues[offset++] = (c ushr 8 and 255).toFloat()
                            inputValues[offset++] = (c and 255).toFloat()
                        }
                    }
                    engine.rgb.rewind()
                    engine.rgb.put(inputValues)
                    engine.invoke()
                    engine.prediction.rewind()
                    engine.prediction.get(outputValues)
                    val w=minOf(plan.prior.core,guide.width-x)*plan.neuralScale
                    val h=minOf(plan.prior.core,guide.height-y)*plan.neuralScale
                    val stride=4/plan.neuralScale
                    for (j in 0 until h) for (i in 0 until w) {
                        val begin=((j*stride+plan.prior.halo*4)*512+i*stride+plan.prior.halo*4)*3
                        if (stride == 1) {
                            predicted[j*w+i] = AiColor.pack(outputValues[begin],outputValues[begin+1],outputValues[begin+2])
                            continue
                        }
                        var rr=0f; var gg=0f; var bb=0f
                        for (sy in 0 until stride) for (sx in 0 until stride) {
                            val pos=begin+(sy*512+sx)*3
                            rr+=outputValues[pos]; gg+=outputValues[pos+1]; bb+=outputValues[pos+2]
                        }
                        val n=stride*stride
                        predicted[j*w+i]=AiColor.pack(rr/n,gg/n,bb/n)
                    }
                    neural.setPixels(predicted,0,w,x*plan.neuralScale,y*plan.neuralScale,w,h)
                    onProgress(AiProgress(++finished,total))
                }
            }
            val ms=(System.nanoTime()-inferenceStart)/1_000_000
            val result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            try {
                compose(original,guide,neural,result,scene,zoom,plan.prior==PhotoPrior.MAX) { stripe ->
                    checkActive(); onProgress(AiProgress(plan.tileCount+stripe,total))
                }
                return Result(result,guide.width,guide.height,(System.nanoTime()-start)/1_000_000,
                    plan.prior.display,backend,plan.tileCount,ms,scene)
            } catch (t: Throwable) { result.recycle(); throw t }
        } finally { neural.recycle(); if (guide !== original) guide.recycle() }
    }

    private const val BAND=48
    private fun compose(source: Bitmap, low: Bitmap, prediction: Bitmap, out: Bitmap, scene: SceneEvidence,
                        zoom: Float, blind: Boolean, progress: (Int)->Unit) {
        val w=out.width
        val rows=Bitmap.createBitmap(w,BAND+2,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(rows)
            val filter=Paint(Paint.FILTER_BITMAP_FLAG)
            val measured=IntArray(w*(BAND+2))
            val lp=IntArray(w*BAND)
            val prior=IntArray(w*BAND)
            val result=IntArray(w*BAND)
            val lum=IntArray(measured.size)
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
                read(low,y,h,lp)
                read(prediction,y,h,prior)
                for (j in 0 until h) {
                    val row=(j+y-top)*w
                    val prev=max(0,j+y-top-1)*w
                    val next=minOf(bottom-top-1,j+y-top+1)*w
                    for (x in 0 until w) {
                        val i=j*w+x
                        result[i]=DetailTransfer.pixel(measured[row+x],lp[i],prior[i],lum[row+max(0,x-1)],
                            lum[row+minOf(w-1,x+1)],lum[prev+x],lum[next+x],zoom,scene,blind)
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
