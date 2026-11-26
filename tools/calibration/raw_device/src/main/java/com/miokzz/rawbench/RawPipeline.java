package com.miokzz.rawbench;

import java.nio.ByteBuffer;

interface RawPipeline extends AutoCloseable {
    record Setup(long delegateNs, long interpreterNs, long allocateNs) {}
    record Output(float[] raw, byte[] rgb, ByteBuffer directRaw, ByteBuffer directRgb) {}

    Setup setup();
    Output output();
    RawEngine.Timing run(RawFrame frame);
}
