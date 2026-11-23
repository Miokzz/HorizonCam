"""Train an experimental bounded SDR residual denoiser on procedural patches.

From the HorizonCam directory, with TensorFlow 2.20 and NumPy installed:
  ../restoration-venv/Scripts/python.exe tools/model/train_experimental_denoiser.py \
      --out ../s24-qa/experimental-denoiser

The model and report are experimental artifacts. Use an output directory outside Git.
"""

import argparse
import hashlib
import json
import os
import platform
import time
from pathlib import Path

os.environ.setdefault("TF_ENABLE_ONEDNN_OPTS", "0")

import numpy as np
import tensorflow as tf


EDGE = 64
HALO = 4
LIMIT = 0.08
TRAIN_SEED = 20261122
HOLDOUT_SEED = 80202611
TRAIN_SCENES = ("grains", "gratings", "geometry", "ramp")
HOLDOUT_SCENES = ("facade", "fibers", "soft_shapes", "colored_edges")
X, Y = np.meshgrid(np.linspace(-1, 1, EDGE, dtype=np.float32),
                   np.linspace(-1, 1, EDGE, dtype=np.float32))


def field(rng, grid_size):
    grid = rng.random((grid_size, grid_size), dtype=np.float32)
    grid_axis = np.linspace(-1, 1, grid_size)
    axis = X[0]
    rows = np.array([np.interp(axis, grid_axis, row) for row in grid])
    return np.array([np.interp(axis, grid_axis, column) for column in rows.T], dtype=np.float32).T


def oriented(rng):
    angle = rng.uniform(-np.pi, np.pi)
    c, s = np.cos(angle), np.sin(angle)
    return X * c + Y * s, -X * s + Y * c


def clean_patch(rng, family):
    u, v = oriented(rng)
    low_color = rng.uniform(0.015, 0.40, 3)
    high_color = rng.uniform(0.42, 0.98, 3)
    if family == "grains":
        tone = 0.48 * field(rng, 24) + 0.32 * field(rng, 11) + 0.20 * field(rng, 4)
    elif family == "gratings":
        waves = np.sin(rng.uniform(16, 54) * (u + 0.05 * field(rng, 6)))
        tone = 0.52 + 0.25 * waves + 0.18 * np.cos(rng.uniform(12, 40) * v)
    elif family == "geometry":
        tone = 0.19 + 0.38 * (u > rng.uniform(-0.5, 0.5)) + 0.26 * (v > rng.uniform(-0.4, 0.4))
    elif family == "ramp":
        tone = 0.44 + 0.21 * u + 0.12 * v + 0.14 * field(rng, 3)
    elif family == "facade":
        mortar = (np.mod(u + 0.11 * (v > 0), 0.19) < 0.030) | (np.mod(v, 0.27) < 0.033)
        inset = (np.abs(u - 0.35) < 0.17) & (np.abs(v + 0.17) < 0.32)
        tone = 0.53 + 0.36 * mortar - 0.35 * inset + 0.06 * np.sin(38 * u)
    elif family == "fibers":
        warp = u + 0.13 * v * v + 0.03 * np.sin(16 * v)
        ridge = np.abs(np.sin(rng.uniform(23, 48) * warp + rng.uniform(-np.pi, np.pi))) ** 3
        tone = 0.22 + 0.47 * ridge * (0.78 + 0.22 * np.cos(9 * v)) + 0.15 * np.sin(27 * (u + v))
    elif family == "soft_shapes":
        tone = np.full((EDGE, EDGE), 0.23, dtype=np.float32)
        for _ in range(4):
            cx, cy = rng.uniform(-0.9, 0.9, 2)
            distance = (u - cx) ** 2 + (v - cy) ** 2
            tone += rng.uniform(0.12, 0.23) * np.exp(-distance / rng.uniform(0.09, 0.45))
    elif family == "colored_edges":
        low_color = rng.permutation([rng.uniform(0.01, 0.12), 0.15, rng.uniform(0.75, 0.98)])
        high_color = np.roll(low_color, 1)
        mask = (u > rng.uniform(-0.21, 0.21)) ^ ((u + 0.42) ** 2 + (v - 0.28) ** 2 < 0.25)
        tone = np.clip(mask.astype(np.float32) * 0.9 + 0.04 * v, 0.0, 1.0)
    else:
        raise ValueError(f"Unknown scene family {family}")
    return np.clip(low_color + np.clip(tone, 0, 1)[..., None] * (high_color - low_color), 0, 1).astype(np.float32)


