package com.miokzz.rawbench;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class RawMathTest {
    @Test public void reflectionMatchesOpenCvAtTheBorders() {
        assertEquals(1, RawIsp.reflect(-1, 5));
        assertEquals(2, RawIsp.reflect(-2, 5));
        assertEquals(3, RawIsp.reflect(5, 5));
        assertEquals(2, RawIsp.reflect(6, 5));
    }

    @Test public void rawResidualPreservesSaturationAndCapsLargeCorrections() {
        assertEquals(1.01f, RawIsp.corrected(1.01f, .1f, 2), 0);
        assertEquals(.16f, RawIsp.corrected(.1f, .02f, 55), 1e-7);
        assertEquals(.04f, RawIsp.corrected(.1f, .02f, -55), 1e-7);
        assertThrows(IllegalArgumentException.class, () -> RawIsp.corrected(.1f, .02f, Float.NaN));
    }

    @Test public void int8ClampsAndHonorsZeroPoint() {
        assertEquals(-124, RawEngine.quantize(0, .008f, -124));
        assertEquals(-128, RawEngine.quantize(-1, .008f, -124));
        assertEquals(127, RawEngine.quantize(9, .008f, -124));
        assertEquals(-122, RawEngine.quantize(.016f, .008f, -124));
    }

    @Test public void gainAxisSamplesPhotositeCenters() throws Exception {
        RawFrame.Axis even = new RawFrame.Axis(2, 3, 0, .5, 0);
        RawFrame.Axis odd = new RawFrame.Axis(2, 3, 1, .5, 0);
        assertEquals(0, even.low[0]);
        assertEquals(.25f, even.weight[0], 1e-7);
        assertEquals(.75f, odd.weight[0], 1e-7);
        assertEquals(1, odd.low[1]);
        assertEquals(.75f, odd.weight[1], 1e-7);
    }
}
