"""Replay full-resolution archived restored Bayer through the actual native and OpenCL developers."""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import statistics
import struct
import time

import numpy as np

from offdevice_corpus import digest

H, W = 1368, 1824
HERE = Path(__file__).resolve().parent
KERNEL = HERE / "raw_device/src/main/cpp/raw_resident_kernels.inc"


def axis(size, samples, phase, spacing, origin):
    if not np.isfinite([spacing, origin]).all() or spacing <= 0:
        raise ValueError("Invalid GainMap axis")
    center = (2 * np.arange(size) + phase + .5) / (2 * size)
    pos = np.clip(center - origin, 0., (samples - 1) * spacing) / spacing
    low = np.minimum(pos.astype(int), samples - 1)
    return np.column_stack((low, np.minimum(low + 1, samples - 1), pos - low)).astype("<f4").ravel()


def parameters(path):
    with path.open("rb") as reader:
        if struct.unpack("<4s3i", reader.read(16)) != (b"HRW1", H, W, 1):
            raise ValueError("Expected full orientation-1 tele fixture")
        reader.read(32)  # phase noise slopes and offsets are applied upstream
        fields = [np.frombuffer(reader.read(36), "<f4")]
        offsets = []
        for phase in range(4):
            rows, cols, sy, sx, oy, ox = struct.unpack("<ii4d", reader.read(40))
            if not (0 < rows <= 256 and 0 < cols <= 256):
                raise ValueError("Invalid GainMap shape")
            offsets.append(sum(v.size for v in fields))
            fields.extend((np.array([cols, rows], np.float32),
                           np.frombuffer(reader.read(rows * cols * 4), "<f4"),
                           axis(H, rows, phase // 2, sy, oy), axis(W, cols, phase % 2, sx, ox)))
        if path.stat().st_size - reader.tell() != H * W * 16:
            raise ValueError("Truncated HDR fixture")
    return np.concatenate(fields), np.array(offsets, np.int32)


def lut():
    x = np.arange(65536) / 65535.
    y = np.where(x <= .0031308, 12.92 * x, 1.055 * x ** (1 / 2.4) - .055)
    return np.rint(255 * y).astype(np.uint8)


def summarize(rows):
    return {key: {"p50":statistics.median([r[key] for r in rows]),
                  "p95":float(np.percentile([r[key] for r in rows], 95))}
            for key in rows[0]}


def replay(args):
    import pyopencl as cl
    devices = [device for platform in cl.get_platforms() for device in platform.get_devices()
               if device.type & cl.device_type.GPU and args.gpu.lower() in device.name.lower()]
    if len(devices) != 1 or args.repeats < 2:
        raise ValueError("Specify one host GPU and at least two timed repetitions")
    args.out.mkdir(parents=True, exist_ok=False)
    clock = time.perf_counter_ns
    start = clock()
    ctx = cl.Context(devices)
    queue = cl.CommandQueue(ctx, properties=cl.command_queue_properties.PROFILING_ENABLE)
    source = KERNEL.read_text().strip()
    if not source.startswith('R"CLC(') or not source.endswith(')CLC"'):
        raise ValueError("Kernel wrapper changed")
    program = cl.Program(ctx, source[6:-5]).build(options=["-DAUDIT_CAPTURE=0", "-DISP_MATH=0"])
    kernel = cl.Kernel(program, "develop")
    init_ms = (clock() - start) / 1e6
    cpu = ctypes.CDLL(str(args.library.resolve())).develop_tele_frame
    cpu.argtypes = [ctypes.c_void_p] * 5
    cpu.restype = None
    reference = json.loads((args.reference / "timing.json").read_text(encoding="utf-8"))
    known = {r["label"]:r["rgbSha256"] for r in reference["pairs"]}
    mf = cl.mem_flags
    lookup = lut()
    table = cl.Buffer(ctx, mf.READ_ONLY | mf.COPY_HOST_PTR, hostbuf=lookup)
    rgb = np.empty(H * W * 12, np.uint8)
    ref_rgb = np.empty_like(rgb)
    scratch = np.empty(W * 2 * 5, np.float32)
    dest = cl.Buffer(ctx, mf.WRITE_ONLY, size=rgb.nbytes)
    report = dict(device=devices[0].name, driver=devices[0].driver_version, hostPlatform=devices[0].platform.name,
                  compiledMs=init_ms, kernelSha=digest(KERNEL), repeats=args.repeats, pairs={})
    for item in json.loads((args.fixtures / "fixtures.json").read_text(encoding="utf-8")):
        label = item["label"]
        fixture = args.fixtures / (label + ".rawbench")
        if digest(fixture) != item["fixtureSha256"]:
            raise ValueError("Fixture hash mismatch")
        fields, offsets = parameters(fixture)
        raw_path = args.reference / (label + ".f32")
        if raw_path.stat().st_size != H * W * 16:
            raise ValueError("Expected packed FP32 restored raster")
        host_raw = np.fromfile(raw_path, dtype="<f4")
        if not np.isfinite(host_raw).all() or not np.isfinite(fields).all():
            raise ValueError("Nonfinite input")
        cpu_args = [a.ctypes.data for a in (host_raw, fields, scratch, lookup, ref_rgb)]
        host_times = []
        for _ in range(3):
            start = clock()
            cpu(*cpu_args)
            host_times.append((clock() - start) / 1e6)
        if hashlib.sha256(ref_rgb).hexdigest() != known[label]:
            raise ValueError(f"Host C++ renderer differs from archived S24 rendering: {label}")
        src = cl.Buffer(ctx, mf.READ_ONLY, size=host_raw.nbytes)
        metadata = cl.Buffer(ctx, mf.READ_ONLY | mf.COPY_HOST_PTR, hostbuf=fields)
        upload = cl.enqueue_copy(queue, src, host_raw, is_blocking=False)
        upload.wait()
        raw_copy = np.empty_like(host_raw)
        def measure(include_raw):
            start = clock()
            event = kernel(queue, (W * 2, H * 2), (16, 16), src, metadata, offsets.tobytes(), table, dest, None)
            raw_read = cl.enqueue_copy(queue, raw_copy, src, is_blocking=False) if include_raw else None
            end = cl.enqueue_copy(queue, rgb, dest, is_blocking=False)
            end.wait()
            elapsed = (clock() - start) / 1e6
            def ms(obj):
                return (obj.profile.end - obj.profile.start) / 1e6
            return dict(wallMs=elapsed, renderMs=ms(event), rgbReadMs=ms(end),
                        rawReadMs=ms(raw_read) if raw_read is not None else 0.)
        first = measure(False)
        groups = {"rgb":[], "rgbAndRaw":[]}
        for rep in range(args.repeats):
            order = (False, True) if rep % 2 == 0 else (True, False)
            for include_raw in order:
                sample = measure(include_raw)
                groups["rgbAndRaw" if include_raw else "rgb"].append(sample)
                if not np.array_equal(rgb, ref_rgb):
                    delta = np.abs(rgb.astype(np.int16) - ref_rgb)
                    raise ValueError(f"{label}: {np.count_nonzero(delta)} changed RGB channels, max {delta.max()}")
        if not np.array_equal(raw_copy, host_raw):
            raise ValueError("Bayer readback differs")
        report["pairs"][label] = dict(inputSha=digest(raw_path), rgbSha=known[label], firstRgb=first,
                         samples=groups, summary={k:summarize(v) for k,v in groups.items()},
                         nativeMs=host_times, rawReadBytes=host_raw.nbytes, rgbReadBytes=rgb.nbytes,
                         uploadMs=(upload.profile.end-upload.profile.start)/1e6)
    (args.out / "replay.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("library", "fixtures", "reference", "out"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--gpu", required=True)
    p.add_argument("--repeats", type=int, default=12)
    replay(p.parse_args())
