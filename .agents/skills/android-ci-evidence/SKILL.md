---
name: android-ci-evidence
description: Fix HorizonCam Gradle, Android GitHub Actions, unit and emulator smoke tests, EXIF metadata, build/signing or APK evidence.
---

# Android CI / QA evidence
1. Identify failing workflow, run SHA, job and exact failing step; success from `assembleDebug` alone does not clear camera smoke.
2. Reproduce shortest failing test. For current code check `tools/ci/camera_smoke.py`, `tools/ci/media_checks.py`, `.github/workflows/*` and model EXIF image-description parser.
3. Add or update bounded regression tests; fix producer/consumer contract rather than weakening checks without justification.
4. Run targeted tests, then build/lint when change warrants. Treat emulator boot trouble separately from app assertions.
5. Protect debug signing consistency, artifact availability and rollback. Never publish/merge without owner permission.
6. Capture a concise evidence packet: command, exit status, model/device, file, measurement, known uncertainty.
References: https://developer.android.com/studio/test ; https://github.com/rcosteira79/android-skills (android-testing and gradle-build-performance).
