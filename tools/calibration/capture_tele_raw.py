"""Capture and export one private, verified tele RAW/JPEG pair over ADB."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


PACKAGE = "com.miokzz.horizoncam.gl.field"
RUNNER = f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
TEST = "com.miokzz.horizoncam.TeleRawPairFieldTest#capturePairedTeleOutputs"
PAIR = r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
FILES = {"dng": "source.dng", "jpeg": "source.jpg"}


def adb_command(adb, serial, *args):
    return [adb, *(["-s", serial] if serial else []), *args]


def read(adb, serial, *args, timeout=15):
    return subprocess.check_output(adb_command(adb, serial, *args), timeout=timeout)


def device_ready(adb, serial):
    state = read(adb, serial, "get-state").decode().strip()
    model = read(adb, serial, "shell", "getprop", "ro.product.model").decode().strip()
    if state != "device" or model != "SM-S9210":
        raise ValueError(f"Expected connected Galaxy S24 SM-S9210, found {state}/{model}")
    policy = read(adb, serial, "shell", "dumpsys", "window", "policy").decode(errors="replace")
    if re.search(r"\b(?:showing|mKeyguardShowing|isStatusBarKeyguard|mShowingLockscreen)=true\b", policy):
        raise ValueError("Unlock the Galaxy S24 and prepare the test scene before capture")


def verified_pair(log):
    ids = re.findall(rf"(?m)^HC_RAW_PAIR privateCalibration=({PAIR})\s*$", log)
    if len(ids) != 1 or not re.search(r"(?m)^OK \(1 test\)\s*$", log):
        raise ValueError("Instrumentation did not confirm exactly one retained pair\n" + log)
    if not re.search(r"(?m)^HC_RAW_PAIR .*status=verified .*equalTimestamps=true\b", log):
        raise ValueError("The capture result and both buffers must confirm the same timestamp")
    return ids[0]


def check_manifest(manifest, pair_id):
    if (manifest.get("schemaVersion"), manifest.get("pairId"), manifest.get("deviceModel"),
            manifest.get("logicalCameraId"), manifest.get("physicalCameraId")) != (1, pair_id, "SM-S9210", "0", "6"):
        raise ValueError("Manifest does not identify the requested tele capture")
    timestamp = manifest["sensorTimestampNs"]
    if type(timestamp) is not int or timestamp <= 0:
        raise ValueError("Invalid sensor timestamp")
    for kind, filename in FILES.items():
        record = manifest[kind]
        if (record.get("filename") != filename or record.get("timestampNs") != timestamp or
                type(record.get("bytes")) is not int or record["bytes"] <= 0 or
                not re.fullmatch(r"[0-9a-f]{64}", record.get("sha256", ""))):
            raise ValueError(f"Invalid {kind} evidence in manifest")


def check_file(file, record):
    with file.open("rb") as source:
        sha = hashlib.file_digest(source, "sha256").hexdigest()
    if file.stat().st_size != record["bytes"] or sha != record["sha256"]:
        raise ValueError(f"Transferred {file.name} does not match the device manifest")


def capture(adb, serial, output):
    device_ready(adb, serial)
    log = read(adb, serial, "shell", "am", "instrument", "-w", "-e", "rawPair", "yes",
               "-e", "retainPair", "yes", "-e", "class", TEST, RUNNER, timeout=55).decode(errors="replace")
    pair_id = verified_pair(log)
    remote = f"cache/quality-pair-{pair_id}"
    export = ("exec-out", "run-as", PACKAGE, "cat")
    raw_manifest = read(adb, serial, *export, f"{remote}/manifest.json")
    manifest = json.loads(raw_manifest)
    check_manifest(manifest, pair_id)

    output.mkdir(parents=True, exist_ok=True)
    pair = output / f"pair-{pair_id}"
    pair.mkdir()
    (pair / "instrumentation.txt").write_text(log, encoding="utf-8")
    for kind, filename in FILES.items():
        staged = pair / f"{filename}.part"
        with staged.open("xb") as destination:
            subprocess.run(adb_command(adb, serial, *export, f"{remote}/{filename}"),
                           stdout=destination, check=True, timeout=45)
        check_file(staged, manifest[kind])
        staged.rename(pair / filename)
    pending_manifest = pair / "manifest.json.part"
    with pending_manifest.open("xb") as destination:
        destination.write(raw_manifest)
    pending_manifest.rename(pair / "manifest.json")
    return pair


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="ADB binary (default: adb on PATH)")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--out", required=True, type=Path, help="Private parent directory for the exported pair")
    args = parser.parse_args()
    try:
        pair = capture(args.adb, args.serial, args.out.resolve())
    except (KeyError, OSError, TypeError, ValueError, subprocess.SubprocessError) as error:
        print(f"Capture failed: {error}", file=sys.stderr)
        return 1
    print(pair)
    return 0


if __name__ == "__main__":
    sys.exit(main())