def noisy_patch(rng, clean, regime):
    if regime == "clean":
        return clean.copy()
    # Linear-light Poisson shot noise plus Gaussian read noise, then sRGB and 8-bit sampling.
    shots, read = ((rng.uniform(500, 5000), rng.uniform(0.0008, 0.006)) if regime == "nominal"
                   else (rng.uniform(120, 480), rng.uniform(0.006, 0.012)))
    linear = np.where(clean <= 0.04045, clean / 12.92, ((clean + 0.055) / 1.055) ** 2.4)
    sample = rng.poisson(linear * shots) / shots + rng.normal(0.0, read, clean.shape)
    sample = np.clip(sample, 0, 1)
    encoded = np.where(sample <= 0.0031308, 12.92 * sample, 1.055 * sample ** (1 / 2.4) - 0.055)
    return (np.rint(encoded * 255) / 255).astype(np.float32)


def training_data(count):
    rng = np.random.default_rng(TRAIN_SEED)
    truth = np.stack([clean_patch(rng, TRAIN_SCENES[index % len(TRAIN_SCENES)]) for index in range(count)])
    inputs = np.stack([noisy_patch(rng, image, "clean" if index % 7 == 0 else "nominal")
                       for index, image in enumerate(truth)])
    return inputs, truth


def holdout_data(per_scene, seed):
    rng = np.random.default_rng(seed)
    for family in HOLDOUT_SCENES:
        truth = [clean_patch(rng, family) for _ in range(per_scene)]
        for regime in ("nominal", "strong", "clean"):
            for image in truth:
                yield family, regime, image, noisy_patch(rng, image, regime)


def residual(base, logits):
    return tf.clip_by_value(base + LIMIT * tf.tanh(logits), 0.0, 1.0)


def build_model(leaky):
    source = tf.keras.Input((EDGE, EDGE, 3), name="rgb")
    hidden = source
    for _ in range(3):
        hidden = tf.keras.layers.Conv2D(12, 3, padding="same", activation=None if leaky else "relu")(hidden)
        if leaky:
            hidden = tf.keras.layers.LeakyReLU(negative_slope=0.1)(hidden)
    prediction = tf.keras.layers.Conv2D(3, 3, padding="same", kernel_initializer="zeros")(hidden)
    output = tf.keras.layers.Lambda(lambda pair: residual(*pair))([source, prediction])
    return tf.keras.Model(source, output)


def loss(truth, restored):
    truth = truth[:, HALO:-HALO, HALO:-HALO, :]
    restored = restored[:, HALO:-HALO, HALO:-HALO, :]
    error = restored - truth
    color_error = error - tf.reduce_mean(error, axis=-1, keepdims=True)
    gradient_error = (tf.reduce_mean(tf.abs(error[:, 1:] - error[:, :-1])) +
                      tf.reduce_mean(tf.abs(error[:, :, 1:] - error[:, :, :-1]))) * 0.5
    return tf.reduce_mean(tf.abs(error)) + 0.14 * gradient_error + 0.10 * tf.reduce_mean(tf.abs(color_error))


def train(inputs, truth, epochs, leaky):
    model = build_model(leaky)
    model.compile(optimizer=tf.keras.optimizers.Adam(0.001), loss=loss)
    rng = np.random.default_rng(TRAIN_SEED + 1)
    history = []
    for epoch in range(epochs):
        order = rng.permutation(len(inputs))
        model.reset_metrics()
        for start in range(0, len(order), 16):
            batch = order[start:start + 16]
            train_loss = model.train_on_batch(inputs[batch], truth[batch])
        history.append(float(train_loss))
        print(f"epoch {epoch + 1}/{epochs}: loss {train_loss:.6f}", flush=True)
    return model, history


