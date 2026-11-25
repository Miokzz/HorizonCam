"""Validate and render a private Camera2 RAW/JPEG pair without touching the originals.

Host tools: Python 3.12, rawpy 0.28.0, NumPy 2.5.5, tifffile 2026.11.16,
Pillow 13.0.0 and OpenCV 5.1.0. Neither output nor inputs belong in Git.
"""

import argparse
import hashlib
import io
import json
import math
import re
from pathlib import Path

import cv2
import numpy as np
import rawpy
import tifffile
from PIL import Image, ImageCms, ImageOps, __version__ as pillow_version

from compare_lens_transition import corresponding_points


def require(condition, message):
    if not condition:
        raise ValueError(message)


def positive_int(obj, key, allow_zero=False):
    value = obj.get(key)
    require(type(value) is int and value >= (0 if allow_zero else 1), f"Invalid {key}")
    return value


def number(obj, key, optional=False):
    value = obj.get(key)
    if value is None and optional:
        return None
    require(type(value) in (float, int) and math.isfinite(value) and value > 0, f"Invalid {key}")
    return value


def file_digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def validate_manifest(pair_dir):
    manifest = json.loads((pair_dir / "manifest.json").read_text(encoding="utf-8"))
    require(isinstance(manifest, dict), "Manifest must be an object")
    require(positive_int(manifest, "schemaVersion") == 1, "Unsupported manifest version")
    for key in ("pairId", "deviceModel", "firmware"):
        require(isinstance(manifest.get(key), str) and bool(manifest[key].strip()), f"Missing {key}")
    require(re.fullmatch(r"[A-Za-z0-9._-]{1,128}", manifest["pairId"]) is not None, "Invalid pairId")
    require(manifest.get("logicalCameraId") == "0" and manifest.get("physicalCameraId") == "6", "Expected tele 0/6")
    require(abs(number(manifest, "focalLengthMm") - 7.0) <= 0.10, "Expected a 7 mm tele result")
    require(number(manifest, "nominalOpticalZoom") == 3, "Expected nominal 3x tele")
    require(number(manifest, "digitalZoomRequested") == 1, "Expected no requested digital zoom")
    for key in ("logicalZoomRatio", "physicalZoomRatio"):
        number(manifest, key, optional=True)
    for key in ("iso", "exposureNs", "sensorTimestampNs"):
        positive_int(manifest, key)
    positive_int(manifest, "frameNumber", allow_zero=True)
    require(type(manifest.get("previewEnabled")) is bool, "Invalid previewEnabled")
    crop = manifest.get("sensorCropRegion")
    if crop is not None:
        require(isinstance(crop, list) and len(crop) == 4 and all(type(x) is int and x >= 0 for x in crop),
                "Invalid sensorCropRegion")
        require(crop[0] < crop[2] and crop[1] < crop[3], "Empty sensorCropRegion")
    for kind, filename in (("dng", "source.dng"), ("jpeg", "source.jpg")):
        entry = manifest.get(kind)
        require(isinstance(entry, dict) and entry.get("filename") == filename, f"Invalid {kind} filename")
        for key in ("width", "height", "bytes"):
            positive_int(entry, key)
        require(1 <= positive_int(entry, "orientation") <= 8, f"Invalid {kind} orientation")
        require(positive_int(entry, "timestampNs") == manifest["sensorTimestampNs"], f"{kind} timestamp mismatch")
        checksum = entry.get("sha256")
        require(isinstance(checksum, str) and re.fullmatch(r"[a-fA-F0-9]{64}", checksum), f"Invalid {kind} SHA-256")
        path = pair_dir / filename
        require(path.is_file() and not path.is_symlink(), f"Missing or indirect {kind} source")
        require(path.stat().st_size == entry["bytes"], f"{kind} byte count mismatch")
        require(file_digest(path) == checksum.lower(), f"{kind} SHA-256 mismatch")
    return manifest


def assert_image_geometry(entry, width, height, orientation, kind):
    require((width, height) == (entry["width"], entry["height"]), f"{kind} stored dimensions mismatch")
    require(orientation == entry["orientation"], f"{kind} orientation mismatch")


def as_float(value):
    if value is None:
        return None
    result = value[0] / value[1] if isinstance(value, tuple) else float(value)
    require(math.isfinite(result) and result > 0, "Invalid photographic metadata value")
    return result


