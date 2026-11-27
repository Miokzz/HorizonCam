"""Compare pretrained x4 RGB priors on private oriented crops using host CPU only."""

import argparse
import hashlib
import json
import math
import os
import platform
import re
import statistics
import sys
import time
from pathlib import Path


# Set these before NumPy, scipy or TensorFlow imports, including when run in CI.
for variable in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS",
                 "TF_NUM_INTRAOP_THREADS", "TF_NUM_INTEROP_THREADS"):
    os.environ[variable] = "2" if variable != "TF_NUM_INTEROP_THREADS" else "1"
os.environ["CUDA_VISIBLE_DEVICES"] = "-1"

import numpy as np
from PIL import Image, ImageOps
from scipy.ndimage import gaussian_filter, maximum_filter, minimum_filter, sobel
from skimage.metrics import structural_similarity


REPO = Path(__file__).resolve().parents[2]
ASSETS = REPO / "app/src/main/assets/ai"
SCALE = 4
TILE = 128
MODELS = {
    "realesrgan": ("RealESRGAN-context-f16.tflite", 34,
                   "1d8860d0608528a669562b431c654ac1616ab943ae4048b27b6211381b3fd869"),
    "quicksrnet": ("QuickSRNet-small-x4-128.tflite", 4,
                   "523609151d17484a0bfef7d60a2e9ddd762604138bf3067b9248aa436f831f3f"),
}
LEVELS = {"bicubic": 0.0, "mild": 0.25, "full": 1.0}
LUMA = np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)


