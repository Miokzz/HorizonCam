package com.miokzz.rawbench;

import com.qualcomm.qti.QnnDelegate;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.gpu.GpuDelegateFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

final class RawEngine implements RawPipeline {
    static final int TILE = 128;
    static final int HALO = 4;
    static final int EXTENT = TILE + 2 * HALO;
    static final int TILE_COUNT = ((RawFrame.HEIGHT + TILE - 1) / TILE) * ((RawFrame.WIDTH + TILE - 1) / TILE);

    final long delegateNs;
    final long interpreterNs;
    final long allocateNs;
    final float[] restored;
    final byte[] rgb;
    final NativeIsp nativeIsp;
    private final float[] workspace;
    private final ByteBuffer input;
    private final ByteBuffer output;
    private final FloatBuffer inputFloat;
    private final FloatBuffer outputFloat;
    private final float[] inputTile;
    private final float[] outputTile;
    private final boolean quantized;
    private final float inputScale;
    private final int inputZero;
    private final float outputScale;
    private final int outputZero;
    private final Interpreter interpreter;
    private final AutoCloseable delegate;

    RawEngine(String mode, File graph, String nativeDir, boolean optimized) throws Exception {
        if (optimized && mode.equals("int8")) throw new IllegalArgumentException("Optimized benchmark uses only FP32 I/O");
        nativeIsp = optimized ? new NativeIsp() : null;
        restored = optimized ? null : new float[RawFrame.SAMPLES];
        rgb = optimized ? null : new byte[3 * RawFrame.SAMPLES];
        workspace = optimized ? null : new float[RawFrame.SAMPLES];
        inputTile = optimized ? null : new float[EXTENT * EXTENT * 8];
        outputTile = optimized ? null : new float[EXTENT * EXTENT * 4];
        long start = System.nanoTime();
        Interpreter.Options options = new Interpreter.Options().setNumThreads(4)
                .setUseXNNPACK(mode.equals("cpu") || mode.equals("int8"));
        switch (mode) {
            case "gpu":
            case "gpu-fp16":
                GpuDelegate gpu = new GpuDelegate(new GpuDelegateFactory.Options()
                        .setPrecisionLossAllowed(mode.equals("gpu-fp16")).setQuantizedModelsAllowed(false));
                delegate = gpu;
                options.addDelegate(gpu);
                break;
            case "qnn":
                QnnDelegate.Options qnnOptions = new QnnDelegate.Options();
                qnnOptions.setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND);
                qnnOptions.setSkelLibraryDir(nativeDir);
                qnnOptions.setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16);
                qnnOptions.setLogLevel(QnnDelegate.Options.LogLevel.LOG_LEVEL_INFO);
                QnnDelegate qnn = new QnnDelegate(qnnOptions);
                delegate = qnn;
                if (!qnn.isAvailable()) {
                    qnn.close();
                    throw new UnsupportedOperationException("QNN HTP delegate reports unavailable");
                }
                options.addDelegate(qnn);
                break;
            case "cpu":
            case "int8":
                delegate = null;
                break;
            default:
                throw new IllegalArgumentException("Unknown backend " + mode);
        }
        long delegateEnd = System.nanoTime();
        delegateNs = delegateEnd - start;
        try {
            interpreter = new Interpreter(graph, options);
        } catch (RuntimeException | LinkageError error) {
            if (delegate != null) delegate.close();
            throw error;
        }
        long interpreterEnd = System.nanoTime();
        interpreterNs = interpreterEnd - delegateEnd;
        try {
            interpreter.allocateTensors();
            Tensor source = interpreter.getInputTensor(0);
            Tensor destination = interpreter.getOutputTensor(0);
            quantized = mode.equals("int8");
            DataType expected = quantized ? DataType.INT8 : DataType.FLOAT32;
            if (!Arrays.equals(source.shape(), new int[]{1, EXTENT, EXTENT, 8}) ||
                    !Arrays.equals(destination.shape(), new int[]{1, EXTENT, EXTENT, 4}) ||
                    source.dataType() != expected || destination.dataType() != expected) {
                throw new IllegalArgumentException("Unexpected model tensor contract");
            }
            inputScale = source.quantizationParams().getScale();
            inputZero = source.quantizationParams().getZeroPoint();
            outputScale = destination.quantizationParams().getScale();
            outputZero = destination.quantizationParams().getZeroPoint();
            if (quantized && (!(inputScale > 0) || !(outputScale > 0))) {
                throw new IllegalArgumentException("Invalid INT8 scales");
            }
            input = ByteBuffer.allocateDirect(source.numBytes()).order(ByteOrder.nativeOrder());
            output = ByteBuffer.allocateDirect(destination.numBytes()).order(ByteOrder.nativeOrder());
            inputFloat = input.asFloatBuffer();
            outputFloat = output.asFloatBuffer();
            allocateNs = System.nanoTime() - interpreterEnd;
        } catch (RuntimeException | LinkageError error) {
            close();
            throw error;
        }
    }

    @Override public Setup setup() {
        return new Setup(delegateNs, interpreterNs, allocateNs);
    }

    @Override public Output output() {
        return nativeIsp == null ? new Output(restored, rgb, null, null)
                : new Output(null, null, nativeIsp.restored, nativeIsp.rgb);
    }

    @Override public Timing run(RawFrame frame) {
        return run(frame, null);
    }

    Timing run(RawFrame frame, RawFidelity trace) {
        if (trace != null && (nativeIsp == null || quantized)) {
            throw new IllegalArgumentException("Fidelity trace requires the native FP32 pipeline");
        }
        long started = System.nanoTime();
        if (nativeIsp == null) RawIsp.noise(frame, workspace);
        long noiseNs = System.nanoTime() - started;
        long[] blocks = new long[TILE_COUNT];
        long[] nativeInvokes = new long[TILE_COUNT];
        int cursor = 0;
        long packNs = 0;
        long assemblyNs = 0;
        for (int y = 0; y < RawFrame.HEIGHT; y += TILE) {
            for (int x = 0; x < RawFrame.WIDTH; x += TILE) {
                int traceSlot = trace == null ? -1 : RawFidelity.tileSlot(cursor);
                long packStart = System.nanoTime();
                long tileNoise = 0;
                if (nativeIsp == null) {
                    copyIn(frame.source, y, x);
                } else {
                    tileNoise = nativeIsp.prepare(frame, input, y, x, traceSlot < 0 ? null : trace.noise(traceSlot));
                    noiseNs += tileNoise;
                }
                if (traceSlot >= 0) trace.input(traceSlot).put(RawFidelity.full(input));
                long inferStart = System.nanoTime();
                input.rewind();
                output.rewind();
                interpreter.run(input, output);
                long inferEnd = System.nanoTime();
                if (traceSlot >= 0) trace.prediction(traceSlot).put(RawFidelity.full(output));
                if (nativeIsp == null) {
                    copyOut(frame.source, y, x);
                } else {
                    nativeIsp.restore(frame, output, y, x, traceSlot < 0 ? null : trace.correction(traceSlot));
                }
                long assembled = System.nanoTime();
                Long nativeNs = interpreter.getLastNativeInferenceDurationNanoseconds();
                nativeInvokes[cursor] = nativeNs == null ? -1 : nativeNs;
                packNs += Math.max(0, inferStart - packStart - tileNoise);
                blocks[cursor++] = inferEnd - inferStart;
                assemblyNs += assembled - inferEnd;
            }
        }
        long tilesEnd = System.nanoTime();
        long gainNs;
        if (nativeIsp == null) {
            RawIsp.gain(frame, restored, workspace);
            gainNs = System.nanoTime() - tilesEnd;
            RawIsp.display(workspace, frame.matrix, rgb);
        } else {
            gainNs = nativeIsp.render(frame, trace == null ? null : trace.display());
        }
        long rendered = System.nanoTime();
        return new Timing(noiseNs, packNs, blocks, nativeInvokes, assemblyNs, tilesEnd - started,
                gainNs, rendered - tilesEnd - gainNs, rendered - started, null);
    }

    private void copyIn(float[] source, int y, int x) {
        Arrays.fill(inputTile, 0);
        int top = y - HALO;
        int left = x - HALO;
        int firstRow = Math.max(0, -top);
        int lastRow = Math.min(EXTENT, RawFrame.HEIGHT - top);
        int firstColumn = Math.max(0, -left);
        int lastColumn = Math.min(EXTENT, RawFrame.WIDTH - left);
        for (int i = firstRow; i < lastRow; i++) {
            for (int j = firstColumn; j < lastColumn; j++) {
                int destination = (i * EXTENT + j) * 8;
                int cell = ((top + i) * RawFrame.WIDTH + left + j) * 4;
                for (int phase = 0; phase < 4; phase++) {
                    inputTile[destination + phase] = source[cell + phase] * 2;
                    inputTile[destination + 4 + phase] = workspace[cell + phase] * 32;
                }
            }
        }
        if (quantized) {
            input.rewind();
            for (float value : inputTile) input.put(quantize(value, inputScale, inputZero));
        } else {
            inputFloat.clear();
            inputFloat.put(inputTile);
        }
    }

    static byte quantize(float value, float scale, int zero) {
        return (byte) Math.max(-128, Math.min(127, Math.round(value / scale) + zero));
    }

    private void copyOut(float[] source, int y, int x) {
        if (quantized) {
            output.rewind();
            for (int i = 0; i < outputTile.length; i++) outputTile[i] = (output.get() - outputZero) * outputScale;
        } else {
            outputFloat.clear();
            outputFloat.get(outputTile);
        }
        int height = Math.min(TILE, RawFrame.HEIGHT - y);
        int width = Math.min(TILE, RawFrame.WIDTH - x);
        for (int i = 0; i < height; i++) {
            int destination = ((y + i) * RawFrame.WIDTH + x) * 4;
            int start = ((i + HALO) * EXTENT + HALO) * 4;
            for (int j = 0; j < width * 4; j++) {
                int index = destination + j;
                restored[index] = RawIsp.corrected(source[index], workspace[index], outputTile[start + j]);
            }
        }
    }

    record Timing(long noiseNs, long packNs, long[] inferenceNs, long[] nativeInvokeNs, long assemblyNs,
                  long rawToRestoredNs, long gainNs, long ispNs, long totalNs, ResidentStats resident) {}

    record ResidentStats(long uploadGpuNs, long readbackGpuNs, long waitHostNs, long enqueueHostNs,
                         long inferenceGpuSpanNs, long demosaicGpuNs, int asyncTiles, int sameQueueTiles,
                         long allocatedClBytes, int uploads, int readbacks) {}

    @Override public void close() throws Exception {
        try {
            interpreter.close();
        } finally {
            if (delegate != null) delegate.close();
        }
    }
}
