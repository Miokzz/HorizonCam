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
- With `--auto-photo` (current CI): fresh install must default to Foto. Each shot
  has up to six minutes to publish exactly one final JPEG with processing
  provenance. Its EXIF dimensions, frame count and neural attribution must match
  the worker log for that capture. A native result has zero neural tiles.
  An unattributed camera fallback or a second public JPEG fails the smoke test.
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
- With `--ai` (enabled in CI): swipe to the public Horizon AI menu, select a native
  center patch of at most 256 × 256, run the actual Android ESRGAN model, require
  a new `HC_AI_` JPEG with 4× dimensions and matching model-generated-detail EXIF
  disclosure, switch Original/AI comparison, and verify the camera JPEG is unchanged.
  Progress state is recorded when observed; a fast completion may precede the
  first XML dump. Missing output, inference errors and timeout still fail the run.
  Busy accessibility trees during tile progress are retried within the bounded
  inference wait; stale XML can never satisfy a successful comparison check.

`work/camera-smoke/report.json` records observed media metadata and pass/failure.
Artifacts include the actual five captures, ffprobe JSON, decoded MP4 frames,
emulator screenshots, last UI XML and camera/sensor/display/logcat diagnostics.
AI runs also include the native before patch, restored JPEG and comparison screens.
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
