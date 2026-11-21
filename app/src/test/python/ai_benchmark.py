"""Reproduce real offline inference. Python 3.12; ai-edge-litert, numpy, pillow, scikit-image.
No network is used by this script. The NASA astronaut fixture ships with scikit-image.
Not an Android/device speed benchmark. Outputs stay in test resources by default.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import platform
import time
from collections import Counter
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
from ai_edge_litert.interpreter import Interpreter
from skimage import data
from skimage.metrics import peak_signal_noise_ratio, structural_similarity

ROOT = Path(__file__).resolve().parents[3]
MODEL = ROOT / "src/main/assets/ai/ESRGAN.tflite"
EXPECTED_SHA = "1a380d3744103e11ef343534aaff54815cae40769dcd00c023652a7e5bc47f4b"


def origins(size):
    points = [0]
    while points[-1] + 32 < size:
        points.append(min(points[-1] + 24, size - 32))
    return points


def mirror(values, size):
    if size == 1:
        return np.zeros_like(values)
    folded = np.mod(values, 2 * (size - 1))
    return np.where(folded < size, folded, 2 * (size - 1) - folded)


def bicubic(rgb):
    def axis(size):
        position = (np.arange(size * 4, dtype=np.float32) + 0.5) / 4 - 0.5
        anchor = np.floor(position).astype(int)
        indices = anchor[:, None] + np.arange(4) - 1
        distance = np.abs(position[:, None] - indices)
        weights = np.where(distance <= 1, 1.5 * distance**3 - 2.5 * distance**2 + 1,
                           np.where(distance < 2, -0.5 * distance**3 + 2.5 * distance**2 - 4 * distance + 2, 0)).astype(np.float32)
        return mirror(indices, size), weights
    yi, yw = axis(rgb.shape[0])
    xi, xw = axis(rgb.shape[1])
    output = np.zeros((rgb.shape[0] * 4, rgb.shape[1] * 4, 3), dtype=np.float32)
    for j in range(4):
        for i in range(4):
            output += rgb[yi[:, j, None], xi[None, :, i]].astype(np.float32) * yw[:, j, None, None] * xw[None, :, i, None]
    return np.clip(np.floor(output + 0.5), 0, 255).astype(np.uint8)


def scene(rgb):
    luma = rgb @ np.array([0.299, 0.587, 0.114], np.float32)
    left, right = luma[1:-1, :-2], luma[1:-1, 2:]
    up, down = luma[:-2, 1:-1], luma[2:, 1:-1]
    center = luma[1:-1, 1:-1]
    gradient = np.abs(right - left) + np.abs(down - up)
    flat_laplacian = np.clip(np.floor(np.abs(4 * center - left - right - up - down)[gradient < 16] + 0.5), 0, 255)
    noise = min(40, float(np.median(flat_laplacian)) / 3.016) if flat_laplacian.size else 0
    padded = np.pad(luma, 1, mode="edge")
    edge = np.abs(padded[1:-1, 2:] - padded[1:-1, :-2]) + np.abs(padded[2:, 1:-1] - padded[:-2, 1:-1])
    return noise, float(np.mean(gradient > 24)), np.repeat(np.repeat(edge, 4, axis=0), 4, axis=1)


def infer(rgb, interpreter):
    height, width = rgb.shape[:2]
    baseline = bicubic(rgb)
    noise, edge_fraction, edge = scene(rgb)
    raw = np.zeros_like(baseline)
    conservative = np.zeros_like(baseline)
    weights = np.zeros(baseline.shape[:2], np.float32)
    inference_ms = []
    plan = [(x, y) for y in origins(height) for x in origins(width)]
    started = time.perf_counter()
    for completed, (x, y) in enumerate(plan, 1):
        source_x = mirror(np.arange(x - 9, x - 9 + 50), width)
        source_y = mirror(np.arange(y - 9, y - 9 + 50), height)
        tile = rgb[source_y[:, None], source_x[None, :]].astype(np.float32)[None]
        interpreter.set_tensor(0, tile)
        then = time.perf_counter()
        interpreter.invoke()
        inference_ms.append((time.perf_counter() - then) * 1000)
        prediction = interpreter.get_tensor(interpreter.get_output_details()[0]["index"])[0]
        assert np.isfinite(prediction).all()
        tw, th = min(32, width - x) * 4, min(32, height - y) * 4
        crop = np.clip(prediction[36:36 + th, 36:36 + tw], 0, 255)
        ys, xs = slice(y * 4, y * 4 + th), slice(x * 4, x * 4 + tw)
        base = baseline[ys, xs].astype(np.float32)
        difference = crop - base
        delta_luma = difference @ np.array([0.299, 0.587, 0.114], np.float32)
        detail = np.clip(edge[ys, xs] / 32, 0, 1)
        amount = 0.65 * (0.55 + 0.45 * detail) * (1 - 0.5 * min(1, noise / 12) * (1 - detail))
        mixed = np.clip(np.floor(base + amount[..., None] *
                    (delta_luma[..., None] + 0.15 * (difference - delta_luma[..., None])) + 0.5), 0, 255)
        wx = np.minimum(1, np.minimum(np.arange(tw) + 1, tw - np.arange(tw)) / 32).astype(np.float32)
        wy = np.minimum(1, np.minimum(np.arange(th) + 1, th - np.arange(th)) / 32).astype(np.float32)
        weight = wy[:, None] * wx[None]
        previous = weights[ys, xs].copy()
        total = previous + weight
        for target, value in [(raw, np.floor(crop + 0.5)), (conservative, mixed)]:
            target[ys, xs] = np.clip(np.floor((target[ys, xs] * previous[..., None] + value * weight[..., None]) /
                               total[..., None] + 0.5), 0, 255).astype(np.uint8)
        weights[ys, xs] = total
    assert np.all(weights > 0)
    return baseline, raw, conservative, {"completed_tiles": len(plan), "total_tiles": len(plan),
        "elapsed_ms": round((time.perf_counter() - started) * 1000, 2),
        "mean_inference_tile_ms": round(float(np.mean(inference_ms)), 2),
        "noise_estimate": round(noise, 4), "edge_fraction": round(edge_fraction, 4)}


def synthetic():
    width, height = 320, 240
    yy, xx = np.mgrid[:height, :width]
    rgb = np.stack((40 + xx * 0.45, 35 + yy * 0.65, 150 + 25 * np.sin(xx / 8)), axis=2)
    image = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8))
    draw = ImageDraw.Draw(image)
    draw.ellipse((25, 25, 165, 165), outline="white", width=3)
    draw.line((0, 225, 310, 8), fill="yellow", width=2)
    for x in range(185, 310, 6):
        draw.line((x, 80, x, 150), fill="white", width=1)
    draw.text((170, 170), "HORIZON 4x / 0123", fill="white", stroke_width=0)
    return np.asarray(image)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=ROOT / "src/test/resources/ai")
    parser.add_argument("--native-crop", action="store_true")
    parser.add_argument("--all-images", action="store_true", help="Also save full intermediate PNGs")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    assert len(MODEL.read_bytes()) == 4993712
    assert hashlib.sha256(MODEL.read_bytes()).hexdigest() == EXPECTED_SHA
    interpreter = Interpreter(model_path=str(MODEL), num_threads=2)
    interpreter.allocate_tensors()
    tensors = interpreter.get_input_details() + interpreter.get_output_details()
    report = {"model_sha256": EXPECTED_SHA, "host": platform.platform(), "python": platform.python_version(),
              "runtime": "ai-edge-litert 2.4.0 CPU/XNNPACK, 2 threads (Android uses Interpreter 1.4.2)",
              "tensors": [{"name": t["name"], "shape": t["shape"].tolist(), "dtype": str(t["dtype"])} for t in tensors],
              "graph_ops": dict(Counter(o["op_name"] for o in interpreter._get_ops_details() if o["op_name"] != "DELEGATE")),
              "fixtures": []}
    fixtures = [("synthetic", synthetic(), False),
                ("nasa_astronaut", np.asarray(Image.fromarray(data.astronaut()).crop((64, 48, 448, 304))), False)]
    if args.native_crop:
        fixtures.append(("native_256_crop", np.asarray(data.astronaut()[100:356, 130:386]), True))
    for name, reference, native in fixtures:
        if native:
            low = reference
        else:
            low = np.asarray(Image.fromarray(reference).resize((reference.shape[1] // 4, reference.shape[0] // 4), Image.Resampling.BICUBIC))
        baseline, raw, conservative, details = infer(low, interpreter)
        details.update({"name": name, "input_shape": list(low.shape), "output_shape": list(conservative.shape)})
        details["mean_abs_difference_ai_vs_bicubic"] = round(float(np.mean(np.abs(conservative.astype(float) - baseline))), 4)
        if not native:
            details["metrics_rgb_no_border_shave"] = {key: {
                "psnr_db": round(float(peak_signal_noise_ratio(reference, value, data_range=255)), 4),
                "ssim": round(float(structural_similarity(reference, value, channel_axis=2, data_range=255)), 5)}
                for key, value in [("bicubic", baseline), ("raw_esrgan", raw), ("horizon_ai_default", conservative)]}
        if args.all_images:
            for key, value in [("input", low), ("bicubic", baseline), ("esrgan", raw), ("horizon_ai", conservative)]:
                Image.fromarray(value).save(args.out / f"{name}_{key}.png")
        elif not native:
            Image.fromarray(low).save(args.out / f"{name}_input.png")
        if not native:
            strip = Image.new("RGB", (reference.shape[1] * 4, reference.shape[0] + 24))
            for i, (label, array) in enumerate([("Reference", reference), ("Bicubic", baseline), ("ESRGAN", raw), ("Horizon AI", conservative)]):
                strip.paste(Image.fromarray(array), (i * reference.shape[1], 24))
                ImageDraw.Draw(strip).text((i * reference.shape[1] + 8, 5), label, fill="white")
            strip.save(args.out / f"{name}_comparison.png")
        report["fixtures"].append(details)
        print(json.dumps(details, indent=2))
    (args.out / "benchmark.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
