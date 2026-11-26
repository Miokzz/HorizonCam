"""Retrieve private S24 fidelity artifacts and compare raw ISP stages."""

import argparse
import json
from pathlib import Path
import re

import numpy as np

from raw_device_run import LABELS, PACKAGE, adb, digest

EXTENT, EDGE = 136, 128
TILES = (0, 16, 69, 82, 89, 164)
LEGACY_TILES = (0, 16, 82, 89, 164)
SHAPES = {
    "noise": (EXTENT, EXTENT, 3, 4),
    "input": (EXTENT, EXTENT, 8),
    "prediction": (EXTENT, EXTENT, 4),
    "correction": (EDGE, EDGE, 4, 3),
}
DISPLAY_SHAPE = (6, 16, 16, 10)
TILE_FLOATS = sum(int(np.prod(shape)) for shape in SHAPES.values())
FRAME_SHAPE = (2736, 3648)


def fetch(args):
    if not all(re.fullmatch("[a-z0-9_-]{1,50}", run) for run in (args.reference, args.run)):
        raise ValueError("Invalid sandbox run name")
    args.out.mkdir(parents=True, exist_ok=False)
    artifacts = {"native": ("native",), "resident": ("resident", "replay")}
    for directory, run in (("native", args.reference), ("resident", args.run)):
        root = args.out / directory
        root.mkdir()
        paths = ["fidelity.json"]
        paths.extend(f"{label}-{route}.trace" for label in args.pairs for route in artifacts[directory])
        if not args.traces_only:
            paths.extend(f"{label}-{route}.{ext}" for label in args.pairs for route in artifacts[directory]
                         for ext in (("rgb",) if route == "replay" else ("f32", "rgb")))
        for name in paths:
            with (root / name).open("xb") as handle:
                adb(args, "exec-out", "run-as", PACKAGE, "cat", f"files/{run}/{name}", stdout=handle, timeout=120)
        print(f"Collected {len(paths)} private artifacts for {directory}", flush=True)


def read_trace(path, indices=TILES):
    expected = (len(indices) * TILE_FLOATS + int(np.prod(DISPLAY_SHAPE))) * 4
    if path.stat().st_size != expected:
        raise ValueError(f"Unexpected trace size for {path.name}")
    data = np.memmap(path, dtype="<f4", mode="r")
    tiles = []
    for k in range(len(indices)):
        values, offset = {}, k * TILE_FLOATS
        for name, shape in SHAPES.items():
            count = int(np.prod(shape))
            values[name] = data[offset:offset + count].reshape(shape)
            offset += count
        tiles.append(values)
    display = data[len(indices) * TILE_FLOATS:].reshape(DISPLAY_SHAPE)
    return tiles, display


def metrics(reference, observed):
    if reference.shape != observed.shape or reference.dtype != observed.dtype:
        raise ValueError("Incompatible stage shapes")
    if not np.isfinite(reference).all() or not np.isfinite(observed).all():
        raise ValueError("Unexpected nonfinite stage output")
    if reference.dtype.itemsize == 4:
        different = reference.view("<u4") != observed.view("<u4")
    else:
        different = reference != observed
    count = int(np.count_nonzero(different))
    if not count:
        return {"n": reference.size, "different": 0, "max": 0., "p95": 0., "mae": 0.}
    difference = np.abs(reference.astype("f4") - observed)
    return {"n": reference.size, "different": count, "max": float(difference.max()),
            "p95": float(np.percentile(difference, 95)), "mae": float(difference.mean()),
            "first": list(map(int, np.unravel_index(different.argmax(), different.shape)))}


def tile_metrics(reference, observed, replay, ordinal, trace_version=3):
    ref, got = reference, observed
    result = {}
    row, col = divmod(ordinal, 15)
    yy, xx = row * EDGE + np.arange(EXTENT) - 4, col * EDGE + np.arange(EXTENT) - 4
    sensor = np.ix_((yy >= 0) & (yy < 1368), (xx >= 0) & (xx < 1824))
    native_noise, resident_noise = (trace["noise"][sensor] for trace in (ref, got))
    for axis, key in enumerate(("sum3x3", "divide9", "variance")):
        result[key] = metrics(native_noise[..., axis, :], resident_noise[..., axis, :])
    # Multiplication by 32 is exact for these normal sigma values and preserves zero padding.
    result["sigma"] = metrics(ref["input"][:, :, 4:] / np.float32(32), got["input"][:, :, 4:] / np.float32(32))
    result["cnnInput"] = metrics(ref["input"], got["input"])
    result["cnnSignal"] = metrics(ref["input"][:, :, :4], got["input"][:, :, :4])
    result["cnnOutput"] = metrics(ref["prediction"], got["prediction"])
    result["cnnOutputWithSameInput"] = metrics(ref["prediction"], replay["prediction"])
    if trace_version >= 2:
        result["cnnReplayInput"] = metrics(ref["input"], replay["input"])
        if result["cnnReplayInput"]["different"]:
            raise ValueError("GPU replay did not receive the exact NEON tensor")
    h, w = min(EDGE, 1368 - row * EDGE), min(EDGE, 1824 - col * EDGE)
    for axis, key in enumerate(("clippedResidual", "saturationWeight", "weightedDelta")):
        section = (slice(h), slice(w), slice(None), axis)
        result[key] = metrics(ref["correction"][section], got["correction"][section])
        if trace_version >= 3:
            result[key + "WithSamePrediction"] = metrics(ref["correction"][section], replay["correction"][section])
    weights = ref["correction"][:h, :w, :, 1]
    result["attenuationSamples"] = int(np.count_nonzero((weights > 0) & (weights < 1)))
    result["zeroWeightSamples"] = int(np.count_nonzero(weights == 0))
    return result


