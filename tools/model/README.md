# Verified portable Real-ESRGAN conversion

This is the official `realesr-general-x4v3.pth` from
[xinntao/Real-ESRGAN v0.2.5.0](https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0),
converted without training, pruning or quantization. This general-photo blind-restoration prior
is different from the existing DIV2K ESRGAN asset. It estimates plausible detail; it cannot
recover arbitrary missing detail, authenticate faces/text, or provide 100× optical resolution.

`vendor/srvgg_arch.py` is the unmodified official tagged architecture source. The BSD 3-Clause
license is retained here and alongside the app asset. The build-time converter executes that
class without BasicSR's unrelated registry imports, loads the checkpoint using Torch's
`weights_only=True`, and requires its observed SHA-256 before using it.

## Asset contract

- File: `app/src/main/assets/ai/RealESRGAN-general-x4v3.tflite`, 4,884,868 bytes.
- SHA-256: `23c281028af47e692dc84fda1fdbc681c3c1038f6d0329c1a849b08c6ec6f11b`.
- Input: float32 `[1,50,50,3]`, RGB, nominal 0–255. Output: float32 `[1,200,200,3]`, same order/range.
- Normalize `/255` and de-normalize `*255` **inside** the graph. Do not repeat externally.
- Official predictions can overshoot 0–255. Retain raw predictions for parity; clamp when encoding pixels.
- Transpose convolution weights OIHW→HWIO; per-channel PReLU slopes share both spatial axes.
- Explicit DCR→CRD channel reorder precedes TensorFlow `depth_to_space`; nearest-neighbor residual is preserved.
- All serialized builtin operator versions are 1. No Flex/Select TF/custom ops, delegates or network calls are required.
  The observed test interpreter used the CPU XNNPACK delegate; `DELEGATE` in its diagnostic listing is not a serialized custom op.

The exact weights hash, graph hash, versions and source URL are in the bundled `RealESRGAN_NOTICE.json`.
`ai/RestorationModel.kt` exposes only the verified asset name, byte count and checksum.

## Reproduction

Use an isolated Python 3.12 venv outside the checkout; the tested package versions are recorded
in `requirements-verified.txt`. Install the CPU Torch wheel from its official CPU index first,
then TensorFlow CPU and the remaining requirements. No Python dependencies ship in the app.

```sh
python3.12 -m venv ../restoration-venv
../restoration-venv/bin/python -m pip install torch==2.15.0+cpu --index-url https://download.pytorch.org/whl/cpu
../restoration-venv/bin/python -m pip install -r tools/model/requirements-verified.txt
curl -L https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth -o ../realesr-general-x4v3.pth
../restoration-venv/bin/python tools/model/convert_realesrgan.py --weights ../realesr-general-x4v3.pth --output app/src/main/assets/ai/RealESRGAN-general-x4v3.tflite --report ../conversion.json
../restoration-venv/bin/python tools/model/validate_realesrgan.py --weights ../realesr-general-x4v3.pth --model app/src/main/assets/ai/RealESRGAN-general-x4v3.tflite --baseline-model app/src/main/assets/ai/ESRGAN.tflite --output ../restoration-validation
../restoration-venv/bin/python tools/model/make_comparison.py ../restoration-validation
```

Windows uses `Scripts/python.exe` in place of `bin/python`. Converter output bytes may vary
with TensorFlow versions/platforms; revalidate and update the integration checksum deliberately.

## Checks actually performed

Official Torch versus converted TFLite, three deterministic fixtures: independent RGB spatial
ramps, RGB impulses at different positions, and random nonconstant pixels. Worst absolute
channel error: **0.00403 on the 0–255 scale**. Odd native crop tiling at 1×1, 29×17, 37×51,
65×63: correct 4× dimensions, finite pixels, positive normalized coverage everywhere; worst
error against identically tiled official Torch: **0.00120**. Validation asserts parity tolerance
0.05, so channel swaps, PixelShuffle ordering, PReLU and normalization errors fail the script.

These tests used reflect-101 padding, 50px input, 32px core, 9px halo and stride 24. A fixed
50px graph has less context than this 34-convolution model's full receptive field. It differs
from official full-image inference: observed mean absolute difference was 1.26–1.30/255 on
the two crops. Overlap prevents uncovered edges; it does not prove absence of subtle seams.

## Measured same-size restoration

Two NASA public-domain astronaut crops were resized to 65×65 as ground truth, then degraded
with seeded Gaussian noise and JPEG compression. All metrics below compare **65×65** images,
including the raw model's 4× output averaged back to 1×. No residual suppression, beauty
filter, strength mixing or chroma preservation was applied to that model baseline.

| Crop | Degraded PSNR / SSIM | Previous ESRGAN area 1× | Real-ESRGAN area 1× |
|---|---|---|---|
| Face, JPEG25 + σ12 | 26.763 / .8849 | 26.403 / .8785 | 26.709 / .9229 |
| Detail, JPEG40 + σ7 | 28.473 / .8546 | 28.128 / .8468 | 28.660 / .8882 |

The first crop loses 0.054dB PSNR against degraded pixels while improving SSIM. Simple bicubic
or Gaussian smoothing achieved higher PSNR on these two crops; the model is not a universal
fidelity winner. Lanczos downsampling also lost PSNR on both crops. Actual measurements,
including those losses, are in `validation_report.json`; scripts save all baseline/result PNGs.
Observed 65×65 tiled float32 inference was approximately 0.40s on this Windows development CPU,
not a phone benchmark. Two synthetic crops do not establish general photo quality or parity
with any phone maker's proprietary restoration pipeline.
