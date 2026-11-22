"""CPU parity, odd-size tile assembly, and honest same-size blind restoration measurements."""
import argparse
import io
import json
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter
from skimage import data
from skimage.metrics import peak_signal_noise_ratio, structural_similarity
import tensorflow as tf
import torch

from convert_realesrgan import load_reference, sha256


def image(array):
    return Image.fromarray(np.clip(array, 0, 255).round().astype(np.uint8))


def reference_infer(reference, rgb):
    source = torch.from_numpy(np.ascontiguousarray(rgb.transpose(2, 0, 1))).unsqueeze(0).float() / 255
    with torch.inference_mode():
        return reference(source)[0].permute(1, 2, 0).numpy() * 255


def make_interpreter(model):
    runner = tf.lite.Interpreter(model_path=str(model), num_threads=2)
    runner.allocate_tensors()
    inp = runner.get_input_details()[0]["index"]
    out = runner.get_output_details()[0]["index"]
    def infer(rgb):
        runner.set_tensor(inp, np.ascontiguousarray(rgb[None], dtype=np.float32))
        runner.invoke()
        return runner.get_tensor(out)[0]
    return infer


def tiled(rgb, infer, core=32, halo=9, overlap=8):
    """Bounded 50px reflected input, overlapping center cores; NEVER resize a native patch."""
    height, width = rgb.shape[:2]
    output = np.zeros((height * 4, width * 4, 3), np.float64)
    weights = np.zeros((height * 4, width * 4, 1), np.float64)
    def reflect(indices, size):
        if size == 1:
            return np.zeros_like(indices)
        cycle = size * 2 - 2
        folded = indices % cycle
        return np.where(folded < size, folded, cycle - folded)
    def origins(size):
        values = [0]
        while values[-1] + core < size:
            values.append(min(values[-1] + core-overlap, size-core))
        return values
    for top in origins(height):
        for left in origins(width):
            rows = reflect(np.arange(top-halo, top-halo+50), height)
            cols = reflect(np.arange(left-halo, left-halo+50), width)
            patch = rgb[rows[:, None], cols[None, :], :]
            prediction = infer(patch)
            h, w = min(core, height-top) * 4, min(core, width-left) * 4
            center = prediction[halo*4:halo*4+h, halo*4:halo*4+w]
            # Nonzero tent weights avoid uncovered seams and fractional/odd crop border holes.
            vertical = np.minimum(1, np.minimum(np.arange(h)+1, np.arange(h, 0, -1))/32).astype(float)
            horizontal = np.minimum(1, np.minimum(np.arange(w)+1, np.arange(w, 0, -1))/32).astype(float)
            blend = (vertical[:, None] * horizontal[None, :])[..., None]
            output[top*4:top*4+h, left*4:left*4+w] += center * blend
            weights[top*4:top*4+h, left*4:left*4+w] += blend
    assert np.all(weights > 0), "Uncovered pixels in odd/native tiling"
    return (output/weights).astype(np.float32)


