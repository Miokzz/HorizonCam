"""Single-frame laboratory RAW ISP for the Galaxy S24 physical telephoto."""

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import time

import cv2
import numpy as np
from PIL import Image
import rawpy
import tifffile

from compare_tele_raw import (decode_jpeg, describe_pixels, develop_raw, display_srgb,
                              orient_rgb, require, srgb_profile, validate_manifest)
from tele_isp_fixture import fixture_metrics, synthetic_fixture


CFA = ((1, 2), (0, 1))  # GBRG, full active area, row-major phase order
BORDER = cv2.BORDER_REFLECT_101
ILLUMINANTS_K = {17: 2856.0, 21: 6504.0}  # Standard A, D65
XYZ_D50 = np.array([0.96422, 1.0, 0.82521])
XYZ_D65 = np.array([0.95047, 1.0, 1.08883])
XYZ_TO_RGB = np.array([[3.2404542, -1.5371385, -0.4985314],
                       [-0.9692660, 1.8760108, 0.0415560],
                       [0.0556434, -0.2040259, 1.0572252]])
BRADFORD = np.array([[0.8951, 0.2664, -0.1614],
                    [-0.7502, 1.7135, 0.0367],
                    [0.0389, -0.0685, 1.0296]])

# Malvar, He and Cutler, ICASSP 2004. The second kernel estimates the
# missing channel whose samples lie along the current row at a green site.
GREEN_AT_RB = np.array([[0, 0, -1, 0, 0], [0, 0, 2, 0, 0], [-1, 2, 4, 2, -1],
                        [0, 0, 2, 0, 0], [0, 0, -1, 0, 0]], np.float32) / 8
COLOR_ALONG_ROW = np.array([[0, 0, .5, 0, 0], [0, -1, 0, -1, 0], [-1, 4, 5, 4, -1],
                            [0, -1, 0, -1, 0], [0, 0, .5, 0, 0]], np.float32) / 8
OPPOSITE_COLOR = np.array([[0, 0, -1.5, 0, 0], [0, 2, 0, 2, 0], [-1.5, 0, 6, 0, -1.5],
                           [0, 2, 0, 2, 0], [0, 0, -1.5, 0, 0]], np.float32) / 8


def phases():
    for y, row in enumerate(CFA):
        for x, channel in enumerate(row):
            yield y, x, channel, np.s_[y::2, x::2]


def bilinear(mosaic):
    result = np.empty((*mosaic.shape, 3), np.float32)
    kernel = np.array([[1, 2, 1], [2, 4, 2], [1, 2, 1]], np.float32)
    for channel in range(3):
        mask = np.zeros(mosaic.shape, np.float32)
        for _, _, color, site in phases():
            if color == channel:
                mask[site] = 1
        samples = cv2.filter2D(mosaic * mask, -1, kernel, borderType=BORDER)
        normalizer = cv2.filter2D(mask, -1, kernel, borderType=BORDER)
        result[:, :, channel] = samples / normalizer
        for _, _, color, site in phases():
            if color == channel:
                result[site + (channel,)] = mosaic[site]
    return result


def malvar(mosaic):
    g, h, v, cross = (cv2.filter2D(mosaic, -1, kernel, borderType=BORDER)
                      for kernel in (GREEN_AT_RB, COLOR_ALONG_ROW, COLOR_ALONG_ROW.T, OPPOSITE_COLOR))
    result = np.empty((*mosaic.shape, 3), np.float32)
    for y, x, channel, site in phases():
        result[site + (channel,)] = mosaic[site]
        if channel == 1:
            result[site + (CFA[y][1 - x],)] = h[site]
            result[site + (CFA[1 - y][x],)] = v[site]
        else:
            result[site + (1,)] = g[site]
            result[site + (2 - channel,)] = cross[site]
    return result


