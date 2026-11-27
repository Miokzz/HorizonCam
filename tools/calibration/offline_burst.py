"""Register and fuse private, fixed-exposure GBRG RAW bursts on the host."""

import argparse
from dataclasses import dataclass
import json
import math
from pathlib import Path
import time

import cv2
import numpy as np
import rawpy
import tifffile

from compare_tele_raw import dng_tags, file_digest, orient_rgb, require, validate_manifest
from tele_isp_fixture import fixture_metrics, synthetic_fixture
from tele_raw_isp import (denoise_mosaic, malvar, measured_noise_scale, numeric, phases,
                          read_sensor, render, save_display)


PATTERN = "GBRG"
SATURATION = .98
MAX_SHIFT = 48  # Full-resolution photosites, per axis.
MIN_VARIANCE = 1e-10
OFFSETS = ((0, 0), (1.35, -2.45), (-3.6, 1.8), (2.65, .9),
           (-.75, -1.4), (4.2, 3.1), (-1.8, -3.9), (3.25, -.65))
SYNTHETIC_PROFILE = np.array([[.0009, .000009], [.0011, .000007], [.0014, .000010]], np.float32)


@dataclass
class Frame:
    source: Path
    sha256: str
    metadata: dict


@dataclass
class Burst:
    kind: str
    frames: list[Frame]
    reference: int
    noise_rgb: np.ndarray | None
    noise_scale_rgb: np.ndarray
    provenance: dict

    def read(self, index):
        frame = self.frames[index]
        if self.kind == "capture":
            sensor = read_sensor(frame.source, frame.metadata)
            sensor.optics.gain_tables()  # Also checks for unhandled mandatory opcodes.
            profile = profile_array(sensor.noise_profile * self.noise_scale_rgb)
            return sensor.mosaic, profile, sensor
        with frame.source.open("rb") as handle:
            mosaic = np.load(handle, allow_pickle=False)
        require(isinstance(mosaic, np.ndarray), "Expected a Bayer NPY array")
        return mosaic, profile_array(self.noise_rgb * self.noise_scale_rgb), None


@dataclass
class Fusion:
    mosaic: np.ndarray
    sigma: np.ndarray
    frames_used: np.ndarray
    evidence: list[dict]


def json_read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def json_write(path, payload):
    with path.open("x", encoding="utf-8") as handle:
        handle.write(json.dumps(payload, indent=2, allow_nan=False) + "\n")


def profile_array(value):
    profile = np.asarray(value, dtype=np.float32)
    require(profile.shape == (3, 2) and np.all(np.isfinite(profile) & (profile >= 0))
            and np.all(np.any(profile > 0, axis=1)), "Invalid RGB NoiseProfile")
    return profile


def capture_key(metadata):
    return (metadata["deviceModel"], metadata["firmware"], metadata["logicalCameraId"],
            metadata["physicalCameraId"], metadata["focalLengthMm"], metadata["iso"],
            metadata["exposureNs"], tuple(metadata["sensorCropRegion"] or ()),
            metadata.get("logicalZoomRatio"), metadata.get("physicalZoomRatio"),
            metadata["dng"]["width"], metadata["dng"]["height"], metadata["dng"]["orientation"])


