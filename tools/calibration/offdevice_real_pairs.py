"""Small Noise2Noise pilot on captured HDR+ observations, split by entire burst."""
import argparse
import copy
import json
from pathlib import Path
import time

import cv2
import numpy as np
import torch

from offline_burst import bilinear_phase, load_burst, register, registration_image
from offdevice_corpus import digest
from tele_raw_restore import HALO, features, load_network, pack, torch_invoke, restore


def prepare(path):
    burst = load_burst(path)
    if burst.kind != "hdrplus-capture":
        raise ValueError("This pilot needs a verified captured HDR+ burst")
    anchor, profile, _ = burst.read(burst.reference)
    plane = pack(anchor)
    input_features, sigma = features(plane, profile)
    src, units = registration_image(anchor)
    observations = []
    summary = []
    for i, frame in enumerate(burst.frames):
        if i == burst.reference:
            continue
        mosaic, _, _ = burst.read(i)
        proxy, _ = registration_image(mosaic)
        forward, backward = register(src, proxy, units), register(proxy, src, units)
        if not forward["accepted"] or not backward["accepted"]:
            continue
        shift = np.asarray(forward["dxdy"])
        cycle = float(np.linalg.norm(shift + np.asarray(backward["dxdy"])))
        if cycle > .5:
            continue
        target = np.empty_like(plane)
        good = np.ones(plane.shape[:2], bool)
        packed = pack(mosaic)
        for ch in range(4):
            a, valid, _, _ = bilinear_phase(packed[..., ch], *(shift / 2))
            target[..., ch] = a
            good &= valid & (a < .97) & (plane[..., ch] < .97)
        # Use pooled local change at a scale above sensor noise to reject motion.
        z2 = np.mean(((target - plane) / np.maximum(sigma, 1e-5)) ** 2, axis=2)
        good &= cv2.blur(np.minimum(z2, 64), (7, 7)) < 5
        if np.count_nonzero(good) < 4096:
            continue
        observations.append((target, good))
        summary.append(dict(frame=i, sha=frame.sha256, shift=shift.tolist(), cycle=cycle,
                            retained=float(good.mean())))
    if not observations:
        raise ValueError(f"No independent registered targets in {path}")
    return dict(scene=burst.provenance["datasetPath"], frames=[f.sha256 for f in burst.frames],
                provenanceSha=digest(path), accepted=summary, profile=profile, input=plane,
                features=input_features, sigma=sigma, targets=observations)


def patch(scene, rng):
    p = 40
    h, w = scene["input"].shape[:2]
    for _ in range(200):
        y, x = rng.integers(h-p+1), rng.integers(w-p+1)
        sl = np.s_[y:y+p, x:x+p]
        target, mask = scene["targets"][rng.integers(len(scene["targets"]))]
        if mask[sl].mean() >= .8:
            return scene["features"][sl], scene["sigma"][sl], scene["input"][sl], target[sl], mask[sl]
    raise ValueError("Not enough supported real patches")


def evaluate(net, examples):
    totals = np.zeros(3)
    count = 0
    for x, sigma, a, b, valid in examples:
        pred = restore(a, torch_invoke(net, x), sigma, guarded=True)
        interior = np.s_[HALO:-HALO, HALO:-HALO]
        mask = valid[interior]
        pred, a, b = [v[interior][mask] for v in (pred,a,b)]
        totals += [np.square(pred-b).sum(dtype=float), np.square(a-b).sum(dtype=float),
                   np.square(pred-a).sum(dtype=float)]
        count += pred.size
    return dict(zip(("targetMse", "identityTargetMse", "changeFromAnchorMse"), (totals/count).tolist()))


def run(args):
    if args.steps < 1:
        raise ValueError("Positive steps required")
    scenes = [prepare(p) for p in (args.train, args.validation, args.test)]
    ids = {s["scene"] for s in scenes}
    hashes = [h for s in scenes for h in s["frames"]]
    if len(ids) != 3 or len(hashes) != len(set(hashes)):
        raise ValueError("An original observation or scene was reused across partitions")
    args.out.mkdir(parents=True, exist_ok=False)
    torch.manual_seed(args.seed)
    torch.set_num_threads(2)
    torch.use_deterministic_algorithms(True)
    rng = np.random.default_rng(args.seed)
    holdouts = []
    for k, s in enumerate(scenes[1:]):
        g = np.random.default_rng(args.seed + 10 + k)
        holdouts.append([patch(s, g) for _ in range(32)])
    net = load_network(args.initial)
    initial_test = evaluate(net, holdouts[1])
    best = evaluate(net, holdouts[0])["targetMse"]
    best_step, weights = 0, copy.deepcopy(net.state_dict())
    history = [{"step":0,"validationMse":best}]
    optim = torch.optim.AdamW(net.parameters(), lr=.0001)
    start = time.perf_counter()
    for step in range(1, args.steps + 1):
        batch = [patch(scenes[0], rng) for _ in range(8)]
        tensors = [torch.from_numpy(np.stack(a).transpose(0,3,1,2).copy()) for a in zip(*[t[:4] for t in batch])]
        x, sigma, a, target = tensors
        valid = torch.from_numpy(np.stack([r[-1] for r in batch]))[:, None, HALO:-HALO, HALO:-HALO]
        area = np.s_[:, :, HALO:-HALO, HALO:-HALO]
        loss = ((a + net(x)*sigma - target)[area].square()*valid).sum() / (4*valid.sum())
        optim.zero_grad(set_to_none=True)
        loss.backward()
        optim.step()
        if not step % 25 or step == args.steps:
            val = evaluate(net, holdouts[0])["targetMse"]
            history.append({"step":step,"validationMse":val})
            if val < best:
                best, best_step, weights = val, step, copy.deepcopy(net.state_dict())
    elapsed = time.perf_counter()-start
    net.load_state_dict(weights)
    weights_path = args.out / "real-noise2noise.pt"
    torch.save(weights, weights_path)
    report = dict(seed=args.seed, elapsedSeconds=elapsed, steps=args.steps, bestStep=best_step,
                  initialSha=digest(args.initial), weightsSha=digest(weights_path), history=history,
                  observedTargets="separate physical exposure, aligned by phase-wise bilinear resampling",
                  splits={k:{f:s[f] for f in ("scene","frames","provenanceSha","accepted")}
                          for k,s in zip(("train","validation","test"), scenes)},
                  initialTest=initial_test, selectedTest=evaluate(net, holdouts[1]))
    (args.out/"report.json").write_text(json.dumps(report, indent=2, allow_nan=False)+"\n", encoding="utf-8")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    for key in ("train","validation","test","initial","out"):
        p.add_argument("--"+key, required=True, type=Path)
    p.add_argument("--steps", type=int, default=150)
    p.add_argument("--seed", type=int, default=1827)
    run(p.parse_args())
