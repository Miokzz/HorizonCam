"""Prepare private RAW inputs and score Android RAW benchmark outputs on the host."""

import argparse
import json
from pathlib import Path
import struct

import cv2
import numpy as np

from compare_tele_raw import decode_jpeg, file_digest, orient_rgb, require, validate_manifest
from tele_raw_isp import compare_display, malvar, phases, read_sensor, render, save_display


MODEL_SHA = "5071b54120e71c0dae7669efff1cbc4862cb8fba377bcd33a90f558e7ade820b"
LABELS = ("detail-a", "detail-b", "flat-a", "flat-b")


def packed(mosaic):
    return np.stack([mosaic[site] for _, _, _, site in phases()], axis=-1)


def unpacked(planes):
    mosaic = np.empty((planes.shape[0] * 2, planes.shape[1] * 2), np.float32)
    for i, (_, _, _, site) in enumerate(phases()):
        mosaic[site] = planes[..., i]
    return mosaic


def prepare(args):
    require(args.model.stat().st_size == 27_620 and file_digest(args.model) == MODEL_SHA,
            "The canonical RAW network differs from checkpoint 0712e96")
    training = json.loads(args.training.read_text(encoding="utf-8"))
    scale = np.asarray(training["flatVarianceScaleRgb"], np.float32).reshape(3, 1)
    require(np.all(np.isfinite(scale) & (scale >= .1) & (scale <= 10)), "Invalid noise calibration")
    args.out.mkdir(parents=True, exist_ok=False)
    rows = []
    for label in LABELS:
        pair = getattr(args, label.replace("-", "_")).resolve()
        manifest = validate_manifest(pair)
        sensor = read_sensor(pair, manifest)
        plane = packed(sensor.mosaic)
        height, width, _ = plane.shape
        profile = (sensor.noise_profile * scale)[[color for _, _, color, _ in phases()]]
        maps = sorted(sensor.optics.gain_tables(), key=lambda gain: gain.phase)
        require([gain.phase for gain in maps] == [(0, 0), (0, 1), (1, 0), (1, 1)], "Incomplete tele GainMaps")
        path = args.out / f"{label}.rawbench"
        with path.open("xb") as out:
            out.write(struct.pack("<4sIII", b"HRW1", height, width, sensor.orientation))
            out.write(profile.astype("<f4").tobytes())
            out.write(sensor.color_matrix.astype("<f4").tobytes())
            for gain in maps:
                out.write(struct.pack("<2i4d", *gain.samples.shape, *gain.spacing, *gain.origin))
                out.write(gain.samples.astype("<f4").tobytes())
            plane.astype("<f4", copy=False).tofile(out)
        rows.append({"label": label, "pairId": manifest["pairId"], "fixtureSha256": file_digest(path),
                     "originalPair": str(pair), "packedShape": list(plane.shape)})
    (args.out / "fixtures.json").write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")


def parity(observed, reference):
    difference = np.abs(observed.astype(np.float32) - reference)
    return {"mae": float(difference.mean()), "p95": float(np.percentile(difference, 95)),
            "max": float(difference.max())}


def score(args):
    require(args.modes[0] == "cpu" and len(set(args.modes)) == len(args.modes),
            "List each mode once, starting with CPU")
    require(not args.output.exists(), "Choose a new report path")
    renders = args.output.with_name(args.output.stem + "-images")
    renders.mkdir()
    manifest_rows = json.loads(args.fixtures.read_text(encoding="utf-8"))
    rows = []
    for row in manifest_rows:
        label, pair = row["label"], Path(row["originalPair"])
        manifest = validate_manifest(pair)
        require(row["pairId"] == manifest["pairId"], "Original DNG pair changed")
        sensor = read_sensor(pair, manifest)
        jpeg, _ = decode_jpeg(pair / "source.jpg", manifest)
        baseline_dir = args.baseline / label
        baseline = cv2.cvtColor(cv2.imread(str(baseline_dir / "mhc-wiener-gainmap.png")), cv2.COLOR_BGR2RGB)
        flat = label.startswith("flat")
        gains = sensor.optics.gain_field(sensor.mosaic.shape)
        cpu_data = np.memmap(args.runs / "cpu" / f"{label}.f32", dtype="<f4", mode="r", shape=tuple(row["packedShape"]))
        image_display_cpu = None
        entry = {"setting": label, "classical": compare_display(baseline, jpeg, flat), "outputs": {}}
        destination = renders / label
        destination.mkdir()
        for mode in args.modes:
            source = args.runs / mode / f"{label}.f32"
            values = np.memmap(source, dtype="<f4", mode="r", shape=tuple(row["packedShape"]))
            require(np.isfinite(values).all() and source.stat().st_size == values.nbytes, "Invalid Android output")
            linear = malvar(unpacked(values) * gains) @ sensor.color_matrix.T
            display, clipping = render(orient_rgb(linear, sensor.orientation), 1.5)
            if mode == "cpu":
                image_display_cpu = display
            require(image_display_cpu is not None, "CPU must be the first mode")
            evidence = {"rawParityVsCpu": parity(values, cpu_data),
                        "displayParityVsCpu": parity(display, image_display_cpu), "clipFraction": clipping,
                        **compare_display(display, jpeg, flat)}
            evidence["numericPrecisionGate"] = (evidence["rawParityVsCpu"]["p95"] <= 1 / 958.67 and
                                                evidence["displayParityVsCpu"]["p95"] <= 1)
            if mode == "cpu":
                pytorch = cv2.imread(str(baseline_dir / "neural-residual.png"))
                if pytorch is not None:
                    evidence["displayParityVsLabPyTorch"] = parity(display, cv2.cvtColor(pytorch, cv2.COLOR_BGR2RGB))
            if label == "detail-a":
                device_rgb = args.runs / mode / f"{label}.rgb"
                if device_rgb.is_file():
                    rgb = np.fromfile(device_rgb, dtype=np.uint8).reshape((*sensor.mosaic.shape, 3))
                    require(sensor.orientation == 1, "Device RGB parity requires unrotated reference")
                    evidence["deviceIspParityVsHost"] = parity(rgb, display)
            save_display(destination / f"{mode}.png", display)
            entry["outputs"][mode] = evidence
        rows.append(entry)
    args.output.write_text(json.dumps(rows, indent=2, allow_nan=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(required=True)
    prep = sub.add_parser("prepare")
    for name in (*LABELS, "model", "training", "out"):
        prep.add_argument(f"--{name}", type=Path, required=True)
    prep.set_defaults(action=prepare)
    report = sub.add_parser("score")
    for name in ("fixtures", "baseline", "runs", "output"):
        report.add_argument(f"--{name}", type=Path, required=True)
    report.add_argument("--modes", nargs="+", required=True)
    report.set_defaults(action=score)
    args = parser.parse_args()
    args.action(args)
