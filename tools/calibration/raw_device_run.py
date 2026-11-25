"""Load four private fixtures, collect isolated Android timing and delegate evidence."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import uuid


PACKAGE = "com.miokzz.rawbench"
MODES = ("cpu", "gpu", "gpu-fp16", "qnn", "int8")
LABELS = ("detail-a", "detail-b", "flat-a", "flat-b")
GRAPH_OPS = 4  # Audited CONV_2D nodes in both fixed benchmark graphs.


def adb(args, *command, **kwargs):
    prefix = [str(args.adb)]
    if args.serial:
        prefix.extend(("-s", args.serial))
    return subprocess.run([*prefix, *command], check=True, **kwargs)


def digest(source):
    with source.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def install(args):
    adb(args, "install", "-r", "--no-incremental", str(args.apk))
    adb(args, "shell", "run-as", PACKAGE, "mkdir", "-p", "files/bench-input")
    paths = {"model.tflite": args.model, "int8.tflite": args.int8}
    paths.update({f"{label}.rawbench": args.fixtures / f"{label}.rawbench" for label in LABELS})
    stage = f"/data/local/tmp/rawbench-{uuid.uuid4().hex}"
    adb(args, "shell", f"umask 077; mkdir {stage}")
    try:
        for name, source in paths.items():
            dest, temporary = f"files/bench-input/{name}", f"{stage}/{name}"
            adb(args, "push", str(source), temporary)
            adb(args, "shell", f"cat {temporary} | run-as {PACKAGE} sh -c 'cat > {dest}'")
            result = adb(args, "shell", "run-as", PACKAGE, "sha256sum", dest, capture_output=True, text=True)
            if result.stdout.split()[0] != digest(source):
                raise ValueError(f"Transport hash mismatch: {name}")
            adb(args, "shell", "rm", temporary)
            print(f"Verified {name}", flush=True)
    finally:
        adb(args, "shell", f"rm -f {' '.join(f'{stage}/{name}' for name in paths)}; rmdir {stage}")


def collect_one(args, mode, output, pairs, repeats, seconds, save):
    output.mkdir(parents=True, exist_ok=False)
    run = "raw-" + uuid.uuid4().hex
    adb(args, "shell", "am", "force-stop", PACKAGE)
    instrument = adb(args, "shell", "am", "instrument", "-w", "-r", "-e", "mode", mode,
                     "-e", "run", run, "-e", "pairs", ",".join(pairs), "-e", "repeats", str(repeats),
                     "-e", "energySeconds", str(seconds), "-e", "save", str(save).lower(),
                     f"{PACKAGE}/.RawBench", capture_output=True, text=True, timeout=360)
    (output / "instrumentation.txt").write_text(instrument.stdout + instrument.stderr, encoding="utf-8")
    match = re.search(r"(?m)^(?:INSTRUMENTATION_STATUS|INSTRUMENTATION_RESULT): pid=(\d+)\s*$", instrument.stdout)
    if match:
        result = adb(args, "logcat", "-d", f"--pid={match[1]}", "-v", "brief", capture_output=True)
        (output / "process.log").write_bytes(result.stdout)
    if not re.search(r"(?m)^INSTRUMENTATION_RESULT: bench=OK\s*$", instrument.stdout):
        raise RuntimeError(f"{mode} did not complete; see {output / 'instrumentation.txt'}")
    files = ["timing.json"]
    if save:
        files.extend(f"{label}.f32" for label in pairs)
        if "detail-a" in pairs:
            files.append("detail-a.rgb")
    for name in files:
        with (output / name).open("xb") as handle:
            adb(args, "exec-out", "run-as", PACKAGE, "cat", f"files/{run}/{name}", stdout=handle, timeout=180)
    return json.loads((output / "timing.json").read_text(encoding="utf-8"))


def distribution(values):
    ordered = sorted(values)
    if not ordered:
        raise ValueError("Cannot summarize empty measurements")
    at = (len(ordered) - 1) * .95
    index = int(at)
    p95 = ordered[index] + (ordered[min(index + 1, len(ordered) - 1)] - ordered[index]) * (at - index)
    return {"n": len(ordered), "p50": statistics.median(ordered), "p95": p95}


def battery_window(window):
    samples = window["samples"]
    seconds = (samples[-1]["timeNs"] - samples[0]["timeNs"]) / 1e9
    status = sorted({sample["thermalStatus"] for sample in samples})
    evidence = {"seconds": seconds, "frames": window["frames"], "n": len(samples),
                "thermalStatuses": status, "temperatureDeciC": [sample.get("temperatureDeciC") for sample in samples],
                "interactive": sorted({sample["interactive"] for sample in samples}),
                "endpointsEnergyNwh": [samples[0]["energyNwh"], samples[-1]["energyNwh"]],
                "endpointsChargeUah": [samples[0]["chargeUah"], samples[-1]["chargeUah"]]}
    valid = seconds > 0 and all(sample.get("plugged") == 0 for sample in samples)
    counts = [sample["currentUa"] for sample in samples if sample["currentUa"] is not None]
    voltages = [sample["voltageMv"] for sample in samples if sample.get("voltageMv", 0) > 0]
    start_charge, end_charge = evidence["endpointsChargeUah"]
    start_energy, end_energy = evidence["endpointsEnergyNwh"]
    joules = None
    source = None
    if valid and start_energy is not None and end_energy is not None and start_energy >= end_energy:
        joules = (start_energy - end_energy) * 3.6e-6
        source = "battery_energy_counter"
    elif valid and voltages and start_charge is not None and end_charge is not None and start_charge >= end_charge:
        joules = (start_charge - end_charge) * statistics.mean(voltages) * 3.6e-6
        source = "charge_counter_times_mean_voltage"
    # Keep CURRENT_NOW as observed. On some devices it is inconsistent with the
    # Android-documented microampere scale.
    evidence.update(totalSystemDischargeJ=joules, energySource=source,
                    grossWatts=joules / seconds if joules is not None else None,
                    currentNowReturned=distribution(counts) if counts else None)
    return evidence


def delegation(logs, mode):
    if mode in ("cpu", "int8"):
        return {"acceleratorNodes": 0, "cpuNodes": GRAPH_OPS,
                "xnnpackLogged": "XNNPACK delegate" in logs}
    pattern = (r"Replacing (\d+) out of (\d+) node\(s\) with delegate \(([^)]+)\) node,"
               r" yielding (\d+) partitions for subgraph 0")
    name = "qnn" if mode == "qnn" else "gpu"
    for delegated, total, backend, partitions in re.findall(pattern, logs, re.IGNORECASE):
        if name in backend.lower():
            return {"acceleratorNodes": int(delegated), "cpuNodes": int(total) - int(delegated),
                    "partitions": int(partitions), "backend": backend}
    if mode == "qnn":
        qnn = re.search(r"(HTP[^:\n]*): (\d+) nodes delegated out of (\d+) nodes with (\d+) partitions", logs)
        if qnn:
            return {"acceleratorNodes": int(qnn[2]), "cpuNodes": int(qnn[3]) - int(qnn[2]),
                    "partitions": int(qnn[4]), "backend": qnn[1]}
    return {"acceleratorNodes": None, "cpuNodes": None, "proof": "No runtime node-count evidence"}


def summarize(primary, cold):
    warm = primary["loops"][1:]
    sections = ("noiseNs", "packNs", "assemblyNs", "rawToRestoredNs", "gainNs", "ispNs", "totalNs")
    evidence = {"graphBytes": primary["graphBytes"], "graphSha256": primary["graphSha256"],
                "coldMs": {part: distribution([report[part] / 1e6 for report in cold])
                           for part in ("engineNs", "delegateNs", "interpreterNs", "allocateNs") if part in primary},
                "firstFrameMs": distribution([report["loops"][0]["totalNs"] / 1e6 for report in cold]),
                "firstTileMs": distribution([report["loops"][0]["inferenceNs"][0] / 1e6 for report in cold]),
                "warmMs": {part: distribution([loop[part] / 1e6 for loop in warm]) for part in sections},
                "peakRssKiB": primary["finalMemory"].get("peakRssKiB"),
                "maxSampledPssKiB": max([primary["finalMemory"]["pssKiB"],
                                         *[loop["memory"]["pssKiB"] for loop in primary["loops"]]])}
    evidence["warmMs"]["invokePerTile"] = distribution([part / 1e6 for loop in warm for part in loop["inferenceNs"]])
    evidence["warmMs"]["allInvokesPerFrame"] = distribution([sum(loop["inferenceNs"]) / 1e6 for loop in warm])
    for label in ("idleWindow", "loadWindow"):
        if label in primary:
            evidence[label] = battery_window(primary[label])
    return evidence


def summarize_folder(output, mode):
    runs = [output, *sorted(output.glob("cold-*"))]
    cold = [json.loads((folder / "timing.json").read_text(encoding="utf-8")) for folder in runs]
    evidence = summarize(cold[0], cold)
    plans = [delegation((folder / "process.log").read_text(encoding="utf-8", errors="replace"), mode)
             for folder in runs]
    evidence["delegation"] = plans[0]
    evidence["coldDelegation"] = plans
    return evidence


def collect(args):
    failures = []
    for mode in args.modes:
        output = args.out / mode
        try:
            collect_one(args, mode, output, args.pairs, args.repeats, args.energy_seconds, True)
            for index in range(1, args.cold):
                collect_one(args, mode, output / f"cold-{index + 1}", args.pairs[:1], 1, 0, False)
            summary = summarize_folder(output, mode)
            (output / "summary.json").write_text(json.dumps(summary, indent=2, allow_nan=False) + "\n", encoding="utf-8")
            print(mode, summary["warmMs"]["totalNs"], flush=True)
        except (RuntimeError, subprocess.SubprocessError) as error:
            failures.append(str(error))
            print(str(error), flush=True)
    if failures:
        raise SystemExit(2)


def report(args):
    evidence = {mode: summarize_folder(args.runs / mode, mode) for mode in args.modes}
    with args.output.open("x", encoding="utf-8") as handle:
        handle.write(json.dumps(evidence, indent=2, allow_nan=False) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--serial")
    sub = parser.add_subparsers(required=True)
    upload = sub.add_parser("install")
    for name in ("apk", "model", "int8", "fixtures"):
        upload.add_argument(f"--{name}", type=Path, required=True)
    upload.set_defaults(action=install)
    capture = sub.add_parser("collect")
    capture.add_argument("--out", type=Path, required=True)
    capture.add_argument("--modes", nargs="+", choices=MODES, default=list(MODES))
    capture.add_argument("--pairs", nargs="+", choices=LABELS, default=list(LABELS))
    capture.add_argument("--repeats", type=int, default=10)
    capture.add_argument("--energy-seconds", type=int, default=20)
    capture.add_argument("--cold", type=int, choices=(1, 2, 3, 4, 5), default=3)
    capture.set_defaults(action=collect)
    offline = sub.add_parser("report")
    offline.add_argument("--runs", type=Path, required=True)
    offline.add_argument("--output", type=Path, required=True)
    offline.add_argument("--modes", nargs="+", choices=MODES, default=list(MODES))
    offline.set_defaults(action=report)
    args = parser.parse_args()
    if args.action is not report and args.adb is None:
        parser.error("--adb is required for install and collect")
    args.action(args)
