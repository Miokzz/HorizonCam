"""Train a small, noise-conditioned Bayer residual network for the tele RAW lab."""

import argparse
import json
from pathlib import Path
import time

import cv2
import numpy as np
import torch
from torch import nn

from compare_tele_raw import file_digest, require, validate_manifest
from tele_raw_isp import flat_noise_repeat, measured_noise_scale, phases, read_sensor


WIDTH = 16
HALO = 4
PHASE_CHANNELS = [color for _, _, color, _ in phases()]


def pack(mosaic):
    return np.stack([mosaic[site] for _, _, _, site in phases()], axis=-1)


def unpack(planes):
    mosaic = np.empty((planes.shape[0] * 2, planes.shape[1] * 2), dtype=planes.dtype)
    for index, (_, _, _, site) in enumerate(phases()):
        mosaic[site] = planes[..., index]
    return mosaic


def sample_cfa(rgb):
    return unpack(np.stack([rgb[site + (color,)] for _, _, color, site in phases()], axis=-1))


def variance(signal, profile):
    return np.maximum(signal, 0) * profile[..., 0] + profile[..., 1]


def noisy_cfa(clean, profile, rng):
    """Sample shot/read noise, then approximate the S24's 10-bit quantization."""
    slope, offset = profile[..., 0], profile[..., 1]
    photons = rng.poisson(np.maximum(clean, 0) / slope)
    noisy = photons * slope + rng.normal(0, np.sqrt(offset), size=clean.shape)
    return ((np.clip(np.rint(noisy * 958.67 + 64.33), 0, 1023) - 64.33) / 958.67).astype(np.float32)


def features(planes, profile):
    phase_profile = profile[PHASE_CHANNELS]
    # A local mean avoids letting the noise map encode strong individual outliers.
    mean = cv2.boxFilter(planes, -1, (3, 3), borderType=cv2.BORDER_REFLECT_101)
    sigma = np.sqrt(variance(mean, phase_profile)).astype(np.float32)
    return np.concatenate((planes * 2, sigma * 32), axis=-1), sigma


class BayerResidual(nn.Module):
    """Four local convolutions. Learned outputs are residuals in noise-sigma units."""

    def __init__(self):
        super().__init__()
        self.layers = nn.Sequential(
            nn.Conv2d(8, WIDTH, 3, padding=1), nn.ReLU(),
            nn.Conv2d(WIDTH, WIDTH, 3, padding=1), nn.ReLU(),
            nn.Conv2d(WIDTH, WIDTH, 3, padding=1), nn.ReLU(),
            nn.Conv2d(WIDTH, 4, 3, padding=1),
        )

    def forward(self, inputs):
        return self.layers(inputs)


def load_network(path):
    model = BayerResidual().eval()
    model.load_state_dict(torch.load(path, map_location="cpu", weights_only=True), strict=True)
    return model


def predict_tiled(planes, profile, invoke, tile=128):
    """Use real neighboring photosites; discard the halo instead of blending tiles."""
    height, width = planes.shape[:2]
    inputs, sigma = features(planes, profile)
    padded = np.zeros((height + 2 * HALO, width + 2 * HALO, 8), dtype=np.float32)
    padded[HALO:HALO + height, HALO:HALO + width] = inputs
    output = np.empty_like(planes)
    for y in range(0, height, tile):
        for x in range(0, width, tile):
            h, w = min(tile, height - y), min(tile, width - x)
            patch = padded[y:y + h + 2 * HALO, x:x + w + 2 * HALO]
            output[y:y + h, x:x + w] = invoke(patch)[HALO:HALO + h, HALO:HALO + w]
    return output, sigma


def torch_invoke(model, features_hwc):
    with torch.inference_mode():
        inputs = torch.from_numpy(np.ascontiguousarray(features_hwc.transpose(2, 0, 1)))[None]
        return model(inputs)[0].permute(1, 2, 0).numpy()


