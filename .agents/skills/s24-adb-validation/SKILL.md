---
name: s24-adb-validation
description: Run careful Galaxy S24 ADB diagnosis, reproducible CameraX/Camera2 lens/zoom/video tests, neural photo benchmarking and GitHub Actions regression triage without touching personal data.
---
# Physical device and CI validation

1. Check `adb devices -l` and confirm intended SM-S9210. Avoid changing network debugging configuration or accessing private content outside approved test outputs.
2. Check phone is unlocked/ready; ask user only when genuinely needed. Never automate lockscreen bypass or use destructive shell/install options.
3. Reuse existing tests/logs and stable camera scene; note ambient light, firmware and lens exposure. Record APK hash/commit, total zoom, actual camera IDs, preview/JPEG size and timestamps.
4. Benchmark cold and warm inference, shutter-to-gallery, 3 repeats when feasible, and report median/range. Do not infer successful GPU from API request; inspect backend logs.
5. For camera failure '2' capture targeted logcat and lifecycle transitions, including OEM camera app handoff. Validate exactly one MediaStore final image per shot.
6. For CI run the smallest relevant test, then full build/lint/smoke; classify assertion mismatch vs functional camera regression. Don't hide CI failures.
7. Preserve original files, avoid resetting app data, do not alter user photos. Share concise evidence paths and what remains untested.
