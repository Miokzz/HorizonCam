package com.miokzz.rawbench;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

final class ResidentIsp implements RawPipeline {
    static {
        System.loadLibrary("horizon_raw_resident");
    }

    private static final int FIRST_TILE = 13;
    private final ByteBuffer raw = direct(RawFrame.SAMPLES * Float.BYTES);
    private final ByteBuffer rgb = direct(RawFrame.SAMPLES * 3);
    private final ByteBuffer parameters = direct(NativeIsp.PARAMETER_BYTES);
    private final long[] values = new long[FIRST_TILE + RawEngine.TILE_COUNT];
    private final Setup setup;
    private long handle;

    ResidentIsp(File model, String libraryDir, boolean external, boolean audit, int math) {
        if (audit && external) throw new IllegalArgumentException("Fidelity audit requires NHWC");
        ByteBuffer lookup = direct(65536);
        RawIsp.copyLookup(lookup);
        long[] durations = new long[3];
        handle = create(model.getAbsolutePath(), libraryDir, lookup, external, audit, math, durations);
        setup = new Setup(durations[0], durations[1], durations[2]);
    }

    @Override public Setup setup() {
        return setup;
    }

    @Override public Output output() {
        return new Output(null, null, raw, rgb);
    }

    @Override public RawEngine.Timing run(RawFrame frame) {
        return run(frame, null, null, null);
    }

    RawEngine.Timing run(RawFrame frame, RawFidelity trace, RawFidelity reference, ByteBuffer referenceRaw) {
        long start = System.nanoTime();
        int metadataBytes = NativeIsp.writeParameters(parameters, frame);
        runFrame(handle, frame.mappedSource, frame.profile, parameters, metadataBytes, raw, rgb, values,
                trace == null ? null : trace.data, reference == null ? null : reference.data, referenceRaw);
        long elapsed = System.nanoTime() - start;
        long[] tileGpuSpans = Arrays.copyOfRange(values, FIRST_TILE, values.length);
        long[] hostInvoke = new long[RawEngine.TILE_COUNT];
        Arrays.fill(hostInvoke, -1); // GPU event spans and host dispatch are different clocks.
        RawEngine.ResidentStats stats = new RawEngine.ResidentStats(values[1], values[4], values[5],
                values[6], values[7], values[3], (int) values[8], (int) values[9], values[10],
                (int) values[11], (int) values[12]);
        return new RawEngine.Timing(values[0], 0, tileGpuSpans, hostInvoke, values[2],
                -1, 0, values[3], elapsed, stats);
    }

    @Override public void close() {
        if (handle != 0) {
            destroy(handle);
            handle = 0;
        }
    }

    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    private static native long create(String model, String libraryDir, ByteBuffer lookup,
                                      boolean external, boolean audit, int math, long[] durations);
    private static native void runFrame(long handle, ByteBuffer input, float[] profile, ByteBuffer metadata,
                                        int metadataBytes, ByteBuffer raw, ByteBuffer rgb, long[] timing,
                                        ByteBuffer trace, ByteBuffer reference, ByteBuffer referenceRaw);
    private static native void destroy(long handle);
}
