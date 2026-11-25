"""Compare RAW-aware models with the existing single-frame tele RAW lab."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import time

import cv2
import numpy as np
import torch

from compare_tele_raw import (decode_jpeg, develop_raw, display_srgb, file_digest, orient_rgb,
                              require, validate_manifest)
from tele_isp_fixture import fixture_metrics, synthetic_fixture
from tele_raw_isp import (compare_display, denoise_mosaic, flat_noise_repeat, malvar, read_sensor,
                          render, save_display)
from tele_raw_restore import (PHASE_CHANNELS, blend_structures, load_network, noisy_cfa, pack,
                              predict_tiled, restore, sample_cfa, torch_invoke, unpack)


PMRID_SOURCE = "56290ec2bc430c58c16345bcac4ed14381b8f059fc1fe66428438bb0bb328970"
PMRID_WEIGHTS = "9361614f3514d27351d81909f2215c0fdc38619c0288d936b7266485ac106c14"
VARIANTS = ("mhc-wiener-gainmap", "neural-residual", "neural-guarded", "neural-hybrid", "pmrid-reno")


def load_pmrid(root):
    source = root / "models/net_torch.py"
    weights = root / "models/torch_pretrained.ckp"
    require(hashlib.sha256(source.read_bytes().replace(b"\r\n", b"\n")).hexdigest() == PMRID_SOURCE,
            "Expected the reviewed official PMRID architecture")
    require(file_digest(weights) == PMRID_WEIGHTS, "Expected the reviewed official PMRID weights")
    spec = importlib.util.spec_from_file_location("pmrid_reference", source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    model = module.Network().eval().to(memory_format=torch.channels_last)
    model.load_state_dict(torch.load(weights, map_location="cpu", weights_only=True), strict=True)
    return model


def reno_gain_noise(iso):
    """K and read variance in the Reno reference's 959-DN normalized space."""
    return ((.0005995267 * iso + .00868861) / 959,
            (7.11772e-7 * iso ** 2 + 6.514934e-4 * iso + .11492713) / 959 ** 2)


def restore_pmrid(mosaic, model, iso):
    # Add one phase-correct row on each side so the network sees RGGB order.
    rggb = pack(np.pad(mosaic, ((1, 1), (0, 0)), mode="reflect"))
    rggb = np.clip(rggb, 0, 1)
    h, w = rggb.shape[:2]
    top, left = (-h % 32) // 2, (-w % 32) // 2
    rggb = np.pad(rggb, ((top, -h % 32 - top), (left, -w % 32 - left), (0, 0)))
    slope, read = reno_gain_noise(iso)
    anchor_slope, anchor_read = reno_gain_noise(1600)
    gain = anchor_slope / slope
    offset = (read / slope ** 2 - anchor_read / anchor_slope ** 2) * anchor_slope
    nhwc = (rggb * gain + offset) * 256
    with torch.inference_mode():
        packed = torch.from_numpy(nhwc)[None].permute(0, 3, 1, 2)
        prediction = model(packed)[0, :, top:top + h, left:left + w].permute(1, 2, 0).numpy()
    require(np.isfinite(prediction).all(), "Non-finite PMRID output")
    return unpack((prediction / 256 - offset) / gain)[1:-1]


def candidates(mosaic, profile, inputs, iso):
    net, pmrid, scale = inputs
    planes = pack(mosaic)
    classical = denoise_mosaic(mosaic, profile)
    yield "mhc-wiener-gainmap", classical, {}
    started = time.perf_counter()
    normalized, sigma = predict_tiled(planes, profile * scale, lambda tile: torch_invoke(net, tile))
    timing = {"packedFeaturesAndTiledInferenceSeconds": time.perf_counter() - started}
    yield "neural-residual", unpack(restore(planes, normalized, sigma, guarded=False)), timing
    guarded = restore(planes, normalized, sigma, guarded=True)
    yield "neural-guarded", unpack(guarded), timing
    yield "neural-hybrid", unpack(blend_structures(planes, pack(classical), guarded, sigma)), timing
    del classical, normalized, sigma, guarded, planes
    started = time.perf_counter()
    output = restore_pmrid(mosaic, pmrid, iso)
    yield "pmrid-reno", output, {"wholeMosaicInferenceAndAdapterSeconds": time.perf_counter() - started}