def denoise_mosaic(mosaic, profile, directional=False):
    """Local Wiener estimate, before gains, on each of the four CFA subgrids."""
    filtered = np.empty_like(mosaic)
    for _, _, channel, site in phases():
        plane = mosaic[site]
        mean = cv2.boxFilter(plane, -1, (5, 5), borderType=BORDER)
        variance = np.maximum(0, cv2.boxFilter(plane * plane, -1, (5, 5), borderType=BORDER) - mean * mean)
        slope, offset = profile[channel]
        noise = slope * np.maximum(mean, 0) + offset
        weight = 1 - np.minimum(1, noise / np.maximum(variance, 1e-12))
        if directional:
            square = plane * plane
            total_weights = np.zeros_like(plane)
            steered_mean = np.zeros_like(plane)
            for direction in (np.full((1, 5), .2, np.float32), np.full((5, 1), .2, np.float32),
                              np.eye(5, dtype=np.float32) / 5, np.fliplr(np.eye(5, dtype=np.float32)) / 5):
                along = cv2.filter2D(plane, -1, direction, borderType=BORDER)
                spread = cv2.filter2D(square, -1, direction, borderType=BORDER) - along * along
                penalty = np.maximum(spread, 0) + noise
                support = np.maximum(penalty, 1e-12) ** -2
                steered_mean += along * support
                total_weights += support
            # Prefer the directional estimate only when structure emerges from
            # the expected noise. A flat patch keeps the wider 5×5 estimate.
            steer = np.clip((variance - noise) / np.maximum(2 * noise, 1e-12), 0, 1)
            mean += steer * (steered_mean / total_weights - mean)
        # Keep the original at sites near saturation. A local statistic cannot
        # reconstruct clipped photosites or distinguish clipped color channels.
        weight = np.maximum(weight, np.clip((plane - .90) / .08, 0, 1))
        filtered[site] = mean + (plane - mean) * weight
    return filtered


def numeric(page, name, count, default=None):
    tag = page.tags.get(name)
    if tag is None:
        require(default is not None, f"Missing {name}")
        return np.asarray(default, dtype=np.float64).reshape(count)
    data = np.asarray(tag.value, dtype=np.float64)
    if int(tag.dtype) in (5, 10):  # TIFF RATIONAL, SRATIONAL
        values = data.reshape(-1, 2)
        require(np.all(values[:, 1] != 0), f"Zero denominator in {name}")
        data = values[:, 0] / values[:, 1]
    require(data.size == count and np.all(np.isfinite(data)), f"Invalid {name}")
    return data.reshape(count)


def estimate_white_temperature(xyz):
    require(np.all(np.isfinite(xyz)) and np.all(xyz > 0), "Invalid illuminant XYZ")
    x, y = xyz[:2] / xyz.sum()
    # McCamy's approximation is used only to interpolate the two DNG profiles.
    require(abs(.1858 - y) > .01, "Illuminant is outside the CCT approximation")
    ratio = (x - .3320) / (.1858 - y)
    return 449 * ratio ** 3 + 3525 * ratio ** 2 + 6823.3 * ratio + 5520.33


