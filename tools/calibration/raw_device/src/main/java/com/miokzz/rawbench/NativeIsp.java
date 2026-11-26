package com.miokzz.rawbench;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

final class NativeIsp {
    static {
        System.loadLibrary("horizon_raw_isp");
    }

    private static final int WIDTH = 2 * RawFrame.WIDTH;
    static final int PARAMETER_BYTES = (9 + 4 * (2 + 256 * 256 + 3 * (RawFrame.HEIGHT + RawFrame.WIDTH))) * Float.BYTES;
    final ByteBuffer restored = allocateFloats(RawFrame.SAMPLES);
    final ByteBuffer rgb = ByteBuffer.allocateDirect(3 * RawFrame.SAMPLES);
    private final ByteBuffer sigma = allocateFloats(RawEngine.EXTENT * RawEngine.EXTENT * 4);
    private final ByteBuffer rows = allocateFloats(WIDTH * 5);
    private final ByteBuffer srgb = ByteBuffer.allocateDirect(65536);
    // Four maps, each with two dimensions, up to 256x256 gains, and two axis tables.
    private final ByteBuffer maps = ByteBuffer.allocateDirect(PARAMETER_BYTES).order(ByteOrder.nativeOrder());
    private RawFrame bound;

    NativeIsp() {
        RawIsp.copyLookup(srgb);
    }

    long prepare(RawFrame frame, ByteBuffer input, int y, int x) {
        return nativePrepare(frame.mappedSource, frame.profile, sigma, input, y, x);
    }

    void restore(RawFrame frame, ByteBuffer output, int y, int x) {
        if (!nativeRestore(frame.mappedSource, sigma, output, restored, y, x)) {
            throw new IllegalArgumentException("Nonfinite neural prediction");
        }
    }

    long render(RawFrame frame) {
        if (bound != frame) {
            writeParameters(maps, frame);
            bound = frame;
        }
        return nativeRender(restored, maps, rows, srgb, rgb);
    }

    static int writeParameters(ByteBuffer target, RawFrame frame) {
        FloatBuffer table = target.asFloatBuffer();
        table.put(frame.matrix);
        for (RawFrame.GainMap map : frame.maps) {
            table.put(map.columns).put(map.gains.length / map.columns).put(map.gains);
            putAxis(table, map.y);
            putAxis(table, map.x);
        }
        return table.position() * Float.BYTES;
    }

    private static void putAxis(FloatBuffer buffer, RawFrame.Axis axis) {
        for (int i = 0; i < axis.low.length; i++) {
            buffer.put(axis.low[i]).put(axis.high[i]).put(axis.weight[i]);
        }
    }

    private static ByteBuffer allocateFloats(int count) {
        return ByteBuffer.allocateDirect(count * Float.BYTES).order(ByteOrder.nativeOrder());
    }

    private static native long nativePrepare(ByteBuffer source, float[] profile, ByteBuffer sigma,
                                             ByteBuffer input, int y, int x);
    private static native boolean nativeRestore(ByteBuffer source, ByteBuffer sigma, ByteBuffer output,
                                                ByteBuffer restored, int y, int x);
    private static native long nativeRender(ByteBuffer restored, ByteBuffer maps, ByteBuffer rows,
                                            ByteBuffer srgb, ByteBuffer rgb);
}