def check_photo_metadata(manifest, focal, exposure, iso, kind):
    metadata = {"focalLengthMm": as_float(focal), "exposureSeconds": as_float(exposure), "iso": as_float(iso)}
    for key, expected, tolerance in (("focalLengthMm", manifest["focalLengthMm"], 0.05),
                                      ("exposureSeconds", manifest["exposureNs"] / 1e9, 0.0001),
                                      ("iso", manifest["iso"], 1)):
        value = metadata[key]
        if value is not None:
            require(abs(value - expected) <= tolerance, f"{kind} {key} mismatch")
    return metadata


def dng_tags(path, manifest):
    with tifffile.TiffFile(path) as source:
        root = source.pages[0]

        def walk(pages):
            for page in pages:
                if page.photometric == 32803:  # TIFF/EP CFA
                    yield page
                if page.pages is not None:
                    yield from walk(page.pages)

        matches = list(walk(source.pages))
        require(50706 in root.tags and len(matches) == 1, "Expected DNG version and one mosaic CFA IFD")
        page = matches[0]

        def tag(code, default=None):
            field = page.tags.get(code) or root.tags.get(code)
            return field.value if field is not None else default

        orientation = int(tag(274, 1))
        assert_image_geometry(manifest["dng"], page.imagewidth, page.imagelength, orientation, "DNG")
        pattern = tag(33422, ())
        require(tuple(tag(33421, ())) == (2, 2) and sorted(pattern) == [0, 1, 1, 2]
                and page.samplesperpixel == 1, "Expected an RGB Bayer CFA, one sample per pixel")
        photo_exif = tag(34665, {})

        def photo_tag(code, name):
            return tag(code, photo_exif.get(name))

        result = check_photo_metadata(manifest, photo_tag(37386, "FocalLength"),
                                      photo_tag(33434, "ExposureTime"), photo_tag(34855, "ISOSpeedRatings"), "DNG")
        result.update(dngVersion=list(tag(50706)), cfaPattern="".join("RGB"[value] for value in pattern),
                      bitsPerStoredSample=page.bitspersample, orientation=orientation)
        return result


def orient_rgb(array, orientation):
    if orientation == 1:
        return array
    if orientation == 2:
        return array[:, ::-1]
    if orientation == 3:
        return array[::-1, ::-1]
    if orientation == 4:
        return array[::-1]
    if orientation == 5:
        return array.transpose(1, 0, 2)
    if orientation == 6:
        return np.rot90(array, -1)
    if orientation == 7:
        return array.transpose(1, 0, 2)[::-1, ::-1]
    if orientation == 8:
        return np.rot90(array, 1)
    raise ValueError("Invalid EXIF orientation")


def develop_raw(path, manifest, demosaic=rawpy.DemosaicAlgorithm.AHD):
    metadata = dng_tags(path, manifest)
    with rawpy.imread(str(path)) as raw:
        visible = raw.raw_image_visible
        require(raw.raw_type == rawpy.RawType.Flat and visible.ndim == 2 and visible.dtype == np.uint16,
                "LibRaw did not unpack a 16-bit Bayer mosaic")
        width, height = manifest["dng"]["width"], manifest["dng"]["height"]
        require((raw.sizes.raw_width, raw.sizes.raw_height) == (width, height), "LibRaw mosaic dimensions mismatch")
        pattern = "".join(chr(raw.color_desc[index]) for index in raw.raw_pattern.ravel())
        # Decoder CFA origins can differ when a raw image has sensor margins. Report both.
        require(sorted(pattern) == ["B", "G", "G", "R"], "Unexpected LibRaw color pattern")
        wb = list(raw.camera_whitebalance)
        require(len(wb) in (3, 4) and all(math.isfinite(x) and x > 0 for x in wb[:3]), "As-shot WB unavailable")
        metadata.update(rawpyPattern=pattern, blackLevels=list(raw.black_level_per_channel), whiteLevel=raw.white_level,
                        cameraWhiteBalance=wb, activeWidth=visible.shape[1], activeHeight=visible.shape[0],
                        defaultCrop=[raw.sizes.crop_left_margin, raw.sizes.crop_top_margin,
                                     raw.sizes.crop_width, raw.sizes.crop_height])
        linear = raw.postprocess(demosaic_algorithm=demosaic, half_size=False,
                                 output_color=rawpy.ColorSpace.sRGB, output_bps=16, gamma=(1, 1), user_flip=0,
                                 use_camera_wb=True, use_auto_wb=False, no_auto_bright=True, bright=1.0,
                                 adjust_maximum_thr=0.0, highlight_mode=rawpy.HighlightMode.Clip,
                                 fbdd_noise_reduction=rawpy.FBDDNoiseReductionMode.Off, noise_thr=None,
                                 median_filter_passes=0)
        require(linear.dtype == np.uint16 and linear.ndim == 3 and linear.shape[2] == 3,
                "Unexpected Linear RAW output format")
    return orient_rgb(linear, metadata["orientation"]), metadata