def display_metrics(reference, observed):
    return {key: metrics(reference[..., channel], observed[..., channel])
            for key, channel in (("gainMaps", 0), ("mhc", slice(1, 4)),
                                 ("colorMatrix", slice(4, 7)), ("exposureClamp", slice(7, 10)))}


def checked_plane(directory, name, dtype, shape, expected):
    path = directory / name
    if path.stat().st_size != int(np.prod(shape)) * np.dtype(dtype).itemsize or digest(path) != expected:
        raise ValueError(f"Wrong length or transport hash for {name}")
    return np.memmap(path, mode="r", dtype=dtype, shape=shape)


def compare(args):
    baseline, resident = (args.input / route for route in ("native", "resident"))
    earlier, report = (json.loads((directory / "fidelity.json").read_text(encoding="utf-8"))
                       for directory in (baseline, resident))
    canonical = "5071b54120e71c0dae7669efff1cbc4862cb8fba377bcd33a90f558e7ade820b"
    if earlier["graphSha256"] != canonical or report["graphSha256"] != canonical:
        raise ValueError("Unexpected model")
    indices = tuple(report.get("tileIndices", LEGACY_TILES))
    if indices != tuple(earlier.get("tileIndices", LEGACY_TILES)) or indices not in (TILES, LEGACY_TILES):
        raise ValueError("Incompatible trace tile map")
    version = report.get("traceVersion", 1)
    native_rows = {entry["label"]: entry["native"] for entry in earlier["pairs"]}
    output = {}
    for row in report["pairs"]:
        label = row["label"]
        if label not in LABELS or label in output or label not in native_rows:
            raise ValueError("Unexpected or repeated fixture")
        row["native"] = native_rows[label]
        (native, display), (candidate, display_gpu), (replay, display_replay) = (
            read_trace(directory / f"{label}-{route}.trace", indices) for directory, route in
            ((baseline, "native"), (resident, "resident"), (resident, "replay")))
        result = {
            "tiles": {tile: tile_metrics(ref, got, replayed, tile, version)
                      for tile, ref, got, replayed in
                      zip(indices, native, candidate, replay)},
            "differentBayerDisplay": display_metrics(display, display_gpu),
            "sameBayerDisplay": display_metrics(display, display_replay),
            "rawHashEqual": row["native"]["raw"] == row["resident"]["raw"],
            "rgbHashEqual": row["native"]["rgb"] == row["resident"]["rgb"],
            "rgbWithSameBayerHashEqual": row["native"]["rgb"] == row["replayRgb"],
        }
        if not args.traces_only:
            a, b = (checked_plane(directory, f"{label}-{route}.f32", "<f4", (1368, 1824, 4), row[route]["raw"])
                    for directory, route in ((baseline, "native"), (resident, "resident")))
            result["restoredBayer"] = metrics(a, b)
            shape = (*FRAME_SHAPE, 3)
            a, b = (checked_plane(directory, f"{label}-{route}.rgb", "u1", shape, row[route]["rgb"])
                    for directory, route in ((baseline, "native"), (resident, "resident")))
            result["sRgb"] = metrics(a, b)
            c = checked_plane(resident, f"{label}-replay.rgb", "u1", shape, row["replayRgb"])
            result["sRgbWithSameBayer"] = metrics(a, c)
        output[label] = result
    with args.output.open("x", encoding="utf-8") as handle:
        json.dump(output, handle, indent=2, allow_nan=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(required=True)
    retrieve = commands.add_parser("fetch")
    retrieve.add_argument("--adb", type=Path, required=True)
    retrieve.add_argument("--serial")
    retrieve.add_argument("--run", required=True)
    retrieve.add_argument("--reference", required=True)
    retrieve.add_argument("--out", type=Path, required=True)
    retrieve.add_argument("--pairs", nargs="+", choices=LABELS, default=LABELS)
    retrieve.add_argument("--traces-only", action="store_true")
    retrieve.set_defaults(action=fetch)
    diff = commands.add_parser("compare")
    diff.add_argument("--input", type=Path, required=True)
    diff.add_argument("--output", type=Path, required=True)
    diff.add_argument("--traces-only", action="store_true")
    diff.set_defaults(action=compare)
    args = parser.parse_args()
    args.action(args)


if __name__ == "__main__":
    main()