def restore(planes, normalized_residual, sigma, guarded):
    require(np.isfinite(normalized_residual).all(), "Non-finite network output")
    correction = np.clip(normalized_residual, -3, 3) * sigma
    weight = np.clip((.99 - planes) / .09, 0, 1)
    if guarded:
        mean = cv2.boxFilter(planes, -1, (5, 5), borderType=cv2.BORDER_REFLECT_101)
        moment = cv2.boxFilter(planes * planes, -1, (5, 5), borderType=cv2.BORDER_REFLECT_101)
        signal_to_noise = np.maximum((moment - mean * mean) / np.maximum(sigma ** 2, 1e-12) - 1, 0)
        weight /= 1 + signal_to_noise / 8
    return planes + correction * weight


def blend_structures(planes, classical, guarded, sigma):
    """Apply one structure weight across Bayer phases to avoid unequal color mixing."""
    green = .5 * (planes[..., 0] + planes[..., 3])
    mean = cv2.boxFilter(green, -1, (5, 5), borderType=cv2.BORDER_REFLECT_101)
    second = cv2.boxFilter(green ** 2, -1, (5, 5), borderType=cv2.BORDER_REFLECT_101)
    noise = .25 * (sigma[..., 0] ** 2 + sigma[..., 3] ** 2)
    structure = np.maximum(second - mean ** 2, 0) / np.maximum(noise, 1e-12)
    weight = .65 * np.clip((structure - 1.5) / 9, 0, 1)[..., None]
    # Keep the Wiener brightness estimate. Use only the learned local detail
    # difference where the two same-cell green sites provide spatial evidence.
    difference = guarded - classical
    detail = difference - cv2.boxFilter(difference, -1, (5, 5), borderType=cv2.BORDER_REFLECT_101)
    shoulder = np.clip((.99 - planes) / .09, 0, 1)
    return classical + weight * np.clip(detail, -2 * sigma, 2 * sigma) * shoulder


def procedural_patch(rng, size, y, x):
    """Independent clean camera-linear training target. No real pixels or JPEG labels."""
    background, foreground = rng.uniform(.012, .95, size=2)
    angle = rng.uniform(0, np.pi)
    distance = np.cos(angle) * (x - size / 2) + np.sin(angle) * (y - size / 2)
    family = int(rng.integers(5))
    if family == 0:
        pattern = np.broadcast_to(rng.uniform(.15, .85), (size, size)).copy()
        pattern += rng.uniform(-.15, .15) * x / size
    elif family == 1:
        pattern = (distance >= rng.uniform(-size / 3, size / 3)).astype(np.float32)
    elif family == 2:
        pattern = .5 + .5 * np.cos(distance * rng.uniform(.22, 2.55) + rng.uniform(0, 6))
    elif family == 3:
        coarse = rng.random((8, 8)).astype(np.float32)
        waves = np.cos(distance * rng.uniform(.12, 2.4) + .8 * np.sin(y / 5))
        pattern = .75 * cv2.resize(coarse, (size, size), interpolation=cv2.INTER_CUBIC) + .25 * waves
    else:
        pattern = np.zeros((size, size), np.uint8)
        for row in range(10, size, 13):
            word = str(int(rng.integers(100000, 999999)))
            cv2.putText(pattern, word, (int(rng.integers(-5, 8)), row), cv2.FONT_HERSHEY_PLAIN,
                        float(rng.uniform(.65, 1.05)), 255, 1, cv2.LINE_AA)
        pattern = pattern.astype(np.float32) / 255
    first, second = rng.uniform(.45, 1.4, size=(2, 3))
    a = background * first
    b = foreground * second
    rgb = np.clip(a + pattern[..., None] * (b - a), 0, .985).astype(np.float32)
    return cv2.GaussianBlur(rgb, (0, 0), float(rng.uniform(.35, 1.1)))