def metrics(truth, candidate):
    result = np.clip(candidate, 0, 255)
    return {"psnr_db": float(peak_signal_noise_ratio(truth, result, data_range=255)),
        "ssim": float(structural_similarity(truth, result, channel_axis=2, data_range=255)),
        "mae": float(np.mean(np.abs(truth.astype(float)-result)))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--baseline-model", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    reference, _ = load_reference(args.weights)
    infer = make_interpreter(args.model)
    baseline_infer = make_interpreter(args.baseline_model) if args.baseline_model else None
    rng = np.random.default_rng(20261121)
    y, x = np.mgrid[:50, :50]
    directional = np.stack((x*5, y*5, ((x+2*y)%50)*5), axis=-1).astype(np.float32)
    impulse = np.zeros((50, 50, 3), np.float32)
    impulse[8, 17, 0], impulse[22, 31, 1], impulse[38, 11, 2] = 255, 211, 173
    fixtures = {"rgb_direction_ramps": directional, "separate_rgb_impulses": impulse,
        "seeded_nonconstant_noise": rng.uniform(0, 255, (50, 50, 3)).astype(np.float32)}
    parity = []
    for name, fixture in fixtures.items():
        expected, actual = reference_infer(reference, fixture), infer(fixture)
        error = np.abs(expected-actual)
        result = {"name": name, "max_abs_0_255": float(error.max()), "mean_abs_0_255": float(error.mean())}
        assert result["max_abs_0_255"] < 0.05, f"Numerical/channel/PReLU/PixelShuffle parity failure: {result}"
        parity.append(result)
        image(fixture).save(args.output / (name + "-input.png"))
        image(actual).save(args.output / (name + "-tflite.png"))
    odd = []
    for height, width in [(1, 1), (17, 29), (51, 37), (63, 65)]:
        pixels = rng.uniform(0, 255, (height, width, 3)).astype(np.float32)
        actual = tiled(pixels, infer)
        expected_tiles = tiled(pixels, lambda tile: reference_infer(reference, tile))
        assert actual.shape == (height*4, width*4, 3) and np.isfinite(actual).all()
        error = float(np.max(np.abs(actual-expected_tiles)))
        assert error < 0.05, f"Odd-tile assembly differs from matching official PyTorch tiles: {error}"
        odd.append({"input": [width, height], "output": [width*4, height*4], "max_abs_tile_parity": error})
    benchmarks = []
    # NASA astronaut (public domain), redistributed by scikit-image. No portrait identity claims.
    original = Image.fromarray(data.astronaut())
    for name, crop, quality, sigma in [
        ("astronaut_face", (175, 65, 305, 195), 25, 12.),
        ("astronaut_detail", (15, 285, 145, 415), 40, 7.),
    ]:
        clean = np.asarray(original.crop(crop).resize((65, 65), Image.Resampling.LANCZOS)).astype(np.float32)
        degraded = image(clean + rng.normal(0, sigma, clean.shape))
        encoded = io.BytesIO()
        degraded.save(encoded, format="JPEG", quality=quality)
        degraded = Image.open(io.BytesIO(encoded.getvalue())).convert("RGB")
        pixels = np.asarray(degraded).astype(np.float32)
        started = time.perf_counter()
        raw4 = tiled(pixels, infer)
        elapsed = time.perf_counter()-started
        # Actual raw model prediction downsampled to 1x. No beauty/chroma/residual suppression.
        restored1 = np.asarray(image(raw4).resize(degraded.size, Image.Resampling.LANCZOS)).astype(np.float32)
        restored_area1 = np.clip(raw4,0,255).reshape(65,4,65,4,3).mean(axis=(1,3))
        bicubic1 = np.asarray(degraded.resize((260,260), Image.Resampling.BICUBIC)
            .resize(degraded.size, Image.Resampling.LANCZOS)).astype(np.float32)
        gaussian = np.asarray(degraded.filter(ImageFilter.GaussianBlur(.6))).astype(np.float32)
        benchmark = {"name": name, "dimensions": [65,65], "jpeg_quality": quality, "noise_sigma_0_255": sigma,
            "raw_model_4x_seconds_cpu": elapsed, "degraded_1x": metrics(clean, pixels),
            "bicubic_up_down_1x": metrics(clean, bicubic1), "gaussian_baseline_1x": metrics(clean, gaussian),
            "raw_realesrgan_lanczos_downsampled_1x": metrics(clean, restored1),
            "raw_realesrgan_area_downsampled_1x": metrics(clean, restored_area1)}
        # Report full-image-vs-fixed-tile deviation; tile equality is NOT claimed.
        full4 = reference_infer(reference, pixels)
        benchmark["fixed_tile_vs_official_full_4x_mae"] = float(np.mean(np.abs(raw4-full4)))
        if baseline_infer:
            prior4 = tiled(pixels, baseline_infer)
            prior1 = np.clip(prior4,0,255).reshape(65,4,65,4,3).mean(axis=(1,3))
            benchmark["previous_esrgan_area_downsampled_1x"] = metrics(clean, prior1)
            image(prior1).save(args.output / f"{name}-previous_esrgan_1x.png")
        benchmarks.append(benchmark)
        for suffix, array in [("truth",clean),("degraded",pixels),("bicubic_1x",bicubic1),
                              ("gaussian_1x",gaussian),("raw_model_1x",restored1),
                              ("raw_model_area_1x",restored_area1),("raw_model_4x",raw4)]:
            image(array).save(args.output / f"{name}-{suffix}.png")
    report = {"weights_sha256": sha256(args.weights), "tflite_sha256": sha256(args.model),
        "baseline_model_sha256": sha256(args.baseline_model) if args.baseline_model else None,
        "parity": parity, "odd_tile_checks": odd, "benchmarks": benchmarks,
        "tile_policy": {"input":50,"core":32,"halo":9,"overlap":8,"stride":24},
        "limitations": ["Synthetic degradations, two public-domain crops, not a representative quality study.",
            "PSNR/SSIM measure fidelity, not authenticity of generated details.",
            "Fixed50 tiles lack the full receptive context of the34-convolution reference; tile seams/context differences remain possible."]}
    (args.output / "validation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
