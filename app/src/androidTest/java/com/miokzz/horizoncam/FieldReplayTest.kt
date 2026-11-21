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
        val base = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder,_,_ ->
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val legacy = args.getString("legacy") == "yes"
        try {
            val start = System.nanoTime()
            var tiles=0
            var model="general-x4v3-50"
            var backend="CPU-2"
            val result = if (legacy) LegacyRestorer.restore(InstrumentationRegistry.getInstrumentation().context,base,
                zoom,400,1,{tiles=it.totalTiles},{false}).bitmap
            else NativeRestorer.restore(ctx,base,zoom,400,1,{}, {false}).let {
                tiles=it.tiles; model=it.model; backend=it.backend; it.bitmap
            }
            try {
                val ms=(System.nanoTime()-start)/1e6
                assertEquals(base.width,result.width)
                assertEquals(base.height,result.height)
                val prefix=if(legacy) "baseline" else "ai2"
                File(ctx.filesDir,"quality-${prefix}.png").outputStream().use { check(result.compress(Bitmap.CompressFormat.PNG,100,it)) }
                Log.i("HorizonReplay","sample=${base.width}x${base.height} zoom=$zoom legacy=$legacy model=$model backend=$backend tiles=$tiles wallMs=$ms")
            } finally { result.recycle() }
        } finally { base.recycle() }
    }
}