def dng_color_matrix(page):
    temperatures = [ILLUMINANTS_K.get(int(numeric(page, f"CalibrationIlluminant{i}", 1)[0])) for i in (1, 2)]
    require(all(value is not None for value in temperatures) and temperatures[0] != temperatures[1],
            "Expected dual D65/standard-A calibration")
    matrices = [numeric(page, f"ColorMatrix{i}", 9).reshape(3, 3) for i in (1, 2)]
    forward = [numeric(page, f"ForwardMatrix{i}", 9).reshape(3, 3) for i in (1, 2)]
    analog = np.diag(numeric(page, "AnalogBalance", 3, np.ones(3)))
    profile_sig = page.tags.get("ProfileCalibrationSignature")
    camera_sig = page.tags.get("CameraCalibrationSignature")
    same_sig = (profile_sig.value if profile_sig else "") == (camera_sig.value if camera_sig else "")
    calibration = [numeric(page, f"CameraCalibration{i}", 9, np.eye(3)).reshape(3, 3)
                   if same_sig else np.eye(3) for i in (1, 2)]
    neutral = numeric(page, "AsShotNeutral", 3)
    require(np.all(neutral > 0), "Invalid as-shot neutral")
    inverse_kelvin = [1 / value for value in temperatures]

    def mix(m, fraction):
        return (1 - fraction) * m[0] + fraction * m[1]

    fraction = .5
    for _ in range(30):
        camera_from_xyz = analog @ mix(calibration, fraction) @ mix(matrices, fraction)
        kelvin = estimate_white_temperature(np.linalg.solve(camera_from_xyz, neutral))
        kelvin = float(np.clip(kelvin, min(temperatures), max(temperatures)))
        following = (1 / kelvin - inverse_kelvin[0]) / (inverse_kelvin[1] - inverse_kelvin[0])
        if abs(following - fraction) < 1e-6:
            fraction = following
            break
        fraction = following
    else:
        raise ValueError("As-shot color interpolation failed to converge")

    reference_from_camera = np.linalg.inv(analog @ mix(calibration, fraction))
    reference_neutral = reference_from_camera @ neutral
    require(np.all(reference_neutral > 0), "Invalid reference-camera neutral")
    mapping = mix(forward, fraction)
    require(np.all(mapping.sum(axis=1) > 0), "Invalid forward matrix")
    mapping = mapping * (XYZ_D50 / mapping.sum(axis=1))[:, None]
    adaptation = np.linalg.solve(BRADFORD, np.diag((BRADFORD @ XYZ_D65) / (BRADFORD @ XYZ_D50)) @ BRADFORD)
    conversion = XYZ_TO_RGB @ adaptation @ mapping @ np.diag(1 / reference_neutral) @ reference_from_camera
    require(np.allclose(conversion @ neutral, 1, atol=.001), "White point failed validation")
    return conversion.astype(np.float32), {"cctApproxK": kelvin, "profile2Weight": fraction,
                                            "cameraNeutral": neutral.tolist(), "cameraToLinearSrgb": conversion.tolist()}


@dataclass
class SensorInput:
    mosaic: np.ndarray
    noise_profile: np.ndarray
    color_matrix: np.ndarray
    color_calibration: dict
    optics: object
    orientation: int


def read_sensor(pair, manifest):
    from tele_dng_optics import read_optics

    with tifffile.TiffFile(pair / "source.dng") as source:
        page = source.pages[0]
        require(page.photometric == 32803 and page.bitspersample == 16 and page.dtype == np.uint16,
                "Expected the full 16-bit Bayer IFD")
        require(page.tags["CFAPattern"].value == bytes([1, 2, 0, 1]) and
                tuple(page.tags["CFARepeatPatternDim"].value) == (2, 2), "Expected full GBRG phase")
        require(tuple(page.tags["ActiveArea"].value) == (0, 0, page.imagelength, page.imagewidth),
                "The experiment requires full active-area input")
        require(page.imagelength == manifest["dng"]["height"] and page.imagewidth == manifest["dng"]["width"] and
                page.tags["Orientation"].value == manifest["dng"]["orientation"], "Manifest and sensor geometry differ")
        require(not any(name in page.tags for name in ("LinearizationTable", "BlackLevelDeltaH", "BlackLevelDeltaV")),
                "Additional sensor linearization is outside this recipe")
        require(tuple(page.tags["BlackLevelRepeatDim"].value) == (2, 2), "Expected a per-phase black level")
        black = numeric(page, "BlackLevel", 4).reshape(2, 2)
        white = numeric(page, "WhiteLevel", 1)[0]
        require(np.all(white > black), "Invalid white and black levels")
        samples = page.asarray().astype(np.float32)
        for y, x, _, region in phases():
            samples[region] = (samples[region] - black[y, x]) / (white - black[y, x])
        profile = numeric(page, "NoiseProfile", 6).reshape(3, 2).astype(np.float32)
        require(np.all(profile >= 0) and np.all(np.any(profile > 0, axis=1)), "Invalid DNG NoiseProfile")
        matrix, evidence = dng_color_matrix(page)
        evidence.update(blackLevels=black.tolist(), whiteLevel=white, noiseProfile=profile.tolist())
        return SensorInput(samples, profile, matrix, evidence, read_optics(page), manifest["dng"]["orientation"])


