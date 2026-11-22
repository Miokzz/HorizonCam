---
name: horizon-ai-evaluation
description: Test and optimize Horizon AI 2.0 Real-ESRGAN/LiteRT restoration quality, GPU accuracy, FP16 tile inference, speed, text/face safety and single final JPEG output on Galaxy S24.
---
# Horizon AI evaluation

1. Recover local work first; compare local models and experiments against published branch without overwriting anything.
2. Inspect actual model checkpoint/license, dimensions, tile count/context, blending/clamping and backend selection. Verify trained inference, not only bicubic resize.
3. Use identical degraded inputs for baseline and candidate: fur, plants, architecture, signage and faces. Prefer real phone captures across 1x, 3x, 10x and 30x after synthetic tests.
4. Protect text/identity/geometry; avoid claiming invented details were recovered. Assess A/B at native output scale with measurable artifacts and, where appropriate, full-reference/perceptual metrics.
5. Measure inference milliseconds separately from shutter-to-gallery; backend, warm/cold latency, memory, thermals and quality thresholds. Verify GPU numerics against CPU reference before activating GPU broadly.
6. One shutter = one public final JPEG. Source stays private until enhanced result or a single original fallback is published. Check MediaStore IS_PENDING/EXIF, recovery/retry and exactly-once behavior.
7. Don't increase 'strength' blindly; reason about scene-aware restoration. Preserve actual crop/zoom.
