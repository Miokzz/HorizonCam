package com.miokzz.horizoncam

import com.miokzz.horizoncam.media.PhotoRegion
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoRegionTest {
    @Test fun cornerOnRotatedPreviewMapsToEncodedPixels() {
        assertEquals(PhotoRegion.Patch(0, 80, 20), PhotoRegion.patch(200, 100, 20, 0f, 0f, 6))
        assertEquals(PhotoRegion.Patch(180, 0, 20), PhotoRegion.patch(200, 100, 20, 0f, 0f, 8))
        assertEquals(PhotoRegion.Patch(180, 80, 20), PhotoRegion.patch(200, 100, 20, 0f, 0f, 7))
    }
    @Test fun tinyImagesRetainRealPixelsAndRespectBounds() {
        assertEquals(PhotoRegion.Patch(0, 4, 6), PhotoRegion.patch(6, 10, 256, 1f, 1f, 1))
    }
}