def text_target():
    """Fixed held-out glyphs, varied contrast and an affine rotation."""
    rows = ("MICRO V3", "0-6-9-8", "RAW TEST", "1IlWmw", "GRAIN 52", "S24 30X")
    image = np.empty((240, 380), np.float32)
    clean_rows = []
    for line, text in enumerate(rows):
        low_value = .085 + .08 * line
        high_value = low_value + (.46 if line % 2 else .15)
        start = line * 40
        mask = np.zeros((40, 380), np.uint8)
        cv2.putText(mask, text, (12, 31), cv2.FONT_HERSHEY_DUPLEX, .63 + .08 * line, 255, 1, cv2.LINE_AA)
        geometry = cv2.getRotationMatrix2D((66, 25), (-1) ** line * 2.7, 1)
        mask = cv2.warpAffine(mask, geometry, (380, 40)).astype(np.float32) / 255
        mask = cv2.GaussianBlur(mask, (0, 0), .42)
        image[start:start + 40] = high_value - (high_value - low_value) * mask
        clean_rows.append((slice(start + 5, start + 36), .5 * (low_value + high_value)))
    return np.repeat(image[..., None], 3, axis=2), clean_rows


def stroke_dice(rgb, truth, rows):
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
    values = []
    for region, threshold in rows:
        expected = truth[region, :, 0] < threshold
        measured = gray[region] < threshold
        values.append(float(2 * np.count_nonzero(expected & measured) / (expected.sum() + measured.sum())))
    return float(np.mean(values))


def halo_p95(rgb, truth, region):
    """Excess outside the two plateaus measured near a known neutral edge."""
    luminance = cv2.cvtColor(rgb[region], cv2.COLOR_RGB2GRAY)
    reference = truth[region][:, :, 0]
    low, high = np.min(reference), np.max(reference)
    edge = np.abs(cv2.Sobel(reference, -1, 1, 0)) > .1
    vicinity = cv2.dilate(edge.astype(np.uint8), np.ones((3, 13), np.uint8)) > 0
    plateau = (reference == low) | (reference == high)
    overshoot = np.maximum(np.maximum(luminance - high, low - luminance), 0)
    return float(np.percentile(overshoot[vicinity & plateau], 95))


def synthetic_suite(profile, scale, inputs, output):
    rows = []
    rgb, _, regions = synthetic_fixture(1, 0, 0)
    text, text_regions = text_target()
    for noise_name, noise_profile in (("declared", profile), ("calibrated", profile * scale)):
        for seed in (1681, 5297, 7129):
            rng = np.random.default_rng(seed)
            for target_name, truth in (("chart", rgb), ("text", text)):
                source = unpack(noisy_cfa(pack(sample_cfa(truth)), noise_profile[PHASE_CHANNELS], rng))
                for name, restored, _ in candidates(source, profile, inputs, 705):
                    image = malvar(restored)
                    if target_name == "chart":
                        metrics = fixture_metrics(image, truth, regions)
                        metrics["edgePlateauOvershootP95"] = halo_p95(image, truth, regions["edge"])
                    else:
                        metrics = {"strokeDice": stroke_dice(image, truth, text_regions)}
                        if seed == 1681 and noise_name == "calibrated":
                            save_display(output / f"text-{name}.png", render(image, 0)[0])
                    rows.append({"seed": seed, "noise": noise_name, "target": target_name,
                                 "variant": name, "metrics": metrics})
    save_display(output / "text-reference.png", render(text, 0)[0])
    return rows