def training_calibration(flat_a, flat_b):
    first, second = (validate_manifest(path) for path in (flat_a, flat_b))
    require(first["sensorTimestampNs"] != second["sensorTimestampNs"], "Expected two distinct RAW captures")
    require(all(first[key] == second[key] for key in ("deviceModel", "firmware")), "Camera calibration mismatch")
    require(all(.97 <= first[key] / second[key] <= 1.03 for key in ("iso", "exposureNs")),
            "Flat captures differ in exposure")
    a, b = read_sensor(flat_a, first), read_sensor(flat_b, second)
    repeat = flat_noise_repeat(a.mosaic, b.mosaic, a.noise_profile, b.noise_profile)
    scale = measured_noise_scale({"flatField": {"noiseProfileRepeat": repeat}})
    return (a.noise_profile + b.noise_profile) / 2, scale, [first["pairId"], second["pairId"]]


def training_batch(rng, patches, nominal, scale, batch_size):
    xs, ys, sigmas = [], [], []
    for index in rng.integers(len(patches), size=batch_size):
        target = patches[index]
        # Vary shot-noise slope and read variance independently around the measured sensor.
        profile = nominal * scale * np.exp(rng.uniform(-1.25, .8, size=(1, 2)))
        profile = profile.astype(np.float32)
        noisy = noisy_cfa(target, profile[PHASE_CHANNELS], rng)
        inputs, sigma = features(noisy, profile)
        xs.append(inputs)
        ys.append(target)
        sigmas.append(sigma)

    def tensor(data):
        return torch.from_numpy(np.ascontiguousarray(np.stack(data).transpose(0, 3, 1, 2)))

    return tensor(xs), tensor(ys), tensor(sigmas)


def train(flat_a, flat_b, output, steps, seed, threads):
    output.mkdir(parents=True, exist_ok=False)
    nominal, scale, pair_ids = training_calibration(flat_a, flat_b)
    torch.set_num_threads(threads)
    torch.manual_seed(seed)
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[:64, :64]
    patches = [pack(sample_cfa(procedural_patch(rng, 64, y, x))) for _ in range(1024)]
    net = BayerResidual()
    with torch.no_grad():
        net.layers[-1].weight.mul_(.1)
        net.layers[-1].bias.zero_()
    optimizer = torch.optim.AdamW(net.parameters(), lr=.001)
    schedule = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, steps, eta_min=.00012)
    checkpoints = []
    started = time.perf_counter()
    for step in range(steps):
        inputs, target, sigma = training_batch(rng, patches, nominal, scale, 12)
        residual = net(inputs)
        region = np.s_[:, :, HALO:-HALO, HALO:-HALO]
        error = ((inputs[:, :4] * .5 + residual * sigma - target) / sigma)[region]
        loss = error.abs().mean()
        loss += .2 * (error.diff(dim=2).abs().mean() + error.diff(dim=3).abs().mean()) / 2
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        optimizer.step()
        schedule.step()
        if (step + 1) % 200 == 0 or step + 1 == steps:
            value = {"step": step + 1, "trainingLoss": float(loss.detach())}
            checkpoints.append(value)
            print(value, flush=True)
    weights = output / "bayer-residual.pt"
    torch.save(net.state_dict(), weights)
    record = {"seed": seed, "steps": steps, "threads": threads, "trainSeconds": time.perf_counter() - started,
              "torch": str(torch.__version__), "numpy": np.__version__, "flatPairIds": pair_ids,
              "declaredProfile": nominal.tolist(), "flatVarianceScaleRgb": scale.ravel().tolist(),
              "calibrationMode": "profiles and flat-field noise statistics only", "cleanTargets": "1024 procedural 64x64 patches",
              "weightsSha256": file_digest(weights), "checkpoints": checkpoints}
    (output / "training.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--flat-a", type=Path, required=True)
    parser.add_argument("--flat-b", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--steps", type=int, default=1600)
    parser.add_argument("--seed", type=int, default=4181)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    require(args.steps > 0 and args.threads > 0, "Training needs positive steps and threads")
    train(args.flat_a, args.flat_b, args.out, args.steps, args.seed, args.threads)