def render(linear, ev, shoulder=False):
    """Apply one explicit exposure and optional luma shoulder before sRGB."""
    adjusted = linear * (2 ** ev)
    if shoulder:
        y = adjusted @ np.array([.2126, .7152, .0722], dtype=np.float32)
        knee = .75
        above = y > knee
        # Applies equal gain to the three channels. Gamut clipping remains explicit.
        factor = (knee - (1 - knee) * np.expm1(-(y[above] - knee) / (1 - knee))) / y[above]
        adjusted[above] *= factor[:, None]
    clipped = float(np.mean(np.max(adjusted, axis=2) >= 1))
    values = np.clip(adjusted, 0, 1)
    display = np.where(values <= .0031308, 12.92 * values, 1.055 * values ** (1 / 2.4) - .055)
    return np.round(display * 255).astype(np.uint8), clipped


def flatness(mosaic):
    results = []
    for y, x, channel, site in phases():
        plane = mosaic[site]
        cells = np.array([[np.median(tile) for tile in np.array_split(row, 12, axis=1)]
                          for row in np.array_split(plane, 8, axis=0)])
        center = float(np.median(cells[3:5, 5:7]))
        require(center > .02 and np.all(cells > 0), "Flat field is too dark for relative shading evidence")
        corners = np.array([cells[0, 0], cells[0, -1], cells[-1, 0], cells[-1, -1]]) / center
        p10, p90 = np.percentile(cells, (10, 90))
        results.append({"phase": [y, x], "channel": "RGB"[channel], "center": center,
                        "cornerToCenter": corners.tolist(), "spreadEvP90P10": float(np.log2(p90 / p10))})
    return results


def flat_noise_repeat(a, b, a_profile, b_profile):
    """Validate sensor noise from independent flat captures without producing an image."""
    require(a.shape == b.shape, "Flat repeats must share the same active area")
    report = []
    for y, x, channel, site in phases():
        first, repeat = a[site], b[site]
        ratio = float(np.median(first) / np.median(repeat))
        require(.95 <= ratio <= 1.05, "Flat repeats have different illumination or exposure")
        variance_ratios = []
        for row_a, row_b in zip(np.array_split(first, 6, axis=0), np.array_split(repeat, 6, axis=0)):
            for tile_a, tile_b in zip(np.array_split(row_a, 8, axis=1), np.array_split(row_b, 8, axis=1)):
                difference = tile_a - ratio * tile_b
                sigma = 1.4826 * np.median(np.abs(difference - np.median(difference)))
                predicted_a = a_profile[channel] @ [max(float(np.median(tile_a)), 0), 1]
                predicted_b = b_profile[channel] @ [max(float(np.median(tile_b)), 0), 1]
                variance_ratios.append(float(sigma ** 2 / (predicted_a + ratio ** 2 * predicted_b)))
        report.append({"phase": [y, x], "channel": "RGB"[channel], "firstToRepeatSignalRatio": ratio,
                       "measuredToPredictedVarianceP10P50P90": np.percentile(variance_ratios, (10, 50, 90)).tolist()})
    return report


def measured_noise_scale(report):
    rows = report["flatField"]["noiseProfileRepeat"]
    require(len(rows) == 4, "Expected four calibrated Bayer phases")
    by_channel = [[], [], []]
    for (y, x, channel, _), row in zip(phases(), rows):
        require(row["phase"] == [y, x] and row["channel"] == "RGB"[channel], "Bad calibration phase order")
        value = row["measuredToPredictedVarianceP10P50P90"][1]
        require(type(value) in (int, float) and .1 <= value <= 10, "Invalid experimental variance scale")
        by_channel[channel].append(value)
    return np.array([np.mean(values) for values in by_channel], dtype=np.float32).reshape(3, 1)


