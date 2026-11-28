"""Measure the existing field APK's Foto shutter through final MediaStore publication."""

import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

from PIL import Image

from raw_device_run import distribution

PACKAGE = "com.miokzz.horizoncam.gl.field"
COMPONENT = PACKAGE + "/com.miokzz.horizoncam.MainActivity"
PRIVATE = "no_backup/pending-photos"
UI_PATH = "/data/local/tmp/horizon-photo-perf.xml"
JPEG_NAME = re.compile(r"[0-9]+_-?[0-9]+_-?[0-9]+_[a-f0-9-]{36}\.jpg")


class Phone:
    def __init__(self, args):
        self.prefix = [str(args.adb)]
        if args.serial:
            self.prefix += ["-s", args.serial]

    def adb(self, *args, binary=False, check=True, timeout=30):
        result = subprocess.run([*self.prefix, *args], check=check, capture_output=True, timeout=timeout)
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")

    def shell(self, *args, **kwargs):
        return self.adb("shell", *args, **kwargs)

    def hierarchy(self):
        self.shell("uiautomator", "dump", "--compressed", UI_PATH, timeout=25)
        return ET.fromstring(self.adb("exec-out", "cat", UI_PATH))

    def node(self, name):
        for node in self.hierarchy().iter("node"):
            if node.get("resource-id", "").endswith(":id/" + name) and node.get("package") == PACKAGE:
                return node
        return None

    def ready(self, name):
        def enabled():
            node = self.node(name)
            return node if node is not None and node.get("enabled") == "true" else None
        return wait_for(enabled, f"Field camera did not expose enabled {name}", 40)

    def tap(self, node, right=False):
        x0, y0, x1, y1 = map(int, re.findall(r"\d+", node.get("bounds")))
        self.shell("input", "tap", str(x1 - 4 if right else (x0 + x1) // 2), str((y0 + y1) // 2))

    def open_photo(self, zoom):
        self.shell("am", "force-stop", PACKAGE)
        self.shell("am", "start", "-n", COMPONENT)
        self.tap(self.ready("modePhoto"))
        self.ready("recordButton")
        if zoom == 3:
            self.tap(self.ready("zoom3Button"))
        else:
            self.tap(self.ready("zoomSlider"), right=True)
        self.ready("recordButton")
        readout = self.node("zoomReadout")
        text = "" if readout is None else readout.get("text", "")
        if not re.fullmatch(rf"{zoom}(?:[,.]0+)?[^0-9.,]+", text.strip()):
            raise RuntimeError(f"Requested {zoom}Ã—, observed {text!r}")

    def log(self, pid):
        return self.adb("logcat", "-d", f"--pid={pid}", "-v", "brief", "-s", "HorizonAuto:I", "HorizonCam:I")

    def pending(self):
        return self.shell("run-as", PACKAGE, "ls", PRIVATE, check=False).splitlines()

    def status(self, pid):
        mem = self.shell("dumpsys", "meminfo", pid)
        thermal = self.shell("dumpsys", "thermalservice")
        info = self.shell("run-as", PACKAGE, "cat", f"/proc/{pid}/status", check=False)
        def match(pattern, text):
            value = re.search(pattern, text)
            return int(value[1]) if value else None
        return {
            "pssKiB": match(r"TOTAL PSS:\s*(\d+)", mem),
            "rssKiB": match(r"TOTAL RSS:\s*(\d+)", mem),
            "rssPeakKiB": match(r"(?m)^VmHWM:\s*(\d+)", info),
            "thermal": match(r"Thermal Status:\s*(\d+)", thermal),
            "batteryDeciC": match(r"(?m)^\s*temperature:\s*(\d+)", self.shell("dumpsys", "battery")),
        }


def wait_for(predicate, message, timeout):
    start = time.monotonic()
    while time.monotonic() - start < timeout:
        value = predicate()
        if value is not None:
            return value
        time.sleep(.25)
    raise RuntimeError(message)


def fields(line):
    result = {}
    for key, value in re.findall(r"(\w+)=([^\s]+)", line):
        if re.fullmatch(r"-?\d+", value):
            value = int(value)
        elif re.fullmatch(r"-?\d+\.\d+", value):
            value = float(value)
        result[key] = value
    return result


def capture(phone, zoom, directory, retain_burst):
    directory.mkdir()
    shutter = phone.ready("recordButton")
    pid = phone.shell("pidof", PACKAGE).split()[0]
    before_log = phone.log(pid)
    before_pending = set(phone.pending()) if retain_burst else set()
    before_m = phone.status(pid)
    phone.tap(shutter)
    if retain_burst:
        def triplet():
            paths = set(phone.pending()) - before_pending
            for name in sorted(paths):
                if JPEG_NAME.fullmatch(name) and all(name[:-4] + f".extra{i}" in paths for i in (1, 2)):
                    return name
            return None
        name = wait_for(triplet, "The scene did not queue a three-frame physical burst", 35)
        burst = directory / "burst"
        burst.mkdir()
        for remote, dest in [(name, "reference.jpg"), (name[:-4] + ".extra1", "extra1.jpg"),
                             (name[:-4] + ".extra2", "extra2.jpg")]:
            data = phone.adb("exec-out", "run-as", PACKAGE, "cat", f"{PRIVATE}/{remote}", binary=True)
            if not data.startswith(b"\xff\xd8"):
                raise RuntimeError(f"Invalid captured {dest}")
            (burst / dest).write_bytes(data)

    count = before_log.count(" published zoom=")

    def publication():
        log = phone.log(pid)
        pubs = [line for line in log.splitlines() if " published zoom=" in line]
        if len(pubs) == count + 1:
            return log, fields(pubs[-1])
        if len(pubs) > count + 1:
            raise RuntimeError("More than one publish event after one shutter")
        return None

    log, published = wait_for(publication, "Photo did not reach the gallery", 90)
    old = before_log.splitlines()
    render = [fields(line) for line in log.splitlines() if " render capture=" in line and line not in old]
    if len(render) != 1 or published["zoom"] != zoom:
        raise RuntimeError(f"Missing attributable render or unexpected zoom: {published}")
    name = str(render[0]["capture"])
    if not JPEG_NAME.fullmatch(name):
        raise RuntimeError("Unexpected rendered JPEG name")
    display = "HC_" + name
    capture_ms = name.split("_", 1)[0]
    query = phone.shell("content", "query", "--uri", "content://media/external_primary/images/media",
                        "--projection", "_id:_display_name:is_pending",
                        "--where", shlex.quote(f"_display_name LIKE 'HC_{capture_ms}_%'"))
    if len(re.findall(r"(?m)^Row:", query)) != 1 or "is_pending=0" not in query or display not in query:
        raise RuntimeError(f"Final photo is not one visible MediaStore row: {query}")
    photo = directory / "final.jpg"
    phone.adb("pull", f"/sdcard/Pictures/HorizonCam/{display}", str(photo))
    with Image.open(photo) as image:
        image.load()
        exif = image.getexif().get_ifd(34665)
        focal = exif.get(37386)
        if image.format != "JPEG" or focal is None or abs(float(focal) - 7.0) > 0.15:
            raise RuntimeError("Expected a JPEG from the telephoto lens")
        image_info = {"size": image.size, "orientation": image.getexif().get(274, 1), "focalMm": float(focal)}
    (directory / "camera-process.log").write_text(log, encoding="utf-8")
    report = {"render": render[0], "published": published, "before": before_m,
              "after": phone.status(pid), "mediaStoreRows": 1, "jpeg": image_info}
    (directory / "timing.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--zoom", type=int, choices=(3, 30), default=30)
    parser.add_argument("--warm", type=int, default=8)
    parser.add_argument("--cold", type=int, default=3)
    parser.add_argument("--retain-burst", action="store_true")
    args = parser.parse_args()
    if min(args.warm, args.cold) < 0 or args.warm + args.cold == 0:
        parser.error("Select at least one shot")
    if args.retain_burst and args.warm + args.cold != 1:
        parser.error("Use a dedicated shot for copying private inputs")
    phone = Phone(args)
    if phone.shell("getprop", "ro.product.model").strip() != "SM-S9210":
        parser.error("Expected the Galaxy S24 SM-S9210")
    args.out.mkdir(parents=True, exist_ok=False)
    summary = {}
    for stage, repeats in (("cold", args.cold), ("warm", args.warm)):
        rows = []
        for i in range(repeats):
            if i == 0 or stage == "cold":
                phone.open_photo(args.zoom)
                if stage == "warm" and not args.retain_burst:
                    capture(phone, args.zoom, args.out / "warm-up", False)
            row = capture(phone, args.zoom, args.out / f"{stage}-{i + 1:02}", args.retain_burst)
            rows.append(row)
        if rows:
            summary[stage] = {
                section: {metric: distribution([r[section][metric] for r in rows])
                          for metric in sorted(rows[0][section])
                          if metric.endswith("Ms") and all(isinstance(r[section].get(metric), (int, float)) for r in rows)}
                for section in ("render", "published")
            }
    (args.out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
