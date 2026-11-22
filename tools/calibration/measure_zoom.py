"""Measure relative field of view from two upright frames of the same stationary target."""
import argparse
import json
import math

import cv2
import numpy as np
from PIL import Image, ImageOps


def grayscale(path):
    with Image.open(path) as image:
        return np.asarray(ImageOps.exif_transpose(image).convert("L"))


def measure(wide, narrow, expected, tolerance=0.10):
    """Images must have the same aspect and pose. Expected scale is used only for scoring."""
    if not math.isfinite(expected) or expected <= 0 or not 0 < tolerance < 1:
        raise ValueError("Expected scale and tolerance must be positive")
    sift = cv2.SIFT_create(nfeatures=7000)
    def features(image):
        h, w = image.shape
        scaled = cv2.resize(image, (1200, round(h * 1200 / w)), interpolation=cv2.INTER_AREA)
        return sift.detectAndCompute(scaled, None)

    result = {"expected_scale": expected, "status": "INCONCLUSIVE"}
    if abs((wide.shape[1] / wide.shape[0]) / (narrow.shape[1] / narrow.shape[0]) - 1) > 0.015:
        return {**result, "reason": "Different upright aspect ratios"}
    kw, dw = features(wide)
    kn, dn = features(narrow)
    if dw is None or dn is None or len(dn) < 2:
        return {**result, "reason": "Insufficient texture"}
    matches = [a for a, b in cv2.BFMatcher().knnMatch(dw, dn, k=2) if a.distance < 0.72 * b.distance]
    if len(matches) < 15:
        return {**result, "reason": "Insufficient common features", "matches": len(matches)}
    src = np.float32([kw[m.queryIdx].pt for m in matches])
    dst = np.float32([kn[m.trainIdx].pt for m in matches])
    model, mask = cv2.estimateAffinePartial2D(src, dst, method=cv2.RANSAC, ransacReprojThreshold=3)
    if model is None:
        return {**result, "reason": "No consistent similarity transform"}
    points = dst[mask.ravel() != 0]
    result.update(matches=len(matches), inliers=len(points))
    if len(points) < 15 or len(points) < 0.25 * len(matches):
        return {**result, "reason": "Too few inliers"}
    coverage = cv2.contourArea(cv2.convexHull(points)) / (1200**2 * narrow.shape[0] / narrow.shape[1])
    if coverage < 0.08:
        return {**result, "reason": "Matches concentrated on a small region"}
    scale = float(np.linalg.norm(model[:, 0]))
    return {**result, "status": "PASS" if abs(scale / expected - 1) <= tolerance else "FAIL",
            "measured_scale": scale, "inlier_coverage": coverage}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wide")
    parser.add_argument("narrow")
    parser.add_argument("--expected", required=True, type=float, help="Narrow zoom divided by wide zoom")
    parser.add_argument("--tolerance", default=0.10, type=float, help="Relative scale tolerance")
    args = parser.parse_args()
    report = measure(grayscale(args.wide), grayscale(args.narrow), args.expected, args.tolerance)
    print(json.dumps(report, indent=2))
    raise SystemExit({"PASS": 0, "FAIL": 1, "INCONCLUSIVE": 2}[report["status"]])