def export(model, leaky):
    convolutions = [layer.get_weights() for layer in model.layers if isinstance(layer, tf.keras.layers.Conv2D)]

    class Frozen(tf.Module):
        def __init__(self):
            super().__init__()
            self.layers = [(tf.constant(kernel), tf.constant(bias)) for kernel, bias in convolutions]

        @tf.function(input_signature=[tf.TensorSpec([1, EDGE, EDGE, 3], tf.float32, name="rgb")])
        def serve(self, source):
            hidden = source
            for index, (kernel, bias) in enumerate(self.layers):
                hidden = tf.nn.bias_add(tf.nn.conv2d(hidden, kernel, strides=1, padding="SAME"), bias)
                if index < len(self.layers) - 1:
                    hidden = tf.nn.leaky_relu(hidden, alpha=0.1) if leaky else tf.nn.relu(hidden)
            return {"restored": residual(source, hidden)}

    frozen = Frozen()
    converter = tf.lite.TFLiteConverter.from_concrete_functions([frozen.serve.get_concrete_function()], frozen)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    return converter.convert()


def gaussian_binomial(image):
    padded = np.pad(image, ((0, 0), (1, 1), (0, 0)), mode="edge")
    horizontal = (padded[:, :-2] + 2 * padded[:, 1:-1] + padded[:, 2:]) * 0.25
    vertical = np.pad(horizontal, ((1, 1), (0, 0), (0, 0)), mode="edge")
    return (vertical[:-2] + 2 * vertical[1:-1] + vertical[2:]) * 0.25


def metrics(truth, value):
    truth = truth[HALO:-HALO, HALO:-HALO]
    error = value[HALO:-HALO, HALO:-HALO] - truth
    mse = float(np.mean(error ** 2))
    gradient_error = tuple(np.abs(np.diff(error, axis=axis)) for axis in (0, 1))
    edges = tuple(np.max(np.abs(np.diff(truth, axis=axis)), axis=2) >= 0.12 for axis in (0, 1))
    edge_error = [gradient[mask] for gradient, mask in zip(gradient_error, edges) if np.any(mask)]
    chroma_error = error - error.mean(axis=2, keepdims=True)
    return {
        "psnr_db": float(-10 * np.log10(max(mse, 1e-12))),
        "mae_255": float(np.abs(error).mean() * 255),
        "gradient_mae_255": float(np.mean([g.mean() for g in gradient_error]) * 255),
        "edge_gradient_mae_255": float(np.concatenate(edge_error).mean() * 255) if edge_error else None,
        "chroma_mae_255": float(np.abs(chroma_error).mean() * 255),
    }


def evaluate(model, binary, per_scene, threads, seed):
    runner = tf.lite.Interpreter(model_content=binary, num_threads=threads)
    runner.allocate_tensors()
    inputs = runner.get_input_details()
    outputs = runner.get_output_details()
    assert len(inputs) == len(outputs) == 1
    for tensor in (inputs[0], outputs[0]):
        assert tensor["dtype"] == np.float32 and list(tensor["shape"]) == [1, EDGE, EDGE, 3]
    results = []
    invocation_ms = []
    parity = []
    largest_change = 0.0
    for family, regime, truth, noisy in holdout_data(per_scene, seed):
        runner.set_tensor(inputs[0]["index"], noisy[None])
        started = time.perf_counter()
        runner.invoke()
        invocation_ms.append((time.perf_counter() - started) * 1_000)
        restored = runner.get_tensor(outputs[0]["index"])[0]
        assert np.isfinite(restored).all() and restored.min() >= 0 and restored.max() <= 1
        largest_change = max(largest_change, float(np.abs(restored - noisy).max()))
        actual = model(noisy[None], training=False).numpy()[0]
        parity.append(float(np.abs(actual - restored).max()))
        results.append({"family": family, "noise": regime, "input": metrics(truth, noisy),
                        "gaussian": metrics(truth, gaussian_binomial(noisy)), "denoised": metrics(truth, restored)})
    assert max(parity) <= 1e-5 and largest_change <= LIMIT + 1e-6
    return results, {"cold_invoke_ms": invocation_ms[0], "warm_median_invoke_ms": float(np.median(invocation_ms[1:])),
                     "warm_p95_invoke_ms": float(np.percentile(invocation_ms[1:], 95)),
                     "max_delta_from_input": largest_change, "max_abs_tf_lite_parity": max(parity)}