def field_pair(path, label, flat, inputs, output):
    output.mkdir()
    manifest = validate_manifest(path)
    sensor = read_sensor(path, manifest)
    jpeg, _ = decode_jpeg(path / "source.jpg", manifest)
    save_display(output / "jpeg.png", jpeg)
    ahd, _ = develop_raw(path / "source.dng", manifest)
    ahd, clipping = display_srgb(ahd, 1.5)
    save_display(output / "libraw-ahd.png", ahd)
    variants = {"libraw-ahd": {"clipFraction": clipping, **compare_display(ahd, jpeg, flat)}}
    del ahd
    gains = sensor.optics.gain_field(sensor.mosaic.shape)
    for name, restored, timing in candidates(sensor.mosaic, sensor.noise_profile, inputs, manifest["iso"]):
        rgb = malvar(restored * gains) @ sensor.color_matrix.T
        display, clipping = render(orient_rgb(rgb, sensor.orientation), 1.5)
        save_display(output / f"{name}.png", display)
        metrics = {"clipFraction": clipping, **timing, **compare_display(display, jpeg, flat)}
        if flat:
            # Keep temporary linear outputs for the temporal noise check. The input
            # images are evaluated individually and never averaged.
            np.save(output / f"{name}-mosaic.npy", restored, allow_pickle=False)
        variants[name] = metrics
        del rgb, display, restored
    validate_manifest(path)
    return {"label": label, "pairId": manifest["pairId"], "iso": manifest["iso"], "variants": variants}, sensor.noise_profile


def flat_repeat(output, profiles, names):
    results = {}
    for name in names:
        arrays = [np.load(output / folder / f"{name}-mosaic.npy", mmap_mode="r", allow_pickle=False)
                  for folder in ("flat-a", "flat-b")]
        results[name] = flat_noise_repeat(*arrays, *profiles)
    return results


def run(args):
    weights = args.training / "bayer-residual.pt"
    training = json.loads((args.training / "training.json").read_text(encoding="utf-8"))
    require(file_digest(weights) == training["weightsSha256"], "Weights and training record differ")
    scale = np.asarray(training["flatVarianceScaleRgb"], dtype=np.float32).reshape(3, 1)
    require(np.all(np.isfinite(scale) & (scale >= .1) & (scale <= 10)), "Invalid variance calibration")
    torch.set_num_threads(args.threads)
    net, pmrid = load_network(weights), load_pmrid(args.pmrid)
    pairs = (("flat-a", args.flat_a), ("flat-b", args.flat_b),
             ("detail-a", args.detail_a), ("detail-b", args.detail_b))
    manifests = {label: validate_manifest(path) for label, path in pairs}
    require([manifests[k]["pairId"] for k in ("flat-a", "flat-b")] == training["flatPairIds"],
            "Flat captures differ from the training calibration")
    require(len({m["sensorTimestampNs"] for m in manifests.values()}) == 4, "Expected four independent pairs")
    args.out.mkdir(parents=True, exist_ok=False)
    inputs = (net, pmrid, scale)
    field, flat_profiles = [], []
    for label, path in pairs:
        is_flat = label.startswith("flat")
        evidence, profile = field_pair(path, label, is_flat, inputs, args.out / label)
        field.append(evidence)
        if is_flat:
            flat_profiles.append(profile)
        print(label, evidence["pairId"], flush=True)
    temporal = flat_repeat(args.out, flat_profiles, VARIANTS)
    chart_profile = np.asarray(training["declaredProfile"], dtype=np.float32) * 1.25
    synthetic = synthetic_suite(chart_profile, scale, inputs, args.out)
    report = {"schemaVersion": 1, "weightsSha256": training["weightsSha256"], "threads": args.threads,
              "fieldPairs": field, "flatResidualVarianceRelativeToDeclaredInput": temporal,
              "syntheticInputs": {"declaredNoiseProfile": chart_profile.tolist(),
                                  "calibratedVarianceScaleRgb": scale.ravel().tolist()},
              "syntheticReferences": synthetic}
    (args.out / "restoration-report.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n",
                                                   encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("flat-a", "flat-b", "detail-a", "detail-b", "training", "pmrid", "out"):
        parser.add_argument(f"--{arg}", type=Path, required=True)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    require(args.threads > 0, "Expected a positive number of threads")
    run(args)