def load_burst(path):
    data = json_read(path)
    require(isinstance(data, dict) and type(data.get("schemaVersion")) is int and data["schemaVersion"] == 1,
            "Expected burst manifest schemaVersion 1")
    kind = data.get("kind")
    require(kind in ("capture", "hdrplus-capture", "synthetic", "real-derived-simulation"),
            "Invalid burst provenance kind")
    require(data.get("cfa") == PATTERN, "Expected unrotated GBRG photosites")
    entries = data.get("frames")
    require(isinstance(entries, list) and 2 <= len(entries) <= 16, "Expected 2–16 burst frames")
    reference = data.get("reference")
    require(type(reference) is int and 0 <= reference < len(entries), "Invalid reference frame index")
    provenance = data.get("provenance", {})
    require(isinstance(provenance, dict), "Invalid burst provenance")
    scale = np.asarray(data.get("noiseVarianceScaleRgb", [1, 1, 1]), np.float32)
    require(scale.shape == (3,) and np.all(np.isfinite(scale) & (scale >= .1) & (scale <= 10)),
            "Invalid RGB noise variance scale")
    noise = None
    if kind == "capture":
        session = provenance.get("captureSessionId")
        require(isinstance(session, str) and bool(session.strip()), "Expected one declared captureSessionId")
        require("noiseProfileRgb" not in data, "Capture noise must come from each DNG")
    else:
        noise = profile_array(data.get("noiseProfileRgb"))

    frames = []
    for entry in entries:
        require(isinstance(entry, dict) and isinstance(entry.get("source"), str)
                and bool(entry["source"].strip()), "Expected a frame source path")
        source = (path.parent / entry["source"]).resolve()
        if kind == "capture":
            metadata = validate_manifest(source)
            require(metadata.get("sensorCropRegion") is not None, "Capture needs declared sensor crop")
            require(dng_tags(source / "source.dng", metadata)["cfaPattern"] == PATTERN, "Capture CFA is not GBRG")
            digest = metadata["dng"]["sha256"].lower()
        else:
            require(source.suffix.lower() == ".npy", "Expected a normalized Bayer NPY")
            digest = file_digest(source)
            require(digest == entry.get("sha256"), "Bayer NPY SHA-256 mismatch")
            metadata = {}
        frames.append(Frame(source, digest, metadata))

    require(len({f.sha256 for f in frames}) == len(frames), "Repeated RAW frame")
    if kind == "capture":
        metadata = [frame.metadata for frame in frames]
        require(len({m["pairId"] for m in metadata}) == len(frames), "Repeated capture pair ID")
        require(len({capture_key(m) for m in metadata}) == 1, "Capture settings or geometry changed")
        for key in ("sensorTimestampNs", "frameNumber"):
            require(all(a[key] < b[key] for a, b in zip(metadata, metadata[1:])),
                    f"Capture {key} must increase in manifest order")
        require(metadata[-1]["sensorTimestampNs"] - metadata[0]["sensorTimestampNs"] <= 2_000_000_000,
                "Declared capture spans over two seconds")
    return Burst(kind, frames, reference, noise, scale[:, None], provenance)


def validate_mosaic(mosaic, shape=None):
    require(mosaic.dtype == np.float32 and mosaic.ndim == 2 and min(mosaic.shape) >= 64
            and all(size % 2 == 0 and size < 32767 for size in mosaic.shape),
            "Expected an even, full phase-aligned float32 Bayer raster")
    require(shape is None or mosaic.shape == shape, "Burst raster shapes differ")
    require(np.isfinite(mosaic).all(), "Nonfinite Bayer samples")


def registration_image(mosaic):
    # These two greens have the same color and a fixed half-phase offset. Averaging
    # keeps their centroid at the packed pixel center in every frame.
    green = .5 * (mosaic[::2, ::2] + mosaic[1::2, 1::2])
    h, w = green.shape
    scale = min(1., 960 / max(h, w))
    if scale < 1:
        green = cv2.resize(green, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)
    low = cv2.GaussianBlur(green, (0, 0), .8)
    band = low - cv2.GaussianBlur(low, (0, 0), 4)
    return band, np.array([2 * w / green.shape[1], 2 * h / green.shape[0]])


def ecc_shift(reference, source, seed):
    matrix = np.array([[1, 0, seed[0]], [0, 1, seed[1]]], np.float32)
    criteria = (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 24, 1e-5)
    try:
        correlation, matrix = cv2.findTransformECC(reference, source, matrix, cv2.MOTION_TRANSLATION,
                                                   criteria, None, 5)
    except cv2.error:
        return 0., np.zeros(2)
    return correlation, matrix[:, 2].astype(np.float64)


