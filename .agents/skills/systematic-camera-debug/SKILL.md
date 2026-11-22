---
name: systematic-camera-debug
description: HorizonCam reproducible bugs in Camera2/CameraX, zoom, AF, OpenGL, GPU, CI or unexpected output. Use when diagnosing a failure or regression.
---

# Systematic bug fixing
**Evidence before change:** determine exact expected/actual behavior, steps, device/build/version and narrow log lines.
1. Trace a failing value end-to-end (gesture -> zoom router -> bind -> CameraControl -> preview -> still crop -> EXIF) or the relevant other path.
2. Compare known-working baseline and recent diff. Form one precise hypothesis and change one variable to test.
3. Add a regression test before the permanent fix when possible; avoid a pile of speculative patches.
4. Verify against independent evidence: frame content/FOV, output metadata, model benchmark, specific CI step or ADB capture.
5. Mark what is still unverified; if three attempts fail, reconsider architecture and ask Astra for coordination.
6. Do not run duplicate full builds unless code or environment changed.
Method adapted as original concise workflow; inspiration: https://github.com/obra/superpowers (systematic-debugging and verification-before-completion).
