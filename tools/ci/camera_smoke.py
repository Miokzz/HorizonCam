"""Public-UI camera smoke, exclusively for a disposable Android emulator.

No app hooks, seeded media, fake success, or private user/device data. See README.md.
"""
from __future__ import annotations

import argparse
import io
import json
import re
import subprocess
import threading
import time
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageChops, ImageOps, ImageStat
from media_checks import require, validate_geometry, validate_probe

PACKAGE = "com.miokzz.horizoncam.gl"
COMPONENT = f"{PACKAGE}/com.miokzz.horizoncam.MainActivity"


def assert_visible_scene(image):
    stats = ImageStat.Stat(image.convert("RGB"))
    require(max(stats.stddev) > 3 and max(stats.mean) > 15,
            "Camera image is blank/dark/unvarying; no usable emulated preview/frame")


class Smoke:
    def __init__(self, output):
        self.output = output
        output.mkdir(parents=True, exist_ok=True)
        self.report = {"device": "emulator", "status": "running", "captures": []}
        self.gravity = "0:9.81:0"
        self.stop_sensors = threading.Event()
        self.sensor_thread = None
        self.sequence = 0
        self.verified_emulator = False

    def command(self, *args, timeout=30, check=True, binary=False):
        result = subprocess.run(args, capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(f"Command failed: {args}: {result.stderr.decode(errors='replace')} {result.stdout.decode(errors='replace')}")
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def adb(self, *args, **kwargs):
        return self.command("adb", *args, **kwargs)

    def shell(self, *args, **kwargs):
        return self.adb("shell", *args, **kwargs)

    def snapshot(self, label):
        self.sequence += 1
        name = f"{self.sequence:02d}-{label}"
        png = self.adb("exec-out", "screencap", "-p", binary=True)
        (self.output / f"{name}.png").write_bytes(png)
        return Image.open(io.BytesIO(png)).convert("RGB")

    def hierarchy(self):
        # Keep each attempt bounded. A camera frame isn't a UI event; the 1Hz recording timer can be.
        for _ in range(3):
            try:
                self.shell("rm", "-f", "/sdcard/camera-smoke-ui.xml")
                self.shell("uiautomator", "dump", "--compressed", "/sdcard/camera-smoke-ui.xml", timeout=20)
                xml = self.adb("exec-out", "cat", "/sdcard/camera-smoke-ui.xml")
                root = ET.fromstring(xml)
                (self.output / "latest-ui.xml").write_text(xml, encoding="utf-8")
                return root
            except (RuntimeError, ET.ParseError, subprocess.TimeoutExpired):
                time.sleep(0.3)
        raise AssertionError("UIAutomator could not produce a fresh UI hierarchy")

    def find(self, root, resource=None, text=None):
        for node in root.iter("node"):
            # AGP may use applicationId or namespace in compiled resource names; match real suffix.
            if resource and not node.get("resource-id", "").endswith(":id/" + resource):
                continue
            if text is not None and node.get("text") != text:
                continue
            return node
        return None

    @staticmethod
    def bounds(node):
        coords = [int(x) for x in re.findall(r"\d+", node.get("bounds", ""))]
        require(len(coords) == 4 and coords[2] > coords[0] and coords[3] > coords[1], "UI target has invalid bounds")
        return tuple(coords)

    def wait(self, predicate, message, seconds=45):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            result = predicate()
            if result:
                return result
            time.sleep(0.5)
        raise AssertionError(message)

    def wait_node(self, resource, description=None, enabled=True):
        def poll():
            node = self.find(self.hierarchy(), resource=resource)
            return node if node is not None and (not enabled or node.get("enabled") == "true") and (
                description is None or node.get("content-desc") == description) else None
        return self.wait(poll, f"Missing enabled {resource} / {description}")

    def tap_node(self, node):
        require(node.get("enabled") == "true", f"Disabled UI target {node.attrib}")
        x0, y0, x1, y1 = self.bounds(node)
        self.shell("input", "tap", str((x0 + x1) // 2), str((y0 + y1) // 2))

    def tap(self, resource):
        self.tap_node(self.wait_node(resource))

    def set_stabilization(self, text):
        self.tap("lockButton")
        node = self.wait(lambda: self.find(self.hierarchy(), text=text), f"Missing stabilization menu {text}")
        self.tap_node(node)
        self.wait_node("recordButton", "Iniciar gravação")

    def rotate(self, portrait):
        self.gravity = "0:9.81:0" if portrait else "9.81:0:0"
        self.adb("emu", "sensor", "set", "acceleration", self.gravity)
        # The fullSensor activity follows actual emulated gravity, not a forced display size.
        self.wait(lambda: self.orientation_matches(portrait), f"Device UI did not rotate portrait={portrait}")
        time.sleep(1.5)  # Allow display listener/rebind and gravity filter to settle.

    def orientation_matches(self, portrait):
        png = self.adb("exec-out", "screencap", "-p", binary=True)
        width, height = Image.open(io.BytesIO(png)).size
        return (height > width) == portrait

    def media_paths(self, photo):
        folder = "/sdcard/Pictures/HorizonCam" if photo else "/sdcard/Movies/HorizonCam"
        result = self.shell("find", folder, "-type", "f", check=False)
        suffix = ".jpg" if photo else ".mp4"
        return {line.strip() for line in result.splitlines() if line.startswith(folder + "/") and line.lower().endswith(suffix)}

    def new_media(self, photo, before):
        def poll():
            fresh = self.media_paths(photo) - before
            return next(iter(fresh)) if len(fresh) == 1 else None
        path = self.wait(poll, "Capture did not produce exactly one new real media file", seconds=60)
        return path

    def pull(self, remote, label):
        path = self.output / (label + Path(remote).suffix)
        self.adb("pull", remote, str(path))
        require(path.stat().st_size > 1000, f"Empty capture {path}")
        return path

    def thumbnail(self, label):
        node = self.wait_node("galleryThumbnail")
        require(node.get("clickable") == "true", "Thumbnail is not clickable")
        image = self.snapshot(label)
        return image.crop(self.bounds(node)).resize((64, 64))

    def check_thumbnail(self, placeholder, label):
        def changed():
            current = self.thumbnail(label)
            return max(ImageStat.Stat(ImageChops.difference(placeholder, current)).mean) > 5
        self.wait(changed, "Saved capture never replaced the gallery placeholder", seconds=20)

    def check_preview(self, label, locked=False):
        node = self.wait_node("previewView" if locked else "photoPreview", enabled=False)
        image = self.snapshot(label + "-preview")
        crop = image.crop(self.bounds(node))
        # Exclude scrims, status/zoom controls and crop borders when checking real camera pixels.
        width, height = crop.size
        crop = crop.crop((width // 4, height // 3, width * 3 // 4, height * 2 // 3))
        assert_visible_scene(crop)
        crop.save(self.output / (label + "-preview-center.png"))

    def capture_photo(self, label, portrait):
        self.tap("modePhoto")
        self.rotate(portrait)
        self.wait_node("recordButton", "Tirar foto")
        self.check_preview(label)
        before = self.media_paths(True)
        self.tap("recordButton")
        remote = self.new_media(True, before)
        self.wait_node("recordButton", "Tirar foto")
        path = self.pull(remote, label)
        with Image.open(path) as image:
            require(image.format == "JPEG", "Photo isn't a real JPEG")
            encoded = image.size
            orientation = image.getexif().get(274, 1)
            displayed = ImageOps.exif_transpose(image)
            validate_geometry(*displayed.size, portrait, 4 / 3)
            assert_visible_scene(displayed)
            self.report["captures"].append({"name": label, "kind": "JPEG", "encoded": encoded,
                "displayed": displayed.size, "exif_orientation": orientation})
        self.snapshot(label + "-saved")

    def capture_video(self, label, portrait, locked=False):
        self.wait_node("recordButton", "Iniciar gravação")
        self.check_preview(label, locked)
        before = self.media_paths(False)
        self.tap("recordButton")
        root = self.hierarchy()
        stop = self.find(root, resource="recordButton")
        require(stop is not None and stop.get("content-desc") == "Parar gravação", "Video did not enter recording state")
        for resource in ("modePhoto", "modeVideo", "galleryThumbnail"):
            node = self.find(root, resource=resource)
            require(node is not None and node.get("enabled") == "false", f"{resource} remained active during recording")
        timer = self.find(root, resource="timerText")
        require(timer is not None, "Recording timer is absent")
        self.snapshot(label + "-recording")
        time.sleep(4)
        # Reuse known shutter bounds: no layout/orientation changes occur while recording.
        self.tap_node(stop)
        self.wait_node("recordButton", "Iniciar gravação")
        remote = self.new_media(False, before)
        path = self.pull(remote, label)
        probe = json.loads(self.command("ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)))
        (self.output / f"{label}-ffprobe.json").write_text(json.dumps(probe, indent=2), encoding="utf-8")
        result = validate_probe(probe, portrait, locked)
        frame_path = self.output / f"{label}-frame.png"
        self.command("ffmpeg", "-v", "error", "-ss", "0.5", "-i", str(path), "-frames:v", "1", "-y", str(frame_path))
        with Image.open(frame_path) as image:
            assert_visible_scene(image)
            validate_geometry(*image.size, portrait, 16 / 9)
        self.report["captures"].append({"name": label, "kind": "MP4", "locked": locked, **result})
        self.wait_node("galleryThumbnail")
        self.wait_node("modePhoto")
        self.snapshot(label + "-saved")

    def run(self, apk):
        serial = self.adb("get-serialno").strip()
        require(serial.startswith("emulator-"), "Refusing camera smoke on a non-emulator device")
        require(self.shell("getprop", "ro.kernel.qemu").strip() == "1", "QEMU emulator identity required")
        self.verified_emulator = True
        self.report["serial"] = serial
        self.report["api"] = self.shell("getprop", "ro.build.version.sdk").strip()
        require(self.report["api"] in ("35", "36"), "This harness targets Android API35/36")
        self.adb("logcat", "-c")
        self.adb("install", "-r", str(apk), timeout=120)
        self.shell("pm", "clear", PACKAGE)
        for permission in ("CAMERA", "RECORD_AUDIO"):
            self.shell("pm", "grant", PACKAGE, "android.permission." + permission)
        self.shell("settings", "put", "system", "accelerometer_rotation", "1")
        self.shell("input", "keyevent", "KEYCODE_WAKEUP")
        self.shell("wm", "dismiss-keyguard")
        sensors = self.adb("emu", "sensor", "status")
        (self.output / "emulator-sensors.txt").write_text(sensors, encoding="utf-8")
        require("acceleration" in sensors and "KO:" not in sensors, "Emulator acceleration sensor unavailable")
        self.adb("emu", "sensor", "set", "magnetic-field", "0:0:-50")
        self.adb("emu", "sensor", "set", "gyroscope", "0:0:0")
        self.adb("emu", "sensor", "set", "acceleration", self.gravity)
        def update_sensors():
            while not self.stop_sensors.wait(0.2):
                self.adb("emu", "sensor", "set", "acceleration", self.gravity, check=False)
        self.sensor_thread = threading.Thread(target=update_sensors, daemon=True)
        self.sensor_thread.start()
        self.shell("am", "start", "-W", "-n", COMPONENT)
        self.wait_node("recordButton")
        self.tap("modePhoto")
        self.rotate(True)
        self.wait_node("recordButton", "Tirar foto")
        placeholder = self.thumbnail("gallery-empty")
        self.capture_photo("photo-portrait", True)
        self.check_thumbnail(placeholder, "gallery-photo")
        self.capture_photo("photo-landscape", False)
        self.tap("modeVideo")
        self.set_stabilization("Desligada")
        self.rotate(True)
        self.capture_video("video-portrait", True)
        self.check_thumbnail(placeholder, "gallery-video")
        self.rotate(False)
        self.capture_video("video-landscape", False)
        self.rotate(True)
        self.set_stabilization("Horizon Lock · 360°")
        self.wait(lambda: (self.find(self.hierarchy(), resource="statusText").get("text") == "Horizon Lock · 360°"),
                  "Horizon Lock never obtained a fresh emulated gravity reference", seconds=45)
        self.capture_video("video-horizon-lock", False, locked=True)
        # Latest image must persist across a lifecycle restart, not just a temporary in-memory bitmap.
        self.shell("am", "force-stop", PACKAGE)
        self.shell("am", "start", "-W", "-n", COMPONENT)
        self.wait_node("recordButton")
        self.check_thumbnail(placeholder, "gallery-restored")
        self.report["status"] = "passed"

    def finish(self, error=None):
        self.stop_sensors.set()
        if self.sensor_thread:
            self.sensor_thread.join(timeout=5)
        if error:
            self.report["status"] = "failed"
            self.report["failure"] = str(error)
            self.report["traceback"] = traceback.format_exc()
        if not self.verified_emulator:
            (self.output / "report.json").write_text(json.dumps(self.report, indent=2), encoding="utf-8")
            return
        # Evidence collection is best effort; it must never turn a failed assertion into success.
        for filename, command in {
            "logcat.txt": ("logcat", "-d", "-v", "threadtime"),
            "camera-service.txt": ("shell", "dumpsys", "media.camera"),
            "activity.txt": ("shell", "dumpsys", "activity", "activities"),
            "sensors.txt": ("shell", "dumpsys", "sensorservice"),
            "display.txt": ("shell", "dumpsys", "display"),
        }.items():
            try:
                (self.output / filename).write_text(self.adb(*command, check=False), encoding="utf-8")
            except Exception as capture_error:
                self.report.setdefault("evidence_errors", []).append(str(capture_error))
        try:
            self.snapshot("final")
        except Exception:
            pass
        (self.output / "report.json").write_text(json.dumps(self.report, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("work/camera-smoke"))
    args = parser.parse_args()
    smoke = Smoke(args.output)
    try:
        smoke.run(args.apk)
    except Exception as error:
        smoke.finish(error)
        raise
    else:
        smoke.finish()
        print("PASS: two JPEGs and three MP4s captured and inspected from the Android emulator")


if __name__ == "__main__":
    main()
