---
name: photographic-quality-gates
description: Review Horizon AI subjective and quantitative restoration quality for fur, vegetation, text/face safety, neural fidelity and ADB benchmarks.
---

# Photographic restoration quality gates
1. Use same input, crop, EXIF orientation, exposure and saved resolution for baseline-vs-candidate comparisons. A synthetic degraded sample is not proof of S24 performance.
2. Test three classes separately: stochastic textures (fur/leaves), structured geometry (buildings), sensitive identity/text (faces/signage).
3. Track luminance/color drift, edge halos, false lettering/geometry, smearing, seam artifacts, perceptual gain. Do not score just pixel count or sharpness.
4. Use PSNR/SSIM only where aligned ground-truth exists; supplement LPIPS/DISTS and blind visual QA when relevant. Document ground-truth limitations.
5. Distinguish cold/warm GPU, CPU fallback, inference-only, processing pipeline, shutter-to-gallery, memory and device temperature.
6. Require ONE final public JPEG per shutter; capture remains private until success/fallback.
7. Stop or revert model/strength changes that hallucinate text, change faces or destroy existing real details.
References: https://ai.google.dev/edge/litert ; https://developer.android.com/media/camera/camera2/multi-camera.
