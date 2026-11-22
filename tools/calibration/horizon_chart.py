"""Fail-closed local geometry oracle for the matching Horizon chart.

Dependencies: Python 3.12, numpy, Pillow, opencv-contrib-python-headless.
Generate: python tools/calibration/horizon_chart.py --make-target ../s24-qa/horizon-target.png
Inspect:  python tools/calibration/horizon_chart.py --input clip.mp4 --out report-dir --setup-confirmed

Physical --setup-confirmed means the target is level, fronto-parallel to the
optical axis, and printed/displayed without nonuniform scaling. A planar chart
alone cannot distinguish oblique viewing from digital anamorphic distortion.
No ADB, camera control, upload, runtime modification, or smoke integration.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageOps

WIDTH, HEIGHT = 1200, 800
MARKER_SIDE = 90
MARKERS = {1: (240, 160), 7: (960, 160), 13: (960, 640), 29: (240, 640)}
CIRCLE_CENTER = (600, 344)
CIRCLE_RADIUS = 140
HORIZON = ((360, 544), (840, 544))
DICTIONARY_ID = cv2.aruco.DICT_4X4_50


def dictionary():
    return cv2.aruco.getPredefinedDictionary(DICTIONARY_ID)


def render_target():
    image = np.full((HEIGHT, WIDTH, 3), 255, np.uint8)
    for marker_id, (cx, cy) in MARKERS.items():
        marker = cv2.aruco.generateImageMarker(dictionary(), marker_id, MARKER_SIDE)
        left, top = cx - MARKER_SIDE // 2, cy - MARKER_SIDE // 2
        image[top:top + MARKER_SIDE, left:left + MARKER_SIDE] = marker[..., None]
    cv2.circle(image, CIRCLE_CENTER, CIRCLE_RADIUS, (0, 0, 0), 9, cv2.LINE_AA)
    cv2.line(image, HORIZON[0], HORIZON[1], (0, 0, 0), 8, cv2.LINE_AA)
    cv2.arrowedLine(image, (600, 125), (600, 55), (0, 0, 0), 8, tipLength=0.3)
    cv2.putText(image, "UP / CIMA", (655, 95), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 0), 2, cv2.LINE_AA)
    cv2.putText(image, "HORIZON GEOMETRY 1", (375, 725), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 0, 0), 2, cv2.LINE_AA)
    cv2.putText(image, "LEVEL + FRONT-PARALLEL / NO STRETCH", (280, 770), cv2.FONT_HERSHEY_SIMPLEX, 0.65, (0, 0, 0), 1, cv2.LINE_AA)
    return image


def angle_delta(a, b=0.0):
    return (a - b + 180.0) % 360.0 - 180.0


def project(homography, points):
    return cv2.perspectiveTransform(np.asarray(points, np.float32).reshape(-1, 1, 2), homography).reshape(-1, 2)


def unresolved(reason, **extra):
    return {"status": "INCONCLUSIVE", "confidence": 0.0, "reasons": [reason], **extra}


def analyze(rgb, setup_confirmed=False, max_dimension=1600):
    """Pure image-array analysis. No full homography rectification: that would erase stretch."""
    if rgb.ndim != 3 or rgb.shape[2] != 3 or min(rgb.shape[:2]) < 100:
        return unresolved("Missing or tiny RGB frame"), rgb
    scale = min(1.0, max_dimension / max(rgb.shape[:2]))
    image = cv2.resize(rgb, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA) if scale < 1 else rgb.copy()
    gray = cv2.cvtColor(image, cv2.COLOR_RGB2GRAY)
    detector = cv2.aruco.ArucoDetector(dictionary(), cv2.aruco.DetectorParameters())
    corners, ids, _ = detector.detectMarkers(gray)
    detected = {} if ids is None else {int(i): c.reshape(4, 2) for i, c in zip(ids.flatten(), corners)}
    expected_found = set(detected).intersection(MARKERS)
    if len(expected_found) != 4 or (ids is not None and len(ids.flatten()) != len(set(ids.flatten()))):
        return unresolved("All four unique matching fiducials are required", marker_ids=sorted(detected)), image
    centers = {key: np.mean(detected[key], axis=0) for key in MARKERS}
    source_corners, image_corners = [], []
    for key, (cx, cy) in MARKERS.items():
        half = MARKER_SIDE // 2
        source_corners.extend([(cx-half, cy-half), (cx+half-1, cy-half),
                               (cx+half-1, cy+half-1), (cx-half, cy+half-1)])
        image_corners.extend(detected[key])
    homography, inliers = cv2.findHomography(np.float32(source_corners), np.float32(image_corners), cv2.RANSAC, 2.5)
    if homography is None or inliers is None or int(inliers.sum()) < 14:
        return unresolved("Fiducial geometry is inconsistent or strongly distorted", marker_ids=sorted(detected)), image
    reprojection = project(homography, source_corners) - np.asarray(image_corners)
    marker_rms = float(np.sqrt(np.mean(np.sum(reprojection**2, axis=1))))
    marker_sizes = [float(np.linalg.norm(c[1]-c[0])) for c in detected.values()]
    marker_size = float(np.median(marker_sizes))
    if marker_size < 20 or marker_rms > max(2.5, marker_size * 0.035):
        return unresolved("Markers are too small or reprojection confidence is low", marker_rms_px=marker_rms), image

    tl, tr, br, bl = (centers[key] for key in (1, 7, 13, 29))
    horizontal = ((tr-tl)+(br-bl))/2
    vertical = ((bl-tl)+(br-tr))/2
    orientation = math.degrees(math.atan2(float(horizontal[1]), float(horizontal[0])))
    horizon_angle = (orientation+90) % 180 - 90
    expected_ratio = (640-160)/(960-240)
    marker_anisotropy = float(np.linalg.norm(vertical)/np.linalg.norm(horizontal)/expected_ratio)
    perspective = max(abs(math.log(np.linalg.norm(tr-tl)/np.linalg.norm(br-bl))),
                      abs(math.log(np.linalg.norm(bl-tl)/np.linalg.norm(br-tr))))
    skew = abs(float(np.dot(horizontal, vertical)/(np.linalg.norm(horizontal)*np.linalg.norm(vertical))))

    predicted_center = project(homography, [CIRCLE_CENTER])[0]
    projected_axes = project(homography, [(CIRCLE_CENTER[0]+CIRCLE_RADIUS, CIRCLE_CENTER[1]),
                                           (CIRCLE_CENTER[0], CIRCLE_CENTER[1]+CIRCLE_RADIUS)])
    predicted_radius = float(np.mean(np.linalg.norm(projected_axes-predicted_center, axis=1)))
    dark = np.uint8(gray < 95) * 255
    contours, _ = cv2.findContours(dark, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
    circles = []
    for contour in contours:
        if len(contour) < 40:
            continue
        (cx, cy), (axis_a, axis_b), degrees = cv2.fitEllipse(contour)
        if min(axis_a, axis_b) < 35 or max(axis_a, axis_b) > max(image.shape[:2]) * 0.65:
            continue
        if np.linalg.norm(np.array([cx, cy])-predicted_center) > predicted_radius * 0.18:
            continue
        if not 0.65*predicted_radius < (axis_a+axis_b)/4 < 1.45*predicted_radius:
            continue
        relative = contour.reshape(-1, 2).astype(float) - (cx, cy)
        radians = math.radians(degrees)
        rotation = np.array([[math.cos(radians), math.sin(radians)], [-math.sin(radians), math.cos(radians)]])
        local = relative @ rotation.T
        residual = float(np.mean(np.abs(np.sqrt((local[:, 0]/(axis_a/2))**2+(local[:, 1]/(axis_b/2))**2)-1)))
        if residual < 0.035:
            circles.append((cv2.contourArea(contour), (cx, cy), axis_a, axis_b, degrees, residual))
    if not circles:
        return unresolved("Matching central circle is missing, occluded or not measurable", marker_ids=sorted(detected)), image
    _, center, axis_a, axis_b, ellipse_angle, ellipse_residual = max(circles, key=lambda item: item[0])
    relative_angle = math.radians(ellipse_angle-orientation)
    circle_width = math.hypot(axis_a*math.cos(relative_angle), axis_b*math.sin(relative_angle))
    circle_height = math.hypot(axis_a*math.sin(relative_angle), axis_b*math.cos(relative_angle))
    circle_anisotropy = circle_height/circle_width
    circle_axis_ratio = max(axis_a, axis_b)/min(axis_a, axis_b)

    line_points = project(homography, HORIZON)
    line_mask = np.zeros_like(gray)
    polygon = project(homography, [(345, 525), (855, 525), (855, 563), (345, 563)])
    cv2.fillConvexPoly(line_mask, np.int32(polygon), 255)
    roi = cv2.bitwise_and(dark, line_mask)
    expected_line_length = float(np.linalg.norm(line_points[1]-line_points[0]))
    lines = cv2.HoughLinesP(roi, 1, np.pi/720, threshold=max(20, int(expected_line_length*0.25)),
                            minLineLength=expected_line_length*0.65, maxLineGap=max(4, marker_size*0.08))
    eligible_lines = []
    for values in [] if lines is None else lines.reshape(-1, 4):
        x1, y1, x2, y2 = [int(v) for v in values]
        angle = math.degrees(math.atan2(y2-y1, x2-x1))
        difference = abs((angle-orientation+90) % 180 - 90)
        if difference < 3:
            eligible_lines.append((math.hypot(x2-x1, y2-y1), angle, (x1, y1, x2, y2)))
    if not eligible_lines:
        return unresolved("Matching independent horizon line is missing", marker_ids=sorted(detected)), image
    _, measured_line_angle, line = max(eligible_lines)
    measured_horizon = (measured_line_angle+90) % 180 - 90

    confidence = float(min(1.0, 1-marker_rms/max(10, marker_size), 1-ellipse_residual*5))
    reasons = []
    if abs(orientation) > 90:
        reasons.append("Chart is upside down or turned sideways")
    if abs(horizon_angle) > 2.5 or abs(measured_horizon) > 2.5:
        reasons.append("World horizon is not level within 2.5 degrees")
    if abs(math.log(circle_anisotropy)) > math.log(1.06):
        reasons.append("Circle has more than 6 percent anisotropic scale")
    if abs(math.log(marker_anisotropy)) > math.log(1.06):
        reasons.append("Fiducial metric has more than 6 percent anisotropic scale")
    geometry_disagreement = abs(math.log(circle_anisotropy/marker_anisotropy))
    setup_issues = []
    if perspective > 0.08 or skew > 0.08:
        setup_issues.append("Perspective/skew exceeds the fronto-parallel fixture tolerance")
    if geometry_disagreement > 0.06:
        setup_issues.append("Circle and fiducials disagree; lens distortion, crop or occlusion may invalidate measurement")
    if not setup_confirmed:
        setup_issues.append("Level/fronto-parallel/unstretched target setup was not confirmed")
    status = "INCONCLUSIVE" if setup_issues or confidence < 0.90 else ("FAIL" if reasons else "PASS")
    report = {"status": status, "confidence": round(confidence, 4), "reasons": reasons+setup_issues,
              "marker_ids": sorted(expected_found), "orientation_angle_deg": round(orientation, 4),
              "horizon_angle_deg": round(horizon_angle, 4), "measured_line_angle_deg": round(measured_horizon, 4),
              "vertical_to_horizontal_scale": round(marker_anisotropy, 5),
              "circle_vertical_to_horizontal_scale": round(circle_anisotropy, 5),
              "circle_major_minor_ratio": round(circle_axis_ratio, 5), "circle_fit_residual": round(ellipse_residual, 5),
              "marker_rms_px": round(marker_rms, 4), "perspective_indicator": round(perspective, 5),
              "skew_indicator": round(skew, 5), "setup_confirmed": bool(setup_confirmed),
              "scope": "Geometry of this decoded matching-chart frame only; no proof of device rotation or 360-degree sequence."}
    for key, corner in detected.items():
        cv2.polylines(image, [np.int32(corner)], True, (0, 170, 0), 2)
        cv2.putText(image, str(key), tuple(np.int32(centers[key])), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 120, 0), 2)
    cv2.ellipse(image, (tuple(center), (axis_a, axis_b), ellipse_angle), (220, 20, 20), 2)
    cv2.line(image, line[:2], line[2:], (30, 80, 220), 2)
    return report, image


def inspect_media(path, samples, setup_confirmed):
    if path.stat().st_size > 512*1024*1024:
        raise ValueError("Input exceeds the bounded 512MiB inspection limit")
    if path.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tif", ".tiff"}:
        with Image.open(path) as image:
            if image.width*image.height > 50_000_000:
                raise ValueError("Photo exceeds 50 million pixels; make a faithful bounded analysis copy")
            rgb = np.asarray(ImageOps.exif_transpose(image).convert("RGB"))
        return [(0.0, *analyze(rgb, setup_confirmed))], {"kind": "photo", "orientation": "EXIF transposed once"}
    capture = cv2.VideoCapture(str(path))
    try:
        if not capture.isOpened():
            raise ValueError("Cannot decode input video")
        capture.set(cv2.CAP_PROP_ORIENTATION_AUTO, 1)
        rotation_auto = bool(capture.get(cv2.CAP_PROP_ORIENTATION_AUTO))
        rotation_metadata = capture.get(cv2.CAP_PROP_ORIENTATION_META)
        frame_count, fps = capture.get(cv2.CAP_PROP_FRAME_COUNT), capture.get(cv2.CAP_PROP_FPS)
        if not math.isfinite(fps) or fps <= 0 or frame_count < 2:
            raise ValueError("Missing reliable video duration/index metadata")
        duration = frame_count/fps
        if duration > 300:
            raise ValueError("Video exceeds five-minute oracle limit; inspect a representative copied clip")
        times = np.linspace(min(0.1, duration/4), max(0, duration-0.1), min(samples, int(frame_count)))
        result = []
        for seconds in times:
            capture.set(cv2.CAP_PROP_POS_MSEC, float(seconds)*1000)
            ok, bgr = capture.read()
            if not ok:
                result.append((float(seconds), unresolved("Video frame could not be decoded"), None))
                continue
            report, annotated = analyze(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB), setup_confirmed)
            if not rotation_auto:
                report["status"] = "INCONCLUSIVE"
                report["reasons"].append("Decoder did not confirm display-orientation handling")
            result.append((float(seconds), report, annotated))
        return result, {"kind": "video", "duration_seconds": duration, "decoder_rotation_auto": rotation_auto,
                        "decoder_rotation_metadata_degrees": rotation_metadata, "sampled_frames": len(result)}
    finally:
        capture.release()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--make-target", type=Path)
    parser.add_argument("--input", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--samples", type=int, default=24)
    parser.add_argument("--setup-confirmed", action="store_true")
    args = parser.parse_args()
    if args.make_target:
        args.make_target.parent.mkdir(parents=True, exist_ok=True)
        Image.fromarray(render_target()).save(args.make_target)
        print(json.dumps({"target": str(args.make_target), "pixels": [WIDTH, HEIGHT],
                          "instructions": "Print/display without stretching; target level, optical axis fronto-parallel; keep all four IDs and circle visible."}))
        return 0
    if args.input is None or args.out is None or not 1 <= args.samples <= 60:
        parser.error("Use --input PATH --out DIRECTORY and --samples in 1..60")
    args.out.mkdir(parents=True, exist_ok=True)
    try:
        inspected, metadata = inspect_media(args.input, args.samples, args.setup_confirmed)
        records = []
        for index, (seconds, report, image) in enumerate(inspected):
            report["time_seconds"] = round(seconds, 4)
            if image is not None:
                name = f"frame-{index:03d}.png"
                Image.fromarray(image).save(args.out/name)
                report["annotated_frame"] = name
            records.append(report)
        passes = sum(record["status"] == "PASS" for record in records)
        failures = sum(record["status"] == "FAIL" for record in records)
        # Every sampled frame must be conclusive. A clear failure is retained
        # even if other portions lose the chart; missing frames never become PASS.
        status = "FAIL" if failures else ("PASS" if records and passes == len(records) else "INCONCLUSIVE")
        final = {"status": status, "input": str(args.input), "media": metadata, "pass_frames": passes,
                 "fail_frames": failures, "inconclusive_frames": len(records)-passes-failures,
                 "frames": records, "limits": "Matching chart geometry only. Setup confirmation is operator evidence, not inferred. Sampled video frames do not prove an actual 360-degree phone rotation."}
    except (OSError, ValueError, cv2.error) as error:
        final = {"status": "INCONCLUSIVE", "error": str(error), "input": str(args.input)}
    (args.out/"report.json").write_text(json.dumps(final, indent=2)+"\n", encoding="utf-8")
    print(json.dumps({"status": final["status"], "report": str(args.out/"report.json")}))
    return 0 if final["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
