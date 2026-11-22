"""Offline feasibility check for alignment and color on two photos of one flat target."""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageOps


def upright(path, width=960):
    with Image.open(path) as image:
        pixels = np.asarray(ImageOps.exif_transpose(image).convert("RGB"))
    height = round(width * pixels.shape[0] / pixels.shape[1])
    return cv2.resize(pixels, (width, height), interpolation=cv2.INTER_AREA)


def corresponding_points(wide, narrow):
    sift = cv2.SIFT_create(nfeatures=5500)
    key_wide, desc_wide = sift.detectAndCompute(cv2.cvtColor(wide, cv2.COLOR_RGB2GRAY), None)
    key_narrow, desc_narrow = sift.detectAndCompute(cv2.cvtColor(narrow, cv2.COLOR_RGB2GRAY), None)
    if desc_wide is None or desc_narrow is None or len(desc_narrow) < 2:
        raise ValueError("Insufficient image texture")
    matches = [a for a, b in cv2.BFMatcher().knnMatch(desc_wide, desc_narrow, k=2) if a.distance < 0.72 * b.distance]
    if len(matches) < 60:
        raise ValueError(f"Too few cross-lens correspondences: {len(matches)}")
    return (np.float32([key_wide[m.queryIdx].pt for m in matches]),
            np.float32([key_narrow[m.trainIdx].pt for m in matches]))


def compare(wide, narrow, zoom_ratio):
    if not np.isfinite(zoom_ratio) or zoom_ratio <= 1:
        raise ValueError("The measured narrow-to-wide ratio must exceed 1")
    source, target = corresponding_points(wide, narrow)
    shuffled = np.random.default_rng(6).permutation(len(source))
    split = len(source) * 2 // 3
    train, held_out = shuffled[:split], shuffled[split:]
    homography, mask = cv2.findHomography(source[train], target[train], cv2.RANSAC, 3.0)
    if homography is None or np.count_nonzero(mask) < 30:
        raise ValueError("The scene cannot be aligned with a reliable planar model")

    height, width = narrow.shape[:2]
    center_wide = np.array([wide.shape[1], wide.shape[0]]) / 2
    center_narrow = np.array([width, height]) / 2
    centered = (source[held_out] - center_wide) * zoom_ratio + center_narrow
    registered = cv2.perspectiveTransform(source[held_out, None], homography)[:, 0]
    distances = np.linalg.norm(registered - target[held_out], axis=1)
    coherent = distances <= 5.0
    if np.count_nonzero(coherent) < 20 or coherent.mean() < .45:
        raise ValueError("Too few held-out points agree with the planar model")
    coverage = cv2.contourArea(cv2.convexHull(target[held_out][coherent])) / (width * height)

    # Fit channel adjustments on alternating tiles, then evaluate on the opposite tiles.
    wide_normalized = wide.astype(np.float32) / 255.0
    narrow_normalized = narrow.astype(np.float32) / 255.0
    warp = cv2.warpPerspective(wide_normalized, homography, (width, height))
    valid = cv2.warpPerspective(np.ones(wide.shape[:2], np.uint8), homography, (width, height)) > 0
    valid &= (warp.min(axis=2) > 0.03) & (warp.max(axis=2) < 0.97)
    valid &= (narrow_normalized.min(axis=2) > 0.03) & (narrow_normalized.max(axis=2) < 0.97)
    yy, xx = np.ogrid[:height, :width]
    even = ((yy // 64 + xx // 64) % 2) == 0
    fit = valid & even
    score = valid & ~even
    if min(np.count_nonzero(fit), np.count_nonzero(score)) < 1000:
        raise ValueError("Insufficient unclipped shared pixels for photometric comparison")

    channel_input = cv2.GaussianBlur(warp, (5, 5), 0)[fit][::8]
    channel_target = cv2.GaussianBlur(narrow_normalized, (5, 5), 0)[fit][::8]
    params = []
    for source_channel, target_channel in zip(channel_input.T, channel_target.T):
        design = np.column_stack([source_channel, np.ones_like(source_channel)])
        gain, bias = np.linalg.lstsq(design, target_channel, rcond=None)[0]
        params.append((float(np.clip(gain, .8, 1.25)), float(np.clip(bias, -.08, .08))))
    gains, biases = np.asarray(params, dtype=np.float32).T
    corrected = np.clip(warp * gains + biases, 0, 1)
    lab_target = cv2.cvtColor(narrow_normalized, cv2.COLOR_RGB2LAB)[score]

    def median_delta(image):
        lab = cv2.cvtColor(image, cv2.COLOR_RGB2LAB)[score]
        return float(np.median(np.linalg.norm(lab - lab_target, axis=1)))

    return {
        "normalized_width_px": width,
        "matched_features": len(source),
        "held_out": len(held_out),
        "coherent_held_out_fraction": float(coherent.mean()),
        "coherent_frame_coverage": coverage,
        "center_crop_error_median_px": float(np.median(np.linalg.norm(centered[coherent] - target[held_out][coherent], axis=1))),
        "registered_error_median_px": float(np.median(distances[coherent])),
        "registered_error_p95_px": float(np.percentile(distances[coherent], 95)),
        "delta_e76_aligned_median": median_delta(warp),
        "delta_e76_after_channel_fit_median": median_delta(corrected),
        "channel_gain_bias": params,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wide", type=Path)
    parser.add_argument("narrow", type=Path)
    parser.add_argument("--zoom-ratio", required=True, type=float)
    args = parser.parse_args()
    print(json.dumps(compare(upright(args.wide), upright(args.narrow), args.zoom_ratio), indent=2))
