package com.miokzz.horizoncam

import com.miokzz.horizoncam.ui.PreviewFitTransform
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class PreviewFitTransformTest {
    @Test fun fitMapsEveryRotatedCropCornerInsideViewWithoutChangingPixelDistances() {
        for ((bw,bh) in listOf(Pair(1920,1080),Pair(1080,1920),Pair(1440,1080),Pair(1080,1440))) {
            for ((vw,vh) in listOf(Pair(800,450),Pair(360,640),Pair(400,300))) {
                val crop = PreviewFitTransform.Crop(bw*0.1,bh*0.15,bw*0.9,bh*0.85)
                for (rotation in listOf(0,90,180,270)) {
                    val fit = PreviewFitTransform.calculate(bw,bh,crop,rotation,vw,vh)
                    for (x in listOf(crop.left,crop.right)) for (y in listOf(crop.top,crop.bottom)) {
                        val p = fit.bufferToView.map(x,y)
                        assertTrue("corner=$p", p.first >= -0.00001 && p.first <= vw+0.00001)
                        assertTrue("corner=$p", p.second >= -0.00001 && p.second <= vh+0.00001)
                        assertTrue(kotlin.math.abs(p.first-fit.visible.left)<0.00001 ||
                            kotlin.math.abs(p.first-fit.visible.right)<0.00001)
                        assertTrue(kotlin.math.abs(p.second-fit.visible.top)<0.00001 ||
                            kotlin.math.abs(p.second-fit.visible.bottom)<0.00001)
                    }
                    val center = fit.bufferToView.map((crop.left+crop.right)/2,(crop.top+crop.bottom)/2)
                    assertEquals(vw/2.0,center.first,0.00001)
                    assertEquals(vh/2.0,center.second,0.00001)
                    val a = fit.bufferToView.map(500.0,500.0)
                    val b = fit.bufferToView.map(600.0,500.0)
                    val c = fit.bufferToView.map(500.0,600.0)
                    assertEquals(hypot(b.first-a.first,b.second-a.second),
                        hypot(c.first-a.first,c.second-a.second),0.00001)
                }
            }
        }
    }

    @Test fun remainingQuarterTurnsHaveCorrectClockwiseDirection() {
        val crop = PreviewFitTransform.Crop(0.0,0.0,1080.0,1920.0)
        val expectedRight = mapOf(0 to Pair(1,0),90 to Pair(0,1),180 to Pair(-1,0),270 to Pair(0,-1))
        for ((rotation,expected) in expectedRight) {
            val f = PreviewFitTransform.calculate(1080,1920,crop,rotation,800,450)
            val center = f.bufferToView.map(540.0,960.0)
            val right = f.bufferToView.map(640.0,960.0)
            val dx = right.first-center.first
            val dy = right.second-center.second
            if (expected.first == 0) assertEquals(0.0,dx,0.00001) else assertTrue(dx*expected.first > 0)
            if (expected.second == 0) assertEquals(0.0,dy,0.00001) else assertTrue(dy*expected.second > 0)
        }
    }

    @Test fun actualTextureViewStretchAndImplicitCameraTransformAreUndoneExactlyOnce() {
        for ((bw,bh) in listOf(Pair(1920,1080),Pair(1080,1920))) {
            for (rotation in listOf(0,90,180,270)) {
                for (implicitCamera in listOf<Int?>(null,rotation+90)) {
                    val crop = PreviewFitTransform.Crop(20.0,40.0,bw-20.0,bh-40.0)
                    val f = PreviewFitTransform.calculate(bw,bh,crop,rotation,800,450,implicitCamera)
                    for ((x,y) in listOf(Pair(80.0,90.0),Pair(bw/2.0,bh/2.0),Pair(bw-60.0,bh-70.0))) {
                        val native = implicitCamera?.let {
                            PreviewFitTransform.implicitCameraTransform(bw,bh,it).map(x,y)
                        } ?: Pair(x,y)
                        // TextureView default stretches the buffer across all view pixels.
                        val stretched = Pair(native.first*800/bw,native.second*450/bh)
                        val actual = f.textureViewTransform.map(stretched.first,stretched.second)
                        val expected = f.bufferToView.map(x,y)
                        assertEquals(expected.first,actual.first,0.00001)
                        assertEquals(expected.second,actual.second,0.00001)
                    }
                }
            }
        }
    }
}