def digest(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def output_directory(path):
    path = path.resolve()
    if path.is_relative_to(REPO):
        raise ValueError("The output directory must be outside the repository")
    return path


def load_cases(path):
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(data, list) or not data:
        raise ValueError("Manifest must be a non-empty array of cases")
    seen = set()
    for item in data:
        name = item["id"]
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,51}", name) or name in seen:
            raise ValueError(f"Invalid or duplicate fixture ID: {name!r}")
        seen.add(name)
        source = (path.parent / item["source"]).resolve(strict=True)
        with Image.open(source) as image:
            oriented = ImageOps.exif_transpose(image)
            left, top, right, bottom = item["box"]
            if not (0 <= left < right <= oriented.width and 0 <= top < bottom <= oriented.height):
                raise ValueError(f"Crop outside EXIF-oriented source: {name}")
            crop = oriented.crop((left, top, right, bottom)).convert("RGB")
        mode = item["mode"]
        if mode == "controlled":
            if crop.width % SCALE or crop.height % SCALE:
                raise ValueError(f"Controlled crop must divide exactly by {SCALE}: {name}")
            low = crop.resize((crop.width // SCALE, crop.height // SCALE), Image.Resampling.BOX)
            reference = np.asarray(crop)
        elif mode == "native":
            low, reference = crop, None
        else:
            raise ValueError(f"Unknown fixture mode: {mode}")
        if not all(7 <= length <= 256 for length in low.size):
            raise ValueError(f"Input crop dimensions must be within 7..256 pixels: {name}")
        metadata = {"id": name, "scene": item["scene"], "mode": mode,
                    "source_sha256": digest(source), "box_xyxy_exif_oriented": item["box"],
                    "input_width_height": list(low.size)}
        yield metadata, np.asarray(low), reference


def invoke_tiled(rgb, infer, halo, tile=TILE):
    """Keep the full receptive radius and assemble disjoint, native-pixel cores."""
    height, width = rgb.shape[:2]
    core = tile - 2 * halo
    if halo < 0 or core < 1:
        raise ValueError("Tile must have a positive core and a nonnegative halo")
    padded = np.pad(rgb, ((halo, halo + core), (halo, halo + core), (0, 0)), mode="reflect")
    output = np.empty((height * SCALE, width * SCALE, 3), dtype=np.float32)
    calls = 0
    for y in range(0, height, core):
        for x in range(0, width, core):
            patch = padded[y:y + tile, x:x + tile]
            prediction = infer(patch)
            rows, cols = min(core, height - y) * SCALE, min(core, width - x) * SCALE
            inset = halo * SCALE
            output[y * SCALE:y * SCALE + rows, x * SCALE:x * SCALE + cols] = (
                prediction[inset:inset + rows, inset:inset + cols]
            )
            calls += 1
    if not np.isfinite(output).all():
        raise ValueError("Model returned a non-finite pixel")
    return output, calls


def byte_image(values):
    return np.clip(values, 0, 255).round().astype(np.uint8)


def bicubic(rgb):
    height, width = rgb.shape[:2]
    return np.asarray(Image.fromarray(rgb).resize((width * SCALE, height * SCALE), Image.Resampling.BICUBIC))


def blend(baseline, prediction, strength):
    return byte_image(baseline.astype(np.float32) * (1 - strength) + prediction.astype(np.float32) * strength)


def area_project(image):
    height, width = image.shape[:2]
    return image.reshape(height // SCALE, SCALE, width // SCALE, SCALE, 3).mean(axis=(1, 3), dtype=np.float32)


def comparisons(reference, candidate):
    error = reference.astype(np.float32) - candidate.astype(np.float32)
    mse = float(np.mean(error ** 2))
    return {
        "ssim_rgb": float(structural_similarity(reference, candidate, channel_axis=2, data_range=255)),
        "psnr_db": 10 * math.log10(255 ** 2 / mse) if mse else None,
        "mae_dn": float(np.abs(error).mean()),
    }


def evidence(rgb, baseline, candidate, reference):
    projected = area_project(candidate)
    native_y, projected_y = rgb @ LUMA, projected @ LUMA
    source_edges = np.stack([sobel(native_y, axis=axis) / 8 for axis in (0, 1)], axis=-1)
    output_edges = np.stack([sobel(projected_y, axis=axis) / 8 for axis in (0, 1)], axis=-1)
    edge_magnitude = np.linalg.norm(source_edges, axis=-1)
    strong = edge_magnitude >= 8
    inner_product = float(np.sum(source_edges[strong] * output_edges[strong]))
    edge_norm = float(np.linalg.norm(source_edges[strong]) * np.linalg.norm(output_edges[strong]))

    delta = projected - rgb
    luma_drift = projected_y - native_y
    output_y = candidate @ LUMA
    baseline_y = baseline @ LUMA
    low = minimum_filter(native_y, size=3).repeat(SCALE, axis=0).repeat(SCALE, axis=1)
    high = maximum_filter(native_y, size=3).repeat(SCALE, axis=0).repeat(SCALE, axis=1)
    baseline_hp = baseline_y - gaussian_filter(baseline_y, 1)
    output_hp = output_y - gaussian_filter(output_y, 1)
    smooth = (high - low) <= 8
    report = {
        "source_roundtrip": comparisons(rgb, projected),
        "source_strong_edge_cosine": inner_product / edge_norm if edge_norm else None,
        "source_strong_edge_pixel_count": int(strong.sum()),
        "roundtrip_mean_luma_drift_dn": float(luma_drift.mean()),
        "roundtrip_chroma_mae_dn": float(np.mean(np.abs(delta - luma_drift[..., None]))),
        "beyond_native_luma_envelope_fraction": float(np.mean((output_y < low - 3) | (output_y > high + 3))),
        "output_highpass_rms_dn": float(np.sqrt(np.mean(output_hp ** 2))),
        "smooth_region_highpass_delta_rms_dn": (
            float(np.sqrt(np.mean((output_hp[smooth] - baseline_hp[smooth]) ** 2))) if smooth.any() else None
        ),
    }
    if reference is not None:
        report["controlled_aligned_reference"] = comparisons(reference, candidate)
    return report


def peak_working_set_bytes():
    if sys.platform != "win32":
        import resource

        peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        return int(peak * (1 if sys.platform == "darwin" else 1024))

    import ctypes
    from ctypes import wintypes

    class ProcessMemoryCounters(ctypes.Structure):
        _fields_ = ([("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)]
                    + [(field, ctypes.c_size_t) for field in (
                        "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
                        "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")])

    handle = ctypes.windll.kernel32.GetCurrentProcess
    handle.restype = wintypes.HANDLE
    query = ctypes.windll.psapi.GetProcessMemoryInfo
    query.argtypes = [wintypes.HANDLE, ctypes.POINTER(ProcessMemoryCounters), wintypes.DWORD]
    query.restype = wintypes.BOOL
    counters = ProcessMemoryCounters()
    counters.cb = ctypes.sizeof(counters)
    if not query(handle(), ctypes.byref(counters), counters.cb):
        raise OSError("GetProcessMemoryInfo failed")
    return counters.PeakWorkingSetSize


def make_inference(tf, model_path):
    runner = tf.lite.Interpreter(model_path=str(model_path), num_threads=2)
    runner.allocate_tensors()
    inputs, outputs = runner.get_input_details(), runner.get_output_details()
    for metadata, side in ((inputs, TILE), (outputs, TILE * SCALE)):
        if (len(metadata) != 1 or list(metadata[0]["shape"]) != [1, side, side, 3]
                or metadata[0]["dtype"] != np.float32):
            raise ValueError("Unexpected model RGB tensor contract")

    invocation_ms = []

    def infer(rgb):
        runner.set_tensor(inputs[0]["index"], np.ascontiguousarray(rgb[None], dtype=np.float32))
        start = time.perf_counter()
        runner.invoke()
        invocation_ms.append((time.perf_counter() - start) * 1000)
        return runner.get_tensor(outputs[0]["index"])[0]

    return infer, invocation_ms


def benchmark(model_name, manifest, output):
    output = output_directory(output)
    model_file, halo, model_sha256 = MODELS[model_name]
    model_path = ASSETS / model_file
    if digest(model_path) != model_sha256:
        raise ValueError(f"Reviewed model digest mismatch: {model_file}")

    import tensorflow as tf

    tf.config.set_visible_devices([], "GPU")
    peak_before_model = peak_working_set_bytes()
    start = time.perf_counter()
    infer, invocation_ms = make_inference(tf, model_path)
    allocate_ms = (time.perf_counter() - start) * 1000
    start = time.perf_counter()
    infer(np.full((TILE, TILE, 3), 127, dtype=np.float32))
    first_tile_ms = (time.perf_counter() - start) * 1000

    report = {
        "model": model_name, "model_sha256": model_sha256, "model_bytes": model_path.stat().st_size,
        "python": platform.python_version(), "platform": platform.platform(), "cpu": platform.processor(),
        "tensorflow": tf.__version__, "threads": 2, "tile": TILE, "halo": halo, "scale": SCALE,
        "manifest_sha256": digest(manifest), "alpha_rgb": LEVELS,
        "allocate_ms_excluding_import": allocate_ms, "first_neutral_tile_ms": first_tile_ms,
        "first_neutral_invoke_ms": invocation_ms[0],
        "peak_working_set_bytes_before_model": peak_before_model,
        "cases": [],
    }
    for metadata, rgb, reference in load_cases(manifest):
        start = time.perf_counter()
        baseline = bicubic(rgb)
        baseline_ms = (time.perf_counter() - start) * 1000
        timings = []
        inference_timings = []
        for _ in range(3):
            invocation_ms.clear()
            start = time.perf_counter()
            raw_prediction, calls = invoke_tiled(rgb, infer, halo)
            timings.append((time.perf_counter() - start) * 1000)
            inference_timings.append(sum(invocation_ms))
        prediction = np.clip(raw_prediction, 0, 255)

        fixture_directory = output / metadata["id"]
        fixture_directory.mkdir(parents=True, exist_ok=True)
        Image.fromarray(rgb).save(fixture_directory / "input.png")
        if reference is not None:
            Image.fromarray(reference).save(fixture_directory / "reference.png")
        levels = {}
        for label, alpha in LEVELS.items():
            candidate = blend(baseline, prediction, alpha)
            path = fixture_directory / f"{label}.png"
            Image.fromarray(candidate).save(path)
            levels[label] = {"output_sha256": digest(path), **evidence(rgb, baseline, candidate, reference)}

        report["cases"].append({
            **metadata, "output_width_height": [baseline.shape[1], baseline.shape[0]],
            "bicubic_ms": baseline_ms, "tile_calls": calls, "warm_tiled_ms": timings,
            "warm_tiled_median_ms": statistics.median(timings),
            "warm_invoke_sum_ms": inference_timings,
            "warm_invoke_sum_median_ms": statistics.median(inference_timings),
            "raw_out_of_range_fraction": float(np.mean((raw_prediction < 0) | (raw_prediction > 255))),
            "levels": levels,
        })

    report["peak_working_set_bytes"] = peak_working_set_bytes()
    (output / "report.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(f"{model_name}: {len(report['cases'])} cases. Report written to {output / 'report.json'}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, choices=MODELS)
    parser.add_argument("--manifest", required=True, type=Path, help="Private JSON fixture manifest")
    parser.add_argument("--out", required=True, type=Path, help="Private output directory outside the repository")
    args = parser.parse_args()
    benchmark(args.model, args.manifest, args.out)


if __name__ == "__main__":
    main()