def register(reference, source, units):
    """Return reference-to-source sampling displacement in full Bayer pixels."""
    h, w = reference.shape
    window = cv2.createHanningWindow((w, h), cv2.CV_32F)
    seed, response = cv2.phaseCorrelate(reference.copy(), source.copy(), window)
    record = {"phaseResponse": float(response) if math.isfinite(response) else None}
    if not math.isfinite(response) or response < .10 or not np.isfinite(seed).all():
        return dict(record, accepted=False, reason="weak phase correlation")
    if np.max(np.abs(seed * units)) > MAX_SHIFT:
        return dict(record, accepted=False, reason="phase shift exceeds model limit")

    # Overlapping tiles vote on the same translation. The global estimate can
    # follow a moving object, so use the median of agreeing local estimates.
    correlation, global_shift = ecc_shift(reference, source, seed)
    windows = [(slice(y, y + 3 * h // 5), slice(x, x + 3 * w // 5))
               for y in (0, h - 3 * h // 5) for x in (0, w - 3 * w // 5)]
    local = []
    for roi in windows:
        score, shift = ecc_shift(reference[roi], source[roi], seed)
        if score >= .78 and np.max(np.abs(shift * units)) <= MAX_SHIFT:
            local.append(shift * units)
    record.update(globalEcc=float(correlation) if math.isfinite(correlation) else None,
                  reliableTileCount=len(local), localDxDy=[shift.tolist() for shift in local])
    if len(local) < 2:
        return dict(record, accepted=False, reason="too few reliable translation tiles")
    local = np.asarray(local)
    clusters = np.linalg.norm(local[:, None, :] - local[None, :, :], axis=-1) <= .65
    consensus = clusters[np.argmax(clusters.sum(axis=1))]
    record["agreeingTileCount"] = int(np.count_nonzero(consensus))
    if record["agreeingTileCount"] < 2 or record["agreeingTileCount"] * 2 <= len(local):
        return dict(record, accepted=False, reason="inconsistent local translations")
    offset = np.median(local[consensus], axis=0)
    if record["agreeingTileCount"] == 2 and (not correlation >= .72
                                             or np.linalg.norm(global_shift * units - offset) > .9):
        return dict(record, accepted=False, reason="ambiguous two-tile translation")
    confidence = float(np.clip(response / .60, .25, 1))
    return dict(record, accepted=True, dxdy=offset.tolist(), confidence=confidence)


def bilinear_phase(plane, dx, dy):
    """Resample one Bayer phase. Return samples, full-tap validity and noise factor."""
    h, w = plane.shape
    x, y = math.floor(dx), math.floor(dy)
    fx, fy = dx - x, dy - y
    taps = [(j, i, wy * wx) for j, wy in ((y, 1 - fy), (y + 1, fy))
            for i, wx in ((x, 1 - fx), (x + 1, fx)) if wy * wx > 0]
    top, left = max(0, -min(j for j, _, _ in taps)), max(0, -min(i for _, i, _ in taps))
    bottom, right = min(h, h - max(j for j, _, _ in taps)), min(w, w - max(i for _, i, _ in taps))
    aligned = np.zeros(plane.shape, np.float32)
    valid = np.zeros(plane.shape, bool)
    if top < bottom and left < right:
        region = np.s_[top:bottom, left:right]
        valid[region] = True
        for j, i, weight in taps:
            sample = plane[top + j:bottom + j, left + i:right + i]
            aligned[region] += weight * sample
            valid[region] &= sample < SATURATION
    return aligned, valid, sum(weight ** 2 for _, _, weight in taps), (fx, fy)


def expected_variance(signal, profile):
    return np.maximum(profile[0] * signal + profile[1], MIN_VARIANCE)


def fuse(burst, reject_motion=True):
    start_ns = time.perf_counter_ns()
    reference, profile, reference_sensor = burst.read(burst.reference)
    validate_mosaic(reference)
    proxy, units = registration_image(reference)
    shape = reference.shape
    sites = list(phases())
    local_signal = np.empty(shape, np.float32)
    reference_var = np.empty(shape, np.float32)
    detail_x = np.empty(shape, np.float32)
    detail_y = np.empty(shape, np.float32)
    for _, _, channel, site in sites:
        smooth = cv2.GaussianBlur(reference[site], (3, 3), .60)
        local_signal[site] = np.maximum(cv2.boxFilter(reference[site], -1, (5, 5)), 0)
        reference_var[site] = expected_variance(local_signal[site], profile[channel])
        for target, x, y in ((detail_x, 1, 0), (detail_y, 0, 1)):
            derivative = cv2.Sobel(smooth, -1, x, y, ksize=3, scale=1 / 8)
            curvature = cv2.Sobel(smooth, -1, 2 * x, 2 * y, ksize=3, scale=1 / 4)
            energy = cv2.boxFilter(derivative ** 2 + curvature ** 2, -1, (3, 3))
            target[site] = np.maximum(energy - 1.3 * reference_var[site], 0)

    # Start at the reference and accumulate corrections. Sites with no alternate
    # samples consequently reproduce the reference exactly, including highlights.
    weight_sum = 1 / reference_var
    weight_squared = weight_sum.copy()  # Sum of squared weights times modeled variance.
    weight_energy = weight_sum ** 2     # Sum of squared weights for the effective sample count.
    correction = np.zeros(shape, np.float32)
    evidence = []
    saturated = reference >= SATURATION
    for index, frame in enumerate(burst.frames):
        record = {"index": index, "sha256": frame.sha256}
        if burst.kind == "capture":
            record.update({key: frame.metadata[key] for key in ("pairId", "sensorTimestampNs", "frameNumber")})
        evidence.append(record)
        if index == burst.reference:
            record.update(reference=True, accepted=True, dxdy=[0., 0.], confidence=1.,
                          noiseProfileRgb=profile.tolist())
            continue
        begin_ns = time.perf_counter_ns()
        source, candidate_profile, _ = burst.read(index)
        validate_mosaic(source, shape)
        record.update(register(proxy, registration_image(source)[0], units))
        record["noiseProfileRgb"] = candidate_profile.tolist()
        record["throughRegistrationMs"] = (time.perf_counter_ns() - begin_ns) / 1e6
        if not record["accepted"]:
            continue

        dx, dy = np.asarray(record["dxdy"]) / 2
        state = []
        excess = np.zeros((shape[0] // 2, shape[1] // 2), np.float32)
        for _, _, color, site in sites:
            aligned, valid, squared, (fx, fy) = bilinear_phase(source[site], dx, dy)
            valid &= ~saturated[site]
            noise = squared * expected_variance(local_signal[site], candidate_profile[color])
            # Large gradients magnify subpixel error and attenuation by bilinear
            # resampling. Suppress their weights when interpolation is large.
            model = noise + ((2.6 * fx * (1 - fx)) ** 2 + .07 ** 2) * detail_x[site]
            model += ((2.6 * fy * (1 - fy)) ** 2 + .07 ** 2) * detail_y[site]
            residual = aligned - reference[site]
            standardized = np.where(valid, residual ** 2 / (model + reference_var[site]), 0)
            if reject_motion:
                neighborhood = cv2.boxFilter(np.minimum(standardized, 64), -1, (3, 3))
                np.maximum(excess, neighborhood, out=excess)
            state.append((site, residual, valid, model, standardized))

        # Pool evidence across colors, with one packed pixel of protection around
        # moving regions. The reference remains the image at suppressed sites.
        if reject_motion:
            excess = cv2.dilate(excess, np.ones((3, 3), np.uint8))
        confidence = np.clip((9. - excess) / (9. - 3.6), 0, 1) ** 2
        hard_rejected = usable = 0
        confidence_total = 0.
        for site, residual, valid, variance, standardized in state:
            support = confidence * valid
            if reject_motion:
                support = support * (standardized < 25)
            weights = support * record["confidence"] / variance
            correction[site] += weights * residual
            weight_sum[site] += weights
            weight_squared[site] += weights ** 2 * variance
            weight_energy[site] += weights ** 2
            hard_rejected += int(np.count_nonzero(valid & (support == 0)))
            usable += int(np.count_nonzero(valid))
            confidence_total += float(support.sum(dtype=np.float64))
        record.update(validFraction=usable / reference.size,
                      motionRejectedFractionOfValid=hard_rejected / max(usable, 1),
                      meanMotionConfidenceOfValid=confidence_total / max(usable, 1),
                      throughFusionMs=(time.perf_counter_ns() - begin_ns) / 1e6)

    fused = reference + correction / weight_sum
    # Reuse the accumulator buffers after the last frame.
    np.sqrt(weight_squared, out=weight_squared)
    weight_squared /= weight_sum
    weight_squared[saturated] = np.nan
    np.square(weight_sum, out=weight_sum)
    weight_sum /= weight_energy
    return Fusion(fused, weight_squared, weight_sum, evidence), reference_sensor, (time.perf_counter_ns() - start_ns) / 1e6


def write_fusion(directory, burst, result, sensor, elapsed, display_ev, develop=None):
    np.save(directory / "fused-gbrg.npy", result.mosaic, allow_pickle=False)
    np.save(directory / "uncertainty-std.npy", result.sigma, allow_pickle=False)
    np.save(directory / "effective-frames.npy", result.frames_used, allow_pickle=False)
    if develop is not None:
        linear = develop(result.mosaic)
    elif sensor is not None:
        gained = result.mosaic * sensor.optics.gain_field(result.mosaic.shape)
        linear = orient_rgb(malvar(gained) @ sensor.color_matrix.T, sensor.orientation)
    else:
        linear = malvar(result.mosaic)
    display, clipping = render(linear, display_ev)
    save_display(directory / "fused-srgb.png", display)
    report = {"schemaVersion": 1, "kind": burst.kind, "provenance": burst.provenance, "cfa": PATTERN,
              "shape": list(result.mosaic.shape), "reference": burst.reference, "fusionMs": elapsed,
              "noiseVarianceScaleRgb": burst.noise_scale_rgb.flatten().tolist(),
              "displayEv": display_ev, "displayClipFraction": clipping, "frames": result.evidence,
              "effectiveFramesP10P50P90": np.percentile(result.frames_used, (10, 50, 90)).tolist(),
              "uncertaintyInvalidFraction": float(np.mean(~np.isfinite(result.sigma)))}
    json_write(directory / "burst-report.json", report)
    return report


def phase_crop_origin(shape, crop_shape, pattern):
    """Choose the crop nearest center that re-indexes Bayer into GBRG."""
    require(len(pattern) == 4 and pattern in ("GBRG", "GRBG", "RGGB", "BGGR"), "Unsupported Bayer pattern")
    rows = np.array(list(pattern)).reshape(2, 2)
    for y_shift in (0, 1):
        for x_shift in (0, 1):
            if "".join(np.roll(rows, (y_shift, x_shift), (0, 1)).flatten()) == PATTERN:
                y, x = ((size - part) // 4 * 2 + shift
                        for size, part, shift in zip(shape, crop_shape, (y_shift, x_shift)))
                require(0 <= y <= shape[0] - crop_shape[0] and 0 <= x <= shape[1] - crop_shape[1],
                        "Crop with a GBRG origin does not fit active area")
                return y, x
    raise ValueError("Cannot select a GBRG crop")


def read_hdrplus_dng(path, crop_shape, merged=False):
    # LibRaw decodes the lossless JPEG tiles; tifffile supplies the exact
    # fractional per-phase black levels that LibRaw rounds in its public API.
    with tifffile.TiffFile(path) as source:
        root = source.pages[0]
        page = root.pages[0] if merged else root
        require(page.photometric == 32803 and page.dtype == np.uint16
                and tuple(page.tags["CFARepeatPatternDim"].value) == (2, 2)
                and tuple(page.tags["CFAPlaneColor"].value) == (0, 1, 2)
                and page.tags["CFALayout"].value == 1, "Unsupported HDR+ DNG sensor layout")
        require(not any(name in page.tags for name in ("LinearizationTable", "BlackLevelDeltaH", "BlackLevelDeltaV",
                                                       "OpcodeList1")), "Unsupported HDR+ RAW corrections")
        require(tuple(page.tags["BlackLevelRepeatDim"].value) == (2, 2), "Expected per-phase HDR+ DNG black")
        pattern = "".join("RGB"[color] for color in page.tags["CFAPattern"].value)
        active = page.tags.get("ActiveArea")
        top, left, bottom, right = active.value if active else (0, 0, *page.shape)
        require(top % 2 == left % 2 == 0, "Expected HDR+ active origin with no Bayer phase shift")
        shape = (bottom - top, right - left)
        y, x = phase_crop_origin(shape, crop_shape, pattern)
        black = numeric(page, "BlackLevel", 4).reshape(2, 2)
        white = numeric(page, "WhiteLevel", 1)[0]
        require(np.all(white > black), "Invalid HDR+ DNG black or white levels")
        neutral = numeric(root, "AsShotNeutral", 3)
        require(np.all(neutral > 0), "Invalid HDR+ white balance")
        exif = root.tags["ExifTag"].value
        metadata = {"camera": root.tags["UniqueCameraModel"].value, "orientation": int(root.tags["Orientation"].value),
                    "iso": exif["ISOSpeedRatings"], "exposureSeconds": exif["ExposureTime"][0] / exif["ExposureTime"][1],
                    "sourceShape": list(shape), "sourceCfa": pattern, "cropXYWH": [x, y, crop_shape[1], crop_shape[0]],
                    "asShotNeutral": neutral.tolist()}
        profile = None if merged else profile_array(numeric(root, "NoiseProfile", 6).reshape(3, 2))

    with rawpy.imread(str(path)) as raw:
        require(raw.raw_type == rawpy.RawType.Flat and raw.raw_image_visible.shape == shape
                and (raw.sizes.top_margin, raw.sizes.left_margin) == (top, left)
                and "".join(chr(raw.color_desc[index]) for index in raw.raw_pattern.ravel()) == pattern,
                "HDR+ DNG metadata and LibRaw visible geometry differ")
        h, w = crop_shape
        crop = raw.raw_image_visible[y:y + h, x:x + w].astype(np.float32)
    for row, column, _, site in phases():
        phase_black = black[(row + y) % 2, (column + x) % 2]
        crop[site] = (crop[site] - phase_black) / (white - phase_black)
    return crop, profile, metadata


def hdrplus_developer(provenance):
    color = np.asarray(provenance.get("colorMatrix"), np.float32)
    require(color.shape == (3, 3) and np.isfinite(color).all(), "Invalid HDR+ color matrix")
    orientation = provenance.get("orientation")
    require(type(orientation) is int and 1 <= orientation <= 8, "Invalid HDR+ display orientation")

    def develop(mosaic):
        return orient_rgb(malvar(mosaic) @ color.T, orientation)

    return develop


def hdrplus(args):
    reference = int((args.inputs / "reference_frame.txt").read_text(encoding="utf-8").strip())
    files = sorted(args.inputs.glob("payload_N*.dng"))
    require(2 <= len(files) <= 16 and 0 <= reference < len(files), "Expected complete HDR+ payload set")
    require([p.name for p in files] == [f"payload_N{i:03}.dng" for i in range(len(files))],
            "HDR+ payload frame sequence is incomplete")
    require(all(size >= 64 and size % 2 == 0 for size in args.crop_size), "Expected even HDR+ crop dimensions ≥ 64")
    crop_shape = tuple(reversed(args.crop_size))
    merged_path = args.inputs / "merged.dng"
    merged, _, merged_meta = read_hdrplus_dng(merged_path, crop_shape, merged=True)
    transform_path = args.inputs / "rgb2rgb.txt"
    transform = np.loadtxt(transform_path, dtype=np.float32).reshape(3, 3)
    args.out.mkdir(parents=True, exist_ok=False)
    entries, original_sha256 = [], {}
    for index, path in enumerate(files):
        mosaic, profile, metadata = read_hdrplus_dng(path, crop_shape)
        if index == 0:
            first_metadata, first_profile = metadata, profile
        require(metadata == first_metadata and np.array_equal(profile, first_profile),
                "HDR+ payload settings, geometry or NoiseProfile changed")
        original_sha256[path.name] = file_digest(path)
        name = f"frame-{index:02}.npy"
        np.save(args.out / name, mosaic, allow_pickle=False)
        entries.append({"source": name, "sha256": file_digest(args.out / name)})
    require(all(merged_meta[key] == first_metadata[key]
                for key in ("camera", "sourceShape", "sourceCfa", "cropXYWH", "orientation")),
            "HDR+ merged DNG does not share reference geometry")
    original_sha256.update({name: file_digest(args.inputs / name)
                            for name in ("reference_frame.txt", "merged.dng", "rgb2rgb.txt")})
    provenance = dict(first_metadata, dataset="HDR+ Burst Photography Dataset", datasetPath=args.dataset_path,
                      publishedResultVersion=args.result_version, originalSha256=original_sha256,
                      colorMatrix=(transform / np.asarray(first_metadata["asShotNeutral"])[None, :]).tolist())
    manifest = args.out / "manifest.json"
    json_write(manifest, {"schemaVersion": 1, "kind": "hdrplus-capture", "cfa": PATTERN, "reference": reference,
                          "noiseProfileRgb": first_profile.tolist(), "frames": entries, "provenance": provenance})
    burst = load_burst(manifest)
    fused, _, elapsed = fuse(burst)
    control, _, _ = fuse(burst, reject_motion=False)
    develop = hdrplus_developer(provenance)
    report = write_fusion(args.out, burst, fused, None, elapsed, args.display_ev, develop)
    anchor, _, _ = burst.read(reference)
    for label, raw in (("reference", anchor), ("ungated", control.mosaic), ("published-merge", merged)):
        save_display(args.out / f"{label}-srgb.png", render(develop(raw), args.display_ev)[0])
    compare = np.s_[24:-24, 24:-24]
    geometry = {}
    ref_proxy, units = registration_image(anchor)
    for row in fused.evidence:
        index = row["index"]
        if index != reference and row["accepted"]:
            source_proxy, _ = registration_image(burst.read(index)[0])
            reverse = register(source_proxy, ref_proxy, units)
            geometry[index] = (float(np.linalg.norm(np.add(row["dxdy"], reverse["dxdy"])))
                               if reverse["accepted"] else None)
    json_write(args.out / "benchmark.json",
               {"kind": burst.kind, "provenance": provenance, "fusionMs": report["fusionMs"],
                "forwardReverseCycleErrorPhotosites": geometry, "comparisonMarginPhotosites": 24,
                "rmseVsPublishedMerge": {name: rms((raw - merged)[compare])
                                         for name, raw in (("reference", anchor), ("motionGated", fused.mosaic),
                                                           ("ungated", control.mosaic))}})


def mosaicked(rgb):
    mosaic = np.empty(rgb.shape[:2], np.float32)
    for _, _, color, site in phases():
        mosaic[site] = rgb[site + (color,)]
    return mosaic


def simulated_frame(scene, dxdy, profile, rng):
    h, w = scene.shape[:2]
    dx, dy = dxdy
    shift = np.array([[1, 0, dx], [0, 1, dy]], np.float32)
    shifted = cv2.warpAffine(scene, shift, (w, h), flags=cv2.INTER_LANCZOS4,
                             borderMode=cv2.BORDER_REFLECT_101)
    raw = mosaicked(shifted)
    for _, _, color, site in phases():
        noise = expected_variance(np.maximum(raw[site], 0), profile[color])
        raw[site] += rng.standard_normal(noise.shape, dtype=np.float32) * np.sqrt(noise)
    return np.minimum(raw, 1.)


def simulation_scene(pair, calibration, seed, source_origin):
    if pair is None:
        require(calibration is None and source_origin is None, "Source options need a source pair")
        truth, _, regions = synthetic_fixture(seed, 0, 0)
        return truth, SYNTHETIC_PROFILE, regions, {}
    metadata = validate_manifest(pair)
    sensor = read_sensor(pair, metadata)
    profile = sensor.noise_profile
    provenance = {"sourcePairId": metadata["pairId"], "sourceDngSha256": metadata["dng"]["sha256"]}
    if calibration:
        scale = measured_noise_scale(json_read(calibration))
        profile = profile * scale
        provenance.update(flatCalibrationSha256=file_digest(calibration), sourceNoiseScaleRgb=scale.flatten().tolist())
    h, w = sensor.mosaic.shape
    x, y = source_origin or (((w - 576) // 4) * 2, ((h - 384) // 4) * 2)
    require(0 <= x <= w - 576 and 0 <= y <= h - 384 and x % 2 == y % 2 == 0,
            "Source crop needs an even origin and must fit the active area")
    crop = denoise_mosaic(sensor.mosaic[y:y + 384, x:x + 576], profile)
    provenance["cropXYWH"] = [x, y, 576, 384]
    return malvar(crop), profile, None, provenance


def create_simulation(directory, seed, pair=None, calibration=None, source_origin=None):
    scene, profile, regions, provenance = simulation_scene(pair, calibration, seed, source_origin)
    h, w = scene.shape[:2]
    entering = np.s_[h // 2 - 47:h // 2 + 33, w // 2 + 40:w // 2 + 130]
    leaving = np.s_[h // 2 + 58:h // 2 + 109, w // 2 - 113:w // 2 - 37]
    truth = scene.copy()
    truth[leaving] = (.72, .17, .57)
    rng = np.random.default_rng(seed)
    frames = []
    for i, dxdy in enumerate(OFFSETS):
        current = truth if i == 0 else scene.copy()
        if i:
            current[entering] = (.09, .82, .66)
        raw = simulated_frame(current, dxdy, profile, rng)
        name = f"frame-{i:02}.npy"
        path = directory / name
        np.save(path, raw, allow_pickle=False)
        frames.append({"source": name, "sha256": file_digest(path)})
    manifest = directory / "manifest.json"
    kind = "synthetic" if pair is None else "real-derived-simulation"
    json_write(manifest, {"schemaVersion": 1, "kind": kind, "cfa": PATTERN, "reference": 0,
                          "noiseProfileRgb": profile.tolist(), "frames": frames,
                          "provenance": dict(provenance, seed=seed)})
    return manifest, truth, regions, (entering, leaving)


def rms(values):
    return float(np.sqrt(np.mean(values ** 2, dtype=np.float64)))


def simulation_metrics(reference, truth, regions, moving, fused, control):
    h, w = reference.shape
    target = mosaicked(truth)
    static = np.ones((h, w), bool)
    static[:10] = static[-10:] = False
    static[:, :10] = static[:, -10:] = False
    for ys, xs in moving:
        static[ys.start - 8:ys.stop + 8, xs.start - 8:xs.stop + 8] = False
    # Avoid quoting the synthetic sensor variance for clipped observations.
    calibrated = static & (reference < .9) & (target < .85)
    scores = {}
    for name, raw in (("reference", reference), ("motionGated", fused.mosaic), ("ungated", control.mosaic)):
        err = raw - target
        scores[name] = {"rawStaticRmse": rms(err[static]),
                        "rawMovingEnterRmse": rms(err[moving[0]]), "rawMovingLeaveRmse": rms(err[moving[1]])}
        if regions:
            # Some ISP patches intersect the occluders. Synthetic motion and
            # texture scores use only ROI regions clear of the occluders.
            clear = dict(regions)
            clear["texture"] = np.s_[137:194, 300:326]
            clear["lines"] = np.s_[137:215, 156:170]
            clear["color_green"] = np.s_[316:373, 154:278]
            scores[name]["isp"] = fixture_metrics(malvar(raw), truth, clear)
    scores["uncertainty"] = {"empiricalStaticRmse": rms((fused.mosaic - target)[calibrated]),
                             "predictedStaticRmsStd": rms(fused.sigma[calibrated]),
                             "staticEffectiveFramesMedian": float(np.median(fused.frames_used[calibrated])),
                             "movingEffectiveFramesMedian": [float(np.median(fused.frames_used[roi])) for roi in moving]}
    if regions:
        flat = regions["uniform_mid"]
        scores["uncertainty"].update(empiricalMidFlatRmse=rms((fused.mosaic - target)[flat]),
                                     predictedMidFlatRmsStd=rms(fused.sigma[flat]))
    return scores


def benchmark(args):
    args.out.mkdir(parents=True, exist_ok=False)
    manifest, truth, regions, moving = create_simulation(
        args.out, args.seed, args.source_pair, args.flat_calibration, args.source_origin)
    burst = load_burst(manifest)
    fused, _, elapsed = fuse(burst)
    control, _, _ = fuse(burst, reject_motion=False)
    report = write_fusion(args.out, burst, fused, None, elapsed, args.display_ev)
    reference, _, _ = burst.read(burst.reference)
    save_display(args.out / "reference-srgb.png", render(malvar(reference), args.display_ev)[0])
    save_display(args.out / "ungated-srgb.png", render(malvar(control.mosaic), args.display_ev)[0])
    errors = [float(np.linalg.norm(row["dxdy"] - np.array(offset))) if row["accepted"] else None
              for row, offset in zip(fused.evidence, OFFSETS)]
    json_write(args.out / "benchmark.json",
               {"kind": burst.kind, "provenance": burst.provenance, "expectedDxDy": OFFSETS,
                "registrationErrorPhotosites": errors, "fusionMs": report["fusionMs"],
                "metrics": simulation_metrics(reference, truth, regions, moving, fused, control)})


def run(args):
    burst = load_burst(args.manifest)
    result, sensor, elapsed = fuse(burst)
    args.out.mkdir(parents=True, exist_ok=False)
    develop = hdrplus_developer(burst.provenance) if burst.kind == "hdrplus-capture" else None
    write_fusion(args.out, burst, result, sensor, elapsed, args.display_ev, develop)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    fusion = commands.add_parser("fuse", help="import a burst manifest, register and fuse frames")
    fusion.add_argument("manifest", type=Path)
    fusion.set_defaults(action=run)
    bench = commands.add_parser("benchmark", help="make and score a controlled burst")
    bench.add_argument("--seed", type=int, default=20261126)
    bench.add_argument("--source-pair", type=Path, help="derive a simulation from one validated RAW/JPEG pair")
    bench.add_argument("--source-origin", nargs=2, type=int, metavar=("X", "Y"),
                       help="override the simulation crop origin in unrotated DNG photosites")
    bench.add_argument("--flat-calibration", type=Path, help="existing flat-field noise calibration report")
    bench.set_defaults(action=benchmark)
    public = commands.add_parser("hdrplus", help="adapt and score a captured HDR+ dataset burst")
    public.add_argument("inputs", type=Path, help="downloaded payloads and published result files")
    public.add_argument("--dataset-path", required=True, help="source burst prefix within the HDR+ bucket")
    public.add_argument("--result-version", default="20171023")
    public.add_argument("--crop-size", nargs=2, type=int, metavar=("WIDTH", "HEIGHT"), default=(1024, 768))
    public.set_defaults(action=hdrplus)
    for command in (fusion, bench, public):
        command.add_argument("--out", type=Path, required=True)
        command.add_argument("--display-ev", type=float, default=0.)
    args = parser.parse_args()
    if not math.isfinite(args.display_ev) or not -12 <= args.display_ev <= 12:
        parser.error("display EV must be finite in [-12, 12]")
    if args.command == "benchmark" and args.seed < 0:
        parser.error("benchmark seed must be nonnegative")
    try:
        args.action(args)
    except (ValueError, OSError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