def save_display(path, rgb):
    Image.fromarray(rgb).save(path, icc_profile=srgb_profile().tobytes())


def compare_display(rgb, jpeg, flat_field):
    if not flat_field:
        return describe_pixels(rgb, jpeg)

    def center_median(image):
        h, w = image.shape[:2]
        return np.median(image[h * 2 // 5:h * 3 // 5, w * 2 // 5:w * 3 // 5], axis=(0, 1)).tolist()

    # Noise and fixed-pattern texture can create spurious features on a flat target.
    return {"alignment": {"status": "not_evaluated", "reason": "Uniform target has no geometry reference"},
            "metrics": None, "flatDisplayCenterMedianRgb255": {"raw": center_median(rgb), "jpeg": center_median(jpeg)}}


def synthetic_comparison(profile, calibrated_profile=None):
    rows = []
    for seed in (631, 1237, 4639):
        slope, offset = profile[1]
        truth, noisy, regions = synthetic_fixture(seed, float(slope), float(offset))
        candidates = [("bilinear", bilinear(noisy)), ("malvar", malvar(noisy)),
                      ("malvar-noise", malvar(denoise_mosaic(noisy, profile))),
                      ("malvar-directional", malvar(denoise_mosaic(noisy, profile, directional=True)))]
        if calibrated_profile is not None:
            candidates.append(("malvar-calibrated", malvar(denoise_mosaic(noisy, calibrated_profile))))
        for name, result in candidates:
            rows.append({"seed": seed, "variant": name, "metrics": fixture_metrics(result, truth, regions)})
    return rows


def experiment(pair, output, ev, flat_field=False, export_linear=None, flat_repeat=None, noise_calibration=None):
    pair = pair.resolve()
    output = output.resolve()
    require(not output.exists(), "Output already exists. Choose a new experiment directory.")
    require(export_linear != "malvar-calibrated" or noise_calibration is not None,
            "Calibrated linear export requires a flat-field report")
    manifest = validate_manifest(pair)
    sensor = read_sensor(pair, manifest)
    optics = sensor.optics.diagnostic()
    gains = sensor.optics.gain_field(sensor.mosaic.shape)
    results = {"schemaVersion": 1, "pairId": manifest["pairId"], "exposureEv": ev, "optics": optics,
               "color": sensor.color_calibration, "whiteClippingFractionCfa": float(np.mean(sensor.mosaic >= 1))}
    calibrated_profile = None
    if noise_calibration is not None:
        calibration = json.loads(noise_calibration.read_text(encoding="utf-8"))
        scale = measured_noise_scale(calibration)
        calibrated_profile = sensor.noise_profile * scale
        results["experimentalNoiseCalibration"] = {"sourcePairId": calibration["pairId"],
                                                   "varianceScaleRgb": scale.ravel().tolist()}
    if flat_field:
        results["flatField"] = {"uncorrected": flatness(sensor.mosaic), "gainMap": flatness(sensor.mosaic * gains)}
    if flat_repeat is not None:
        require(flat_field, "A flat repeat requires --flat-field")
        repeat_manifest = validate_manifest(flat_repeat)
        require(repeat_manifest["sensorTimestampNs"] != manifest["sensorTimestampNs"], "Expected independent captures")
        require(all(repeat_manifest[k] == manifest[k] for k in ("deviceModel", "firmware")), "Flat repeats differ in device or firmware")
        for key in ("iso", "exposureNs"):
            require(.97 <= repeat_manifest[key] / manifest[key] <= 1.03, f"Flat repeats differ in {key}")
        repeat = read_sensor(flat_repeat, repeat_manifest)
        results["flatField"]["noiseProfileRepeat"] = flat_noise_repeat(sensor.mosaic, repeat.mosaic,
                                                                        sensor.noise_profile, repeat.noise_profile)
    jpeg, _ = decode_jpeg(pair / "source.jpg", manifest)
    output.mkdir(parents=True)
    save_display(output / "jpeg.png", jpeg)
    results["variants"] = {}
    for name, decoder in (("libraw-ahd", rawpy.DemosaicAlgorithm.AHD), ("libraw-dcb", rawpy.DemosaicAlgorithm.DCB)):
        baseline, _ = develop_raw(pair / "source.dng", manifest, decoder)
        control, clipping = display_srgb(baseline, ev)
        results["variants"][name] = {"clipFraction": clipping, **compare_display(control, jpeg, flat_field)}
        save_display(output / f"{name}.png", control)
        del baseline, control

    denoised = denoise_mosaic(sensor.mosaic, sensor.noise_profile)
    directional = denoise_mosaic(sensor.mosaic, sensor.noise_profile, directional=True)
    candidates = [
        ("bilinear", bilinear, sensor.mosaic, False, False, False),
        ("malvar", malvar, sensor.mosaic, False, False, False),
        ("malvar-noise", malvar, denoised, False, False, False),
        ("malvar-gain", malvar, denoised, True, False, False),
        ("malvar-directional", malvar, directional, True, False, False),
        ("malvar-optics", malvar, denoised, True, True, False),
        ("malvar-shoulder", malvar, denoised, True, True, True),
    ]
    if calibrated_profile is not None:
        candidates.append(("malvar-calibrated", malvar, denoise_mosaic(sensor.mosaic, calibrated_profile), True, False, False))
    require(export_linear is None or export_linear in {item[0] for item in candidates}, "Linear export requires an enabled RAW variant")
    for name, algorithm, mosaic, apply_gain, apply_warp, shoulder in candidates:
        started = time.perf_counter()
        rgb = algorithm(mosaic * gains if apply_gain else mosaic) @ sensor.color_matrix.T
        if apply_warp:
            rgb = sensor.optics.warp(rgb)
        rgb = orient_rgb(rgb, sensor.orientation)
        if export_linear == name:
            tifffile.imwrite(output / f"{name}-linear-f32.tiff", rgb, photometric="rgb",
                             description="Float32 scene-linear sRGB. No display gain, tone curve or gamut clipping.")
        image, clipped = render(rgb, ev, shoulder)
        del rgb
        duration = time.perf_counter() - started
        evidence = {"renderSeconds": duration, "clipFraction": clipped, **compare_display(image, jpeg, flat_field)}
        results["variants"][name] = evidence
        save_display(output / f"{name}.png", image)
    results["syntheticLinearReference"] = synthetic_comparison(sensor.noise_profile, calibrated_profile)
    (output / "isp-report.json").write_text(json.dumps(results, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(output / "isp-report.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pair", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--exposure-ev", type=float, default=1.5)
    parser.add_argument("--flat-field", action="store_true", help="Report spatial shading on an actual flat target")
    parser.add_argument("--flat-repeat", type=Path, help="An independent capture of the same flat target for noise analysis")
    parser.add_argument("--noise-calibration", type=Path, help="Add an ablation using the variance ratios of a flat-field report")
    parser.add_argument("--export-linear", choices=("bilinear", "malvar", "malvar-noise", "malvar-gain",
                                                     "malvar-directional", "malvar-optics", "malvar-shoulder", "malvar-calibrated"))
    args = parser.parse_args()
    require(np.isfinite(args.exposure_ev) and -6 <= args.exposure_ev <= 6, "Exposure EV must be between -6 and +6")
    experiment(args.pair, args.out, args.exposure_ev, args.flat_field, args.export_linear, args.flat_repeat,
               args.noise_calibration)


if __name__ == "__main__":
    main()
