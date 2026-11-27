"""Fine-tune the existing Bayer CNN with two independent corruptions of real photo content."""
import argparse
import copy
import json
from pathlib import Path
import time

import numpy as np
from skimage.metrics import structural_similarity
import torch

from offdevice_corpus import digest, read_rgb, validate
from tele_raw_isp import denoise_mosaic
from tele_raw_restore import (HALO, PHASE_CHANNELS, features, load_network, noisy_cfa, pack,
                              restore, sample_cfa, torch_invoke, unpack)


def scene_planes(rgb):
    display = rgb.astype(np.float32) / 255
    linear = np.where(display <= .04045, display / 12.92, ((display + .055) / 1.055) ** 2.4)
    h, w = linear.shape[:2]
    # A display-linear GBRG proxy. No camera matrix, optics or DNG inversion is implied.
    return pack(sample_cfa(linear[:h // 2 * 2, :w // 2 * 2]))


def sample(scene, rng, side):
    h, w = scene.shape[:2]
    if min(h, w) < side:
        raise ValueError("Scene too small for packed patch")
    y, x = rng.integers(0, h - side + 1), rng.integers(0, w - side + 1)
    return scene[y:y + side, x:x + side]


def observations(truth, profile, rng):
    phase_profile = profile[PHASE_CHANNELS]
    return noisy_cfa(truth, phase_profile, rng), noisy_cfa(truth, phase_profile, rng)


def quality(estimate, truth):
    center = np.s_[HALO:-HALO, HALO:-HALO]
    a, b = estimate[center], truth[center]
    diff = a - b
    mse = float(np.square(diff).mean())
    gradients = lambda t: np.hypot(np.gradient(t, axis=0), np.gradient(t, axis=1))
    edge = gradients(b)
    strong = edge >= max(.01, float(np.quantile(edge, .80)))
    flat = edge <= .006
    return {"psnrLinear": -10 * np.log10(max(mse, 1e-18)),
            "ssimPacked": structural_similarity(a, b, channel_axis=2, data_range=1),
            "edgeGradientMae": float(np.abs(gradients(a) - edge)[strong].mean()) if strong.any() else None,
            "flatRmse": float(np.sqrt(np.square(diff[flat]).mean())) if flat.any() else None,
            "phaseBiasMax": float(np.abs(diff.mean(axis=(0, 1))).max())}


def evaluate(model, examples, controls):
    rows = []
    for name, target, noisy, profile in examples:
        inputs, sigma = features(noisy, profile)
        pred = restore(noisy, torch_invoke(model, inputs), sigma, guarded=True)
        row = {"scene": name, "network": quality(pred, target)}
        if controls:
            row["input"] = quality(noisy, target)
            row["wiener"] = quality(pack(denoise_mosaic(unpack(noisy), profile)), target)
        rows.append(row)
    return rows


def fixture(rows, profile, seed):
    rng = np.random.default_rng(seed)
    examples = []
    for name, scene in rows:
        for _ in range(8):
            target = sample(scene, rng, 56)
            noisy = noisy_cfa(target, profile[PHASE_CHANNELS], rng)
            examples.append((name, target, noisy, profile))
    return examples


def reduce_metrics(rows):
    result = {}
    for kind, metrics in rows[0].items():
        if kind == "scene":
            continue
        result[kind] = {}
        for key in metrics:
            values = [row[kind][key] for row in rows if row[kind][key] is not None]
            result[kind][key] = float(np.mean(values)) if values else None
    return result


def run(args):
    corpus = validate(args.manifest)
    record = json.loads(args.calibration.read_text(encoding="utf-8"))
    profile = np.asarray(record["declaredProfile"], np.float32) * np.asarray(record["flatVarianceScaleRgb"],
                                                                          np.float32).reshape(3, 1)
    if profile.shape != (3, 2) or not np.isfinite(profile).all() or not np.all(profile > 0):
        raise ValueError("Invalid calibration")
    if args.steps < 1 or args.threads < 1:
        raise ValueError("Positive iteration and thread counts required")
    args.out.mkdir(parents=True, exist_ok=False)
    torch.set_num_threads(args.threads)
    torch.manual_seed(args.seed)
    torch.use_deterministic_algorithms(True)
    splits = {s: [] for s in ("train", "validation", "test")}
    for entry in corpus["scenes"]:
        rgb = read_rgb(args.manifest.parent / entry["path"])
        splits[entry["split"]].append((entry["id"], scene_planes(rgb)))
    val = fixture(splits["validation"], profile, args.seed + 1)
    test = fixture(splits["test"], profile, args.seed + 2)
    initial = load_network(args.initial)
    if digest(args.initial) != record["weightsSha256"]:
        raise ValueError("Seed network differs from calibration/training record")
    results = dict(seed=args.seed, steps=args.steps, threads=args.threads, torch=str(torch.__version__),
                   numpy=np.__version__, domain="synthetic noise on real display-linear photographic content",
                   profile=profile.tolist(), initialSha=digest(args.initial), calibrationSha=digest(args.calibration),
                   corpusSha=digest(args.manifest), splits={k:[n for n,_ in v] for k,v in splits.items()},
                   checkpointSelection="lowest held-out validation MSE, including unchanged initialization",
                   initial=reduce_metrics(evaluate(initial, test, True)), runs={})
    def validation_score(net):
        return float(np.mean([10 ** (-r["network"]["psnrLinear"] / 10) for r in evaluate(net, val, False)]))
    for mode in ("supervised", "noise2noise"):
        rng = np.random.default_rng(args.seed)
        net = copy.deepcopy(initial).train()
        optim = torch.optim.AdamW(net.parameters(), lr=2e-4)
        best = validation_score(initial)
        selected, step_selected = copy.deepcopy(initial.state_dict()), 0
        history = [{"step": 0, "validationMse": best}]
        start = time.perf_counter()
        for step in range(1, args.steps + 1):
            items = []
            for _ in range(8):
                source = splits["train"][int(rng.integers(len(splits["train"])))][1]
                truth = sample(source, rng, 32)
                a, b = observations(truth, profile, rng)
                inputs, sigma = features(a, profile)
                target = truth if mode == "supervised" else b
                items.append((inputs, sigma, a, target))
            x, sigma, a, target = [torch.from_numpy(np.ascontiguousarray(np.stack(v).transpose(0, 3, 1, 2)))
                                    for v in zip(*items)]
            prediction = a + net(x) * sigma
            center = np.s_[:, :, HALO:-HALO, HALO:-HALO]
            loss = (prediction[center] - target[center]).square().mean()
            optim.zero_grad(set_to_none=True)
            loss.backward()
            optim.step()
            if step % 50 == 0 or step == args.steps:
                score = validation_score(net)
                history.append({"step": step, "validationMse": score})
                if score < best:
                    best, selected, step_selected = score, copy.deepcopy(net.state_dict()), step
        seconds = time.perf_counter() - start
        net.load_state_dict(selected)
        weights = args.out / (mode + ".pt")
        torch.save(selected, weights)
        tested = evaluate(net.eval(), test, False)
        results["runs"][mode] = dict(elapsedSeconds=seconds, weightsSha=digest(weights), bestStep=step_selected,
                                     valHistory=history, test=reduce_metrics(tested),
                                     scenes={name:reduce_metrics([r for r in tested if r["scene"] == name])
                                             for name,_ in splits["test"]})
    (args.out / "training.json").write_text(json.dumps(results, indent=2, allow_nan=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("manifest", "initial", "calibration", "out"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--steps", type=int, default=250)
    p.add_argument("--threads", type=int, default=2)
    p.add_argument("--seed", type=int, default=6421)
    run(p.parse_args())
