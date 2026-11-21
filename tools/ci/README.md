# Virtual-device camera smoke

`camera-smoke.yml` runs one API 35 x86_64 emulator on pushes to `phase-1/**`.
Manual dispatch can choose API 35 or 36. The Ubuntu 24.04 x64 runner settles udev,
repairs permissions on an existing `/dev/kvm` only, and verifies KVM with the
emulator's `-accel-check`. If unavailable, the emulator action explicitly uses
supported software CPU emulation; boot has a bounded 15-minute timeout. SwiftShader
renders GLES; the rear camera uses the emulator's generated scene. No webcam,
phone, app test hook, seeded output file, or personal media is used.

The harness installs the real debug APK, clears its emulator app data, grants
camera/microphone permissions and operates the app through UIAutomator XML IDs
and `adb input tap`. It refuses to operate or collect diagnostics unless both
an `emulator-*` serial and QEMU identity are present. Accelerometer/gyro/magnetic
field values provide a fresh, upright virtual gravity reference; gravity is
changed to exercise the actual fullSensor portrait/landscape layout.

Checks fail the job when the app cannot complete any required flow:

- Portrait and landscape JPEG capture: actual JPEG decode, EXIF-adjusted 3:4/4:3
  dimensions, nonblank image, shutter restored after save.
- Conventional video with stabilization explicitly **Off**, in both orientations:
  actual MP4 video stream, cardinal playback rotation, displayed 9:16/16:9,
  square pixels, usable encoded bytes and duration; a decoded frame must exist
  and contain a nonblank scene.
- Horizon Lock explicitly selected from portrait UI: fresh-gravity ready state,
  recording through the GL effect, landscape encoded pixels and playback,
  decodable nonblank frame.
- Recording UI: stop action, visible timer, disabled mode/gallery actions; after
  save these controls become available again.
- Gallery thumbnail: real capture replaces the empty placeholder, and the latest
  thumbnail is restored after force-stop/relaunch. Viewer availability is outside
  this test because system images don't consistently bundle a media viewer.

`work/camera-smoke/report.json` records observed media metadata and pass/failure.
Artifacts include the actual five captures, ffprobe JSON, decoded MP4 frames,
emulator screenshots, last UI XML and camera/sensor/display/logcat diagnostics.
They are uploaded even on failure and retained for seven days. There is no
automatic retry of a failed capture, skipped Horizon Lock path, or fabricated
success when media is missing. UI readiness polling is bounded.

This proves basic virtual-device integration. It does **not** verify physical
lens availability, S24 HAL behavior, optical switching, real sensor timing,
real-world horizon quality, or absence of subtle visual stretching. Those need
the physical-device protocol. This CI has to run before claiming an emulator
result; local oracle tests validate only the media assertions.

Local equivalent, with an already running disposable API35/36 emulator:

```sh
python3 -m unittest discover -s tools/ci -p 'test_*.py' -v
python3 tools/ci/camera_smoke.py --apk app/build/outputs/apk/debug/app-debug.apk
```

Requires Android `adb`, Python 3 with Pillow, `ffmpeg` and `ffprobe`. The workflow
uses the Ubuntu system Python so `python3-pil` is on its import path.
