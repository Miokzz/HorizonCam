package com.miokzz.rawbench;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class RawBench extends Instrumentation {
    private static final String MODEL_SHA = "5071b54120e71c0dae7669efff1cbc4862cb8fba377bcd33a90f558e7ade820b";
    private static final String QUANT_SHA = "4a9f2e4be20c94c614b9ed8a6df04412a266f0f4e21decb7148ad75c86a07ce7";
    private static final String[] PAIRS = {"detail-a", "detail-b", "flat-a", "flat-b"};
    private Bundle arguments;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        result.putString("pid", Integer.toString(Process.myPid()));
        sendStatus(1, result);
        try {
            runBenchmark();
            result.putString("bench", "OK");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | LinkageError error) {
            Log.e("HorizonRawBench", "Benchmark failed", error);
            result.putString("bench", "FAILED");
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runBenchmark() throws Exception {
        String mode = arguments.getString("mode");
        String implementation = arguments.getString("implementation", "java");
        boolean external = implementation.equals("resident-direct");
        boolean resident = implementation.equals("resident") || external;
        if (!resident && !implementation.equals("java") && !implementation.equals("native")) {
            throw new IllegalArgumentException("Unknown ISP implementation");
        }
        if (resident && !"gpu".equals(mode)) throw new IllegalArgumentException("Resident ISP requires strict GPU mode");
        boolean optimized = !implementation.equals("java");
        String outputMode = arguments.getString("ispOutput", "full");
        if (!outputMode.equals("full") && (!outputMode.equals("rgb") || !resident || external)) {
            throw new IllegalArgumentException("RGB-only is available for the resident NHWC path");
        }
        boolean exportRaw = outputMode.equals("full");
        String ispMath = arguments.getString("ispMath", "stock");
        int math = switch (ispMath) {
            case "stock" -> 0;
            case "divide9" -> 1;
            case "noise" -> 2;
            default -> throw new IllegalArgumentException("Unknown ISP math");
        };
        if (math != 0 && (!resident || external)) throw new IllegalArgumentException("ISP math requires NHWC");
        String run = arguments.getString("run");
        String[] pairs = arguments.getString("pairs", String.join(",", PAIRS)).split(",");
        int repeats = Integer.parseInt(arguments.getString("repeats", "10"));
        int energySeconds = Integer.parseInt(arguments.getString("energySeconds", "15"));
        boolean save = Boolean.parseBoolean(arguments.getString("save", "true"));
        if (run == null || !run.matches("[a-z0-9_-]{1,50}") || mode == null ||
                repeats < 1 || repeats > 50 || energySeconds < 0 || energySeconds > 60) {
            throw new IllegalArgumentException("Invalid benchmark arguments");
        }
        File workspace = getTargetContext().getFilesDir();
        File inputs = new File(workspace, "bench-input");
        File output = new File(workspace, run);
        if (!output.mkdir()) throw new IllegalStateException("Choose a new run directory");
        File graph = new File(inputs, mode.equals("int8") ? "int8.tflite" : "model.tflite");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(graph.toPath())));
        if (!sha.equals(mode.equals("int8") ? QUANT_SHA : MODEL_SHA)) {
            throw new IllegalArgumentException("Graph differs from the audited benchmark model");
        }
        if (Boolean.parseBoolean(arguments.getString("fidelity", "false"))) {
            if (!"gpu".equals(mode)) throw new IllegalArgumentException("Fidelity audit requires strict GPU");
            if (!exportRaw || external || !optimized) throw new IllegalArgumentException("Audit requires native or resident");
            auditFidelity(graph, inputs, output, pairs, sha, resident, ispMath, math);
            return;
        }
        boolean measureJpeg = Boolean.parseBoolean(arguments.getString("measureJpeg", "false"));
        PowerSamples samples = new PowerSamples(getTargetContext());
        JSONObject report = new JSONObject();
        report.put("mode", mode).put("implementation", implementation).put("pid", Process.myPid()).put("graphSha256", sha)
                .put("graphBytes", graph.length()).put("ispMath", ispMath).put("ispOutput", outputMode)
                .put("initialMemory", memory())
                .put("initialPower", samples.snapshot());
        JSONArray loops = new JSONArray();
        JSONArray pairsEvidence = new JSONArray();
        long engineStart = System.nanoTime();
        String libraryDir = getTargetContext().getApplicationInfo().nativeLibraryDir;
        try (RawPipeline engine = resident ? new ResidentIsp(graph, libraryDir, external, false, math, exportRaw)
                : new RawEngine(mode, graph, libraryDir, optimized);
             RawJpegProbe jpegProbe = measureJpeg ? new RawJpegProbe(getTargetContext(), output) : null) {
            RawPipeline.Setup setup = engine.setup();
            report.put("engineNs", System.nanoTime() - engineStart)
                    .put("delegateNs", setup.delegateNs()).put("interpreterNs", setup.interpreterNs())
                    .put("allocateNs", setup.allocateNs()).put("allocatedMemory", memory());
            for (int i = 0; i < pairs.length; i++) {
                String label = pairs[i];
                if (!java.util.Arrays.asList(PAIRS).contains(label)) throw new IllegalArgumentException("Unknown fixture");
                long start = System.nanoTime();
                RawFrame frame = new RawFrame(new File(inputs, label + ".rawbench"), optimized);
                long loadNs = System.nanoTime() - start;
                if (i == 0) {
                    for (int loop = 0; loop <= repeats; loop++) {
                        long frameStarted = System.nanoTime();
                        JSONObject evidence = timing(engine.run(frame));
                        if (jpegProbe != null) evidence.put("jpeg", jpegProbe.publish(engine.output(), frameStarted));
                        evidence.put("memory", memory()).put("power", samples.snapshot());
                        loops.put(evidence);
                    }
                } else {
                    engine.run(frame);
                }
                JSONObject pair = new JSONObject().put("label", label).put("fixtureLoadNs", loadNs);
                if (save) pair.put("rgbSha256", save(engine.output(), output, label));
                pairsEvidence.put(pair);
                if (i == 0 && energySeconds > 0) {
                    report.put("idleWindow", samples.window(energySeconds, null));
                    report.put("loadWindow", samples.window(energySeconds, () -> engine.run(frame)));
                }
            }
        }
        report.put("loops", loops).put("pairs", pairsEvidence)
                .put("finalMemory", memory()).put("finalPower", samples.snapshot());
        Files.write(new File(output, "timing.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private void auditFidelity(File graph, File inputs, File output, String[] pairs, String sha, boolean resident,
                               String ispMath, int math) throws Exception {
        String libraryDir = getTargetContext().getApplicationInfo().nativeLibraryDir;
        String referenceName = arguments.getString("reference");
        File reference = null;
        if (resident) {
            if (referenceName == null || !referenceName.matches("[a-z0-9_-]{1,50}")) {
                throw new IllegalArgumentException("Resident audit requires a native reference run");
            }
            reference = new File(output.getParentFile(), referenceName);
        }
        JSONArray report = new JSONArray();
        RawFidelity trace = new RawFidelity();
        RawFidelity referenceTrace = resident ? new RawFidelity() : null;
        RawFidelity replay = resident ? new RawFidelity() : null;
        try (RawPipeline engine = resident ? new ResidentIsp(graph, libraryDir, false, true, math, true)
                : new RawEngine("gpu", graph, libraryDir, true)) {
            for (String label : pairs) {
                if (!java.util.Arrays.asList(PAIRS).contains(label)) throw new IllegalArgumentException("Unknown fixture");
                RawFrame frame = new RawFrame(new File(inputs, label + ".rawbench"), true);
                String prefix = label + (resident ? "-resident" : "-native");
                JSONObject evidence = new JSONObject().put("label", label);
                if (engine instanceof ResidentIsp gpu) {
                    gpu.run(frame, trace, null, null);
                    evidence.put("resident", saveFidelity(gpu.output(), output, prefix));
                    loadTrace(new File(reference, label + "-native.trace"), referenceTrace.data);
                    try (var stream = new java.io.FileInputStream(new File(reference, label + "-native.f32"))) {
                        FileChannel channel = stream.getChannel();
                        if (channel.size() != (long) RawFrame.SAMPLES * Float.BYTES) {
                            throw new IllegalArgumentException("Unexpected replay mosaic length");
                        }
                        ByteBuffer source = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
                        gpu.run(frame, replay, referenceTrace, source);
                    }
                    writeBuffer(new File(output, label + "-replay.trace"), replay.data);
                    evidence.put("replayRgb", saveFidelityBuffer(gpu.output().directRgb(), output, label + "-replay.rgb"));
                } else {
                    ((RawEngine) engine).run(frame, trace);
                    evidence.put("native", saveFidelity(engine.output(), output, prefix));
                }
                writeBuffer(new File(output, prefix + ".trace"), trace.data);
                report.put(evidence);
            }
        }
        JSONArray tileIndices = new JSONArray();
        for (int tile : RawFidelity.TILES) tileIndices.put(tile);
        JSONObject result = new JSONObject().put("graphSha256", sha).put("ispMath", ispMath)
                .put("traceVersion", 3).put("tileIndices", tileIndices).put("pairs", report);
        Files.write(new File(output, "fidelity.json").toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static void loadTrace(File source, ByteBuffer target) throws Exception {
        try (var stream = new java.io.FileInputStream(source)) {
            FileChannel channel = stream.getChannel();
            target.clear();
            if (channel.size() != target.remaining()) throw new IllegalArgumentException("Unexpected replay trace length");
            while (target.hasRemaining()) {
                if (channel.read(target) < 0) throw new java.io.EOFException("Incomplete replay trace");
            }
            target.rewind();
        }
    }

    private static JSONObject saveFidelity(RawPipeline.Output image, File output, String prefix) throws Exception {
        return new JSONObject().put("raw", saveFidelityBuffer(image.directRaw(), output, prefix + ".f32"))
                .put("rgb", saveFidelityBuffer(image.directRgb(), output, prefix + ".rgb"));
    }

    private static String saveFidelityBuffer(ByteBuffer data, File output, String name) throws Exception {
        writeBuffer(new File(output, name), data);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(RawFidelity.full(data));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static JSONObject timing(RawEngine.Timing time) throws Exception {
        JSONArray tiles = new JSONArray();
        for (long ns : time.inferenceNs()) tiles.put(ns);
        JSONArray nativeInvokes = new JSONArray();
        for (long ns : time.nativeInvokeNs()) nativeInvokes.put(ns);
        JSONObject record = new JSONObject().put("noiseNs", time.noiseNs()).put("packNs", time.packNs())
                .put("inferenceNs", tiles).put("nativeInvokeNs", nativeInvokes).put("assemblyNs", time.assemblyNs())
                .put("rawToRestoredNs", time.rawToRestoredNs()).put("gainNs", time.gainNs())
                .put("ispNs", time.ispNs()).put("totalNs", time.totalNs())
                .put("elapsedRealtimeNs", SystemClock.elapsedRealtimeNanos());
        RawEngine.ResidentStats gpu = time.resident();
        if (gpu != null) {
            record.put("resident", new JSONObject().put("uploadGpuNs", gpu.uploadGpuNs())
                    .put("readbackGpuNs", gpu.readbackGpuNs()).put("waitHostNs", gpu.waitHostNs())
                    .put("enqueueHostNs", gpu.enqueueHostNs()).put("inferenceGpuSpanNs", gpu.inferenceGpuSpanNs())
                    .put("demosaicGpuNs", gpu.demosaicGpuNs()).put("asyncTiles", gpu.asyncTiles())
                    .put("sameQueueTiles", gpu.sameQueueTiles()).put("allocatedClBytes", gpu.allocatedClBytes())
                    .put("uploads", gpu.uploads()).put("readbacks", gpu.readbacks()));
        }
        return record;
    }

    private static String save(RawPipeline.Output snapshot, File output, String label) throws Exception {
        File raw = new File(output, label + ".f32");
        File rgb = new File(output, label + ".rgb");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        if (snapshot.directRgb() == null) {
            writeFloats(raw, snapshot.raw());
            if (label.equals("detail-a")) Files.write(rgb.toPath(), snapshot.rgb());
            digest.update(snapshot.rgb());
        } else {
            if (snapshot.directRaw() != null) writeBuffer(raw, snapshot.directRaw());
            if (label.equals("detail-a")) writeBuffer(rgb, snapshot.directRgb());
            ByteBuffer image = snapshot.directRgb().asReadOnlyBuffer();
            image.rewind();
            digest.update(image);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeBuffer(File target, ByteBuffer data) throws Exception {
        ByteBuffer source = data.asReadOnlyBuffer();
        source.rewind();
        try (FileOutputStream stream = new FileOutputStream(target)) {
            FileChannel channel = stream.getChannel();
            while (source.hasRemaining()) channel.write(source);
        }
    }

    private static JSONObject memory() throws Exception {
        Debug.MemoryInfo info = new Debug.MemoryInfo();
        Debug.getMemoryInfo(info);
        JSONObject object = new JSONObject().put("pssKiB", info.getTotalPss())
                .put("javaUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        try (var lines = Files.lines(Path.of("/proc/self/status"))) {
            lines.filter(line -> line.startsWith("VmHWM:")).findFirst().ifPresent(line -> {
                try {
                    object.put("peakRssKiB", Long.parseLong(line.split("\\s+")[1]));
                } catch (org.json.JSONException error) {
                    throw new IllegalStateException(error);
                }
            });
        }
        return object;
    }

    private static void writeFloats(File target, float[] values) throws Exception {
        ByteBuffer chunk = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        try (FileOutputStream stream = new FileOutputStream(target)) {
            FileChannel channel = stream.getChannel();
            for (int at = 0; at < values.length;) {
                int count = Math.min(values.length - at, chunk.capacity() / Float.BYTES);
                chunk.clear();
                chunk.asFloatBuffer().put(values, at, count);
                chunk.limit(count * Float.BYTES);
                while (chunk.hasRemaining()) channel.write(chunk);
                at += count;
            }
        }
    }
}