def srgb_profile():
    return ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB"))


def decode_jpeg(path, manifest):
    with Image.open(path) as source:
        require(source.format == "JPEG", "Expected a JPEG source")
        tags = source.getexif()
        orientation = int(tags.get(274, 1))
        assert_image_geometry(manifest["jpeg"], *source.size, orientation, "JPEG")
        exif = tags.get_ifd(34665)
        metadata = check_photo_metadata(manifest, exif.get(37386), exif.get(33434), exif.get(34855), "JPEG")
        image = ImageOps.exif_transpose(source).convert("RGB")
        profile = source.info.get("icc_profile")
        metadata.update(orientation=orientation, exifColorSpace=exif.get(40961),
                        colorManagement="embedded ICC → sRGB" if profile else
                        "EXIF sRGB" if exif.get(40961) == 1 else "sRGB assumed (no profile)")
        if profile:
            image = ImageCms.profileToProfile(image, ImageCms.ImageCmsProfile(io.BytesIO(profile)), srgb_profile())
        else:
            require(exif.get(40961) in (None, 1, 65535), "JPEG color space requires an ICC profile")
        return np.array(image), metadata


def display_srgb(linear, exposure_ev):
    gain = 2 ** exposure_ev
    display = np.empty(linear.shape, dtype=np.uint8)
    clipped_count = 0
    for row in range(0, linear.shape[0], 128):
        block = linear[row:row + 128].astype(np.float32) * (gain / 65_535)
        clipped_count += np.count_nonzero(np.max(block, axis=2) >= 1)
        block = np.minimum(block, 1)
        encoded = np.where(block <= 0.0031308, 12.92 * block, 1.055 * block ** (1 / 2.4) - 0.055)
        display[row:row + 128] = np.rint(encoded * 255).astype(np.uint8)
    return display, clipped_count / (linear.shape[0] * linear.shape[1])


def work_grid(rgb, long_edge=1280):
    height, width = rgb.shape[:2]
    ratio = min(1.0, long_edge / max(width, height))
    return cv2.resize(rgb, (round(ratio * width), round(ratio * height)), interpolation=cv2.INTER_AREA)


def gray_contrast_for_matching(image):
    gray = cv2.cvtColor(image, cv2.COLOR_RGB2GRAY)
    low, high = np.percentile(gray, [1, 99])
    if high > low:
        gray = np.clip((gray.astype(np.float32) - low) / (high - low) * 255, 0, 255).astype(np.uint8)
    return cv2.cvtColor(gray, cv2.COLOR_GRAY2RGB)