def summarize(samples):
    def mean_metric(entries, method, metric):
        values = [s[method][metric] for s in entries if s[method][metric] is not None]
        return float(np.mean(values)) if values else None

    summary = {}
    for scene in (*HOLDOUT_SCENES, "all"):
        summary[scene] = {}
        for regime in ("nominal", "strong", "clean"):
            entries = [s for s in samples if (scene == "all" or s["family"] == scene) and s["noise"] == regime]
            summary[scene][regime] = {method: {metric: mean_metric(entries, method, metric)
                                               for metric in entries[0][method]}
                                      for method in ("input", "gaussian", "denoised")}
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--patches", type=int, default=768)
    parser.add_argument("--epochs", type=int, default=4)
    parser.add_argument("--holdout", type=int, default=10, help="Clean patches per reserved family")
    parser.add_argument("--evaluation-seed", type=int, default=HOLDOUT_SEED)
    parser.add_argument("--leaky", action="store_true", help="Ablate dead ReLU gradients with slope 0.1")
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    if min(args.patches, args.epochs, args.holdout, args.threads) < 1:
        parser.error("Patches, epochs, holdout and thread count must be positive")
    if args.evaluation_seed < 0 or args.evaluation_seed in (TRAIN_SEED, TRAIN_SEED + 1):
        parser.error("Evaluation requires a nonnegative seed distinct from the training seeds")
    args.out.mkdir(parents=True, exist_ok=True)
    tf.config.set_visible_devices([], "GPU")
    tf.config.threading.set_intra_op_parallelism_threads(args.threads)
    tf.config.threading.set_inter_op_parallelism_threads(1)
    tf.keras.utils.set_random_seed(TRAIN_SEED)
    tf.config.experimental.enable_op_determinism()
    started = time.perf_counter()
    inputs, truth = training_data(args.patches)
    model, history = train(inputs, truth, args.epochs, args.leaky)
    train_seconds = time.perf_counter() - started
    binary = export(model, args.leaky)
    samples, timing = evaluate(model, binary, args.holdout, args.threads, args.evaluation_seed)
    summary = summarize(samples)
    report = {
        "scope": "Synthetic SDR patches and desktop built-in LiteRT CPU only; no real camera validation.",
        "python": platform.python_version(), "tensorflow": tf.__version__, "numpy": np.__version__,
        "train_seed": TRAIN_SEED, "holdout_seed": args.evaluation_seed, "patches": args.patches, "epochs": args.epochs,
        "activation": "leaky_relu_0.1" if args.leaky else "relu",
        "holdout_patches_per_family": args.holdout, "train_scene_families": TRAIN_SCENES,
        "holdout_scene_families": HOLDOUT_SCENES, "threads": args.threads, "train_seconds": train_seconds,
        "loss_history": history, "sha256": hashlib.sha256(binary).hexdigest(), "bytes": len(binary),
        "parameter_count": model.count_params(), "shape": [1, EDGE, EDGE, 3], "dtype": "float32",
        "nominal_range": [0, 1], "color": "sRGB", "delta_limit": LIMIT, "halo": HALO, "timing": timing,
        "summary": summary, "samples": samples,
    }
    (args.out / "denoiser-f32.tflite").write_bytes(binary)
    (args.out / "report.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("sha256", "bytes", "train_seconds", "timing")}, indent=2))
    print(json.dumps(summary["all"], indent=2))


if __name__ == "__main__":
    main()
