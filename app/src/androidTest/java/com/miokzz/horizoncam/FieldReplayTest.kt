package com.miokzz.horizoncam

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.miokzz.horizoncam.ai.NativeRestorer
import com.miokzz.horizoncam.bench.LegacyRestorer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional, private, matched-input replay. Caller stages a consented photo in app files.
 * No gallery rows. Input is already framed; zoom modifies restoration policy only.
 */
class FieldReplayTest {
    @Test fun measurePrivateCameraFrame() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val file = File(ctx.filesDir,"quality-source.jpg")
        assumeTrue(args.getString("replay") == "yes" && file.isFile)
        val zoom = args.getString("zoom")?.toFloatOrNull() ?: 1f
        val iso = args.getString("iso")?.toIntOrNull()?.also { require(it > 0) } ?: 400
        val scale = args.getString("upscale")?.toIntOrNull()?.also { require(it in setOf(1, 2, 4)) } ?: 1
        val contextual = args.getString("structure") == "yes"
        val base = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder,_,_ ->
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val legacy = args.getString("legacy") == "yes"
        require(!legacy || !contextual)
        try {
            val start = System.nanoTime()
            var tiles=0
            var model="general-x4v3-50"
            var backend="CPU-2"
            var neuralMs: Long?=null
            var invokeMs: Long?=null
            val result = if (legacy) LegacyRestorer.restore(InstrumentationRegistry.getInstrumentation().context,base,
                zoom,iso,scale,{tiles=it.totalTiles},{false}).bitmap
            else NativeRestorer.restore(ctx,base,zoom,iso,scale,{}, {false}, protectLocalStructure = contextual).let {
                tiles=it.tiles; model=it.model; backend=it.backend
                neuralMs=it.inferenceMs; invokeMs=it.nativeInferenceMs
                it.bitmap
            }
            try {
                val ms=(System.nanoTime()-start)/1e6
                assertEquals(base.width * scale,result.width)
                assertEquals(base.height * scale,result.height)
                val prefix = when {
                    legacy -> "baseline"
                    contextual -> "ai2-structure"
                    else -> "ai2"
                }
                File(ctx.filesDir,"quality-${prefix}.png").outputStream().use { check(result.compress(Bitmap.CompressFormat.PNG,100,it)) }
                Log.i("HorizonReplay","sample=${base.width}x${base.height} zoom=$zoom iso=$iso upscale=$scale " +
                    "structure=$contextual legacy=$legacy model=$model " +
                    "backend=$backend tiles=$tiles neuralMs=$neuralMs invokeMs=$invokeMs wallMs=$ms")
            } finally { if (result !== base) result.recycle() }
        } finally { base.recycle() }
    }
}