def align_pair(raw, jpeg):
    try:
        source, target = corresponding_points(gray_contrast_for_matching(raw), gray_contrast_for_matching(jpeg))
    except ValueError as error:
        return None, {"status": "inconclusive", "reason": str(error)}
    order = np.random.default_rng(6).permutation(len(source))
    train, heldout = np.split(order, [len(order) * 2 // 3])
    cv2.setRNGSeed(6)
    matrix, inliers = cv2.estimateAffinePartial2D(source[train], target[train], method=cv2.RANSAC,
                                                 ransacReprojThreshold=2.5)
    if matrix is None or np.count_nonzero(inliers) < 30:
        return None, {"status": "inconclusive", "reason": "Insufficient fitting inliers", "matches": len(source)}
    estimates = cv2.transform(source[heldout, None], matrix)[:, 0]
    distances = np.linalg.norm(estimates - target[heldout], axis=1)
    coherent = distances <= 3
    stats = {"matches": len(source), "heldout": len(heldout), "heldoutInlierFraction": float(coherent.mean())}
    if np.count_nonzero(coherent) < 16 or coherent.mean() < 0.60:
        return None, {"status": "inconclusive", "reason": "Insufficient heldout agreement", **stats}
    coverage = cv2.contourArea(cv2.convexHull(target[heldout][coherent])) / (jpeg.shape[0] * jpeg.shape[1])
    stats.update(heldoutCoverage=coverage, heldoutMedianPx=float(np.median(distances[coherent])),
                 heldoutP95Px=float(np.percentile(distances[coherent], 95)), rawToJpegMatrix=matrix.tolist())
    if coverage < 0.06:
        return None, {"status": "inconclusive", "reason": "Heldout matches cover too little of the frame", **stats}
    return matrix, {"status": "aligned", **stats}


def gradients(gray):
    return cv2.magnitude(cv2.Sobel(gray, cv2.CV_32F, 1, 0, scale=0.125),
                         cv2.Sobel(gray, cv2.CV_32F, 0, 1, scale=0.125))


def describe_pixels(raw, jpeg):
    raw, jpeg = work_grid(raw), work_grid(jpeg)
    matrix, alignment = align_pair(raw, jpeg)
    height, width = jpeg.shape[:2]
    alignment.update(jpegAnalysisSize=[width, height], rawAnalysisSize=[raw.shape[1], raw.shape[0]])
    if matrix is None:
        return {"alignment": alignment, "metrics": None}
    reference = jpeg.astype(np.float32) / 255
    warped = cv2.warpAffine(raw.astype(np.float32) / 255, matrix, (width, height), flags=cv2.INTER_LINEAR)
    mask = cv2.warpAffine(np.ones(raw.shape[:2], np.uint8), matrix, (width, height), flags=cv2.INTER_NEAREST)
    mask = cv2.erode(mask, np.ones((9, 9), dtype=np.uint8), borderType=cv2.BORDER_CONSTANT, borderValue=0) > 0
    alignment["overlapFraction"] = float(mask.mean())
    valid = mask & (reference.max(axis=2) < 0.98) & (warped.max(axis=2) < 0.98)
    valid &= (reference.max(axis=2) > 0.04) & (warped.max(axis=2) > 0.04)
    if np.count_nonzero(valid) < 1_024:
        return {"alignment": alignment, "metrics": None, "reason": "Insufficient shared unclipped pixels"}
    result = {"sharedUnclippedPixels": int(np.count_nonzero(valid))}
    grays = [cv2.cvtColor(pixels, cv2.COLOR_RGB2GRAY) for pixels in (warped, reference)]
    blurred = [cv2.GaussianBlur(gray, (0, 0), 1.5) for gray in grays]
    flat = valid & (gradients(blurred[0]) < 0.006) & (gradients(blurred[1]) < 0.006)
    gradient_maps = [gradients(gray) for gray in grays]
    threshold = max(0.025, float(np.percentile(gradient_maps[1][valid], 90)))
    edge = valid & (gradient_maps[1] >= threshold)
    result.update(flatSamples=int(np.count_nonzero(flat)), jpegSelectedEdgeSamples=int(np.count_nonzero(edge)),
                  jpegEdgeThreshold255=threshold * 255)
    for label, gray, low, gradient in zip(("raw", "jpeg"), grays, blurred, gradient_maps):
        p05, median, p95 = np.percentile(gray[valid], (5, 50, 95)) * 255
        noise_proxy = None
        if result["flatSamples"] >= 128:
            residual = (gray - low)[flat]
            noise_proxy = float(1.4826 * np.median(np.abs(residual - np.median(residual))) * 255)
        result[label] = {"displayLumaMedian255": float(median), "displayContrastP95MinusP05_255": float(p95 - p05),
                         "flatHighFrequencyMadSigma255": noise_proxy,
                         "jpegSelectedEdgeGradientMedian255": float(np.median(gradient[edge]) * 255)
                         if result["jpegSelectedEdgeSamples"] >= 128 else None}
    labs = [cv2.cvtColor(pixels, cv2.COLOR_RGB2LAB)[valid] for pixels in (warped, reference)]
    differences = labs[0] - labs[1]
    color_distance = np.linalg.norm(differences, axis=1)
    result.update(deltaE76Median=float(np.median(color_distance)), deltaE76P95=float(np.percentile(color_distance, 95)),
                  deltaLStarMedian=float(np.median(differences[:, 0])),
                  deltaABMedian=float(np.median(np.linalg.norm(differences[:, 1:], axis=1))))
    return {"alignment": alignment, "metrics": result}


def compare_pair(pair_dir, output=None, exposure_ev=0.0):
    require(math.isfinite(exposure_ev) and -8 <= exposure_ev <= 8, "Exposure must be between -8 and +8 EV")
    output = output if output is not None else pair_dir / "comparison"
    require(not output.exists() and not output.is_symlink(), "Output already exists; choose a new directory")
    manifest = validate_manifest(pair_dir)
    linear, raw_metadata = develop_raw(pair_dir / "source.dng", manifest)
    jpeg, jpeg_metadata = decode_jpeg(pair_dir / "source.jpg", manifest)
    display, raw_clipped = display_srgb(linear, exposure_ev)
    result = describe_pixels(display, jpeg)
    originals = {key: {item: manifest[key][item] for item in
                       ("filename", "width", "height", "timestampNs", "orientation", "bytes", "sha256")}
                 for key in ("dng", "jpeg")}
    source_fields = ("pairId", "deviceModel", "firmware", "logicalCameraId", "physicalCameraId", "focalLengthMm",
                     "iso", "exposureNs", "sensorTimestampNs", "frameNumber", "nominalOpticalZoom",
                     "digitalZoomRequested", "logicalZoomRatio", "physicalZoomRatio", "sensorCropRegion", "previewEnabled")
    report = {"schemaVersion": 1, "input": {key: manifest.get(key) for key in source_fields} | originals,
              "timestampEvidence": "Camera2 manifest equality; sensor clock is not independently available in the files",
              "rawMetadata": raw_metadata, "jpegMetadata": jpeg_metadata,
              "versions": {"rawpy": rawpy.__version__, "libraw": list(rawpy.libraw_version), "numpy": np.__version__,
                           "tifffile": tifffile.__version__, "opencv": cv2.__version__, "pillow": pillow_version},
              "rawRecipe": {"demosaic": "AHD", "whiteBalance": "as-shot", "output": "linear sRGB, TIFF UInt16",
                            "autoBrightness": False, "sharpening": False, "denoise": False,
                            "highlightRecovery": False, "displayExposureEv": exposure_ev,
                            "displayLinearGain": 2 ** exposure_ev, "displayChannelClipFraction": raw_clipped,
                            "toneMapping": "none; gain, clip, IEC sRGB transfer"},
              "analysisLimits": ["No clean ground truth: scores describe differences between two renderings.",
                                 "Pixels evaluated on an area-downsampled JPEG grid, with bilinear RAW registration.",
                                 "Flat high-frequency MAD includes residual texture and resampling effects.",
                                 "Edges selected from JPEG; gradients are influenced by tone, filtering and resampling.",
                                 "Delta E76 includes differences in exposure, tone and color; no color fit was used."],
              **result}
    for source in originals.values():
        require(file_digest(pair_dir / source["filename"]) == source["sha256"].lower(), "Original changed during comparison")
    output.mkdir(exist_ok=False)
    tifffile.imwrite(output / "raw-linear-srgb.tiff", linear, photometric="rgb",
                    metadata={"ColorSpace": "linear sRGB primaries, D65; orientation applied; no display gain"})
    icc = srgb_profile().tobytes()
    Image.fromarray(display).save(output / "raw-display-srgb.png", icc_profile=icc)
    Image.fromarray(jpeg).save(output / "jpeg-display-srgb.png", icc_profile=icc)
    (output / "comparison.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pair_dir", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--exposure-ev", type=float, default=0.0, help="Fixed RAW display gain in EV, default 0")
    args = parser.parse_args()
    try:
        report = compare_pair(args.pair_dir, args.out, args.exposure_ev)
    except (ValueError, OSError, rawpy.LibRawError, ImageCms.PyCMSError) as error:
        parser.exit(1, f"Pair comparison failed: {error}\n")
    print(f"{report['input']['pairId']}: {report['alignment']['status']}; comparison.json written")


if __name__ == "__main__":
    main()
