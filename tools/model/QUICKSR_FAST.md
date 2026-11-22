# QuickSRNetSmall: verified lightweight Fast candidate

This adds a real converted pretrained **classical super-resolution** model, not a smaller blind-restoration model. QuickSRNetSmall is appropriate for a distinct Fast, low-degradation enlargement lane. It is **not trained for general blur/noise removal**. The controlled results below do not justify replacing x4v3 for strongly degraded input or labeling a native-1× SR/downsample pass as fidelity improvement.

## Artifact and Kotlin contract

- Asset: `ai/QuickSRNet-small-x4-128.tflite`, **136,560 bytes**, **33,264 parameters**, four 3×3 convolutions with 32 feature channels, clipped activations,4×PixelShuffle.
- SHA256: `523609151d17484a0bfef7d60a2e9ddd762604138bf3067b9248aa436f831f3f`.
- One FLOAT32 NHWC RGB input `[1,128,128,3]` nominal0..255; one FLOAT32 NHWC RGB output `[1,512,512,3]`0..255. `/255` and `*255` are **inside the graph**, so Kotlin must not repeat normalization. PixelShuffle channel order is folded into the final convolution weights; no Gather remains.
- A 4-pixel halo covers the complete four-convolution receptive-field radius. Usable core 120; overlap 8/stride 112 is tested. Reflect101 external padding, crop 16 output pixels on each edge, blend positive feather weights. Preserve global coordinates and exact 4× output before any separately labeled final resampling.
- Serialized builtin ops: CONV_2D, RELU, MINIMUM, MUL, DEPTH_TO_SPACE. No Flex/custom operators. CPU XNNPACK was actually exercised. GPU execution is **not yet measured or verified** here; operator availability alone is not proof of full GPU delegation.
- MAC estimate per128px tile: `128²×(3×32×9 + 2×32×32×9 + 32×48×9)` = **542,638,080 MACs**. Existing x4v3 has approximately 1.2M parameters and 34 convolutions; large context plus32 vs 120 usable core changes whole-image cost substantially. Do not infer phone timing from desktop timings.

## Exact upstream provenance and license

[Qualcomm's official AIMET model card](https://github.com/quic/aimet-model-zoo/blob/1bd2bf5b17cdda9251437c444009b29e1a25054b/aimet_zoo_torch/quicksrnet/model/model_cards/quicksrnet_small_4x_w8a8.json) supplies the float32 [4× checkpoint](https://github.com/quic/aimet-model-zoo/releases/download/phase_2_january_artifacts/quicksrnet_small_4x_checkpoint_float32.pth.tar). Weight file 407,197 bytes, SHA256 `e7666018b6aa9590f95b932c8ceb1869e976d93a2a632cf73484e27cc2768978`. The card declares DIV2K training. The [official architecture](https://github.com/quic/aimet-model-zoo/blob/1bd2bf5b17cdda9251437c444009b29e1a25054b/aimet_zoo_torch/quicksrnet/model/models.py) defines Small as 32 channels and 2 intermediate layers. The [official model repository card](https://huggingface.co/qualcomm/QuickSRNetSmall) declares BSD-3-Clause; the [AIMET license](https://github.com/quic/aimet-model-zoo/blob/1bd2bf5b17cdda9251437c444009b29e1a25054b/LICENSE.md) and its upstream NOTICE are bundled as `QuickSRNet_LICENSE.txt` and `QuickSRNet_NOTICE.txt`. `QuickSRNet_NOTICE.json` records pinned source commit and conversion facts. The upstream repository has no AUTHORS file at the inspected commit despite that reference in its license; the unmodified license/NOTICE retain the supplied attributions, including Qualcomm 2022 source copyright.

No training, pruning or quantization was performed. The converter reproduces the public architecture and loads all checkpoint tensors strictly. The original checkpoint also contains optimizer objects; its exact pinned official hash is required before its explicitly documented trusted Torch load. Never use that code path for arbitrary untrusted checkpoints.

Torch versus TFLite max absolute error on random RGB and spatial color ramps: **0.000458/255**. Complete-context tiled output matches full official Torch to within 8-bit rounding (`<0.501/255`) away from the intentional mirrored image boundary. Actual inference tests cover 157×173 seams,1×1,17×29 and127×131 inputs. Both model tests passed.

## Controlled comparison (same field and sampling)

Fixtures: scikit-image grass (vegetation), brick (architecture texture), Chelsea cat (fur), NASA astronaut face. These are small controlled natural-image tests, not a broad camera benchmark. Exact crops/resampling/seeds are in `benchmark_fast_sr.py`. No user photograph is checked in. RGB PSNR/SSIM and pretrained **LPIPS 0.1.4 AlexNet** are computed against the same 192×192 reference after 48×48 input→4×output. Lower LPIPS is better; higher PSNR/SSIM is better. No border shaving or post-strength/chroma blending.

| Degradation (4-fixture mean) | Bicubic PSNR / SSIM / LPIPS | QuickSR PSNR / SSIM / LPIPS | x4v3-50 PSNR / SSIM / LPIPS |
|---|---|---|---|
| Clean bicubic downsample |23.8406/.6039/.4805|24.8384/.6770/.3610|21.9368/.5690/.3631|
| Mild: noiseσ3,blurσ.35,JPEG75 |22.7683/.5157/.5942|22.9135/.5296/.5015|21.4375/.4983/.4040|
| Strong: noiseσ10,blurσ.8,JPEG35 |21.0299/.3922/.7932|21.0262/.3872/.7210|20.8443/.3800/.6099|

QuickSR wins clean PSNR/SSIM and is comparable in average clean LPIPS; x4v3 has better perceptual distance with degradations. Under mild degradation, QuickSR preserves more measured pixel fidelity but does not match x4v3 perceptual restoration. Under strong degradation QuickSR scarcely improves bicubic pixel scores. This supports a **nonblind low-degradation Fast lane**, not a new general restoration claim.

At matched native 96×96 sampling with mild degradation, neither model beats the degraded input PSNR on these four fixtures. QuickSR loses 0.2–0.33dB; x4v3 loses 2.1–5.6dB. A native 1× Fast path should bypass inference or use a separately tested very conservative blend. Do not treat lower changes to the original as proof of better denoising.

Desktop TensorFlow 2.20 CPU XNNPACK, 2 threads, actual end-to-end 48px input assembly: QuickSR **7.9–11.2ms** versus original x4v3-50 **125.7–194.5ms**, about16× workflow speedup. On two private S24 native 128px crops QuickSR took57/60ms (4 calls), original x4v3 took1177/1121ms (25 calls). These are desktop inference timings, **not handset timings**. Source photographs and their side-by-side images/reports remain privately under `../s24-qa/fast-model-private/`; there is no clean ground truth for those photographs. Their camera-conservation scores are not restoration accuracy or identity scores.

Raw measured public-fixture metrics are in `fast_sr_benchmark.json`; conversion facts are in `quicksrnet_conversion.json`. Full public comparisons live in scratch `../fast-model-validation/controlled/`. This comparison uses the existing 50px x4v3, not the separately developed 96px Max model. Larger x4v3 context may improve seam/quality/cost tradeoffs and must be compared separately.

## Reproduce

Use the existing isolated Python 3.12 restoration environment. Additional measured dependencies: `lpips==0.1.4`, `torchvision==0.29.2+cpu`; Torch 2.15.0+cpu, TensorFlow CPU 2.20.0, numpy 2.5.5, Pillow 13.0.0, scikit-image 0.26.0. LPIPS downloads pretrained AlexNet once from the official PyTorch model host; inference afterward is local. It is a development-only metric and no AlexNet ships in the APK.

```powershell
../restoration-venv/Scripts/python.exe tools/model/convert_quicksrnet.py --weights ../fast-model-validation/quicksrnet_small_4x_checkpoint_float32.pth.tar --output app/src/main/assets/ai/QuickSRNet-small-x4-128.tflite --report ../fast-model-validation/conversion.json
../restoration-venv/Scripts/python.exe tools/model/benchmark_fast_sr.py --out ../fast-model-validation/controlled
../restoration-venv/Scripts/python.exe -m unittest discover -s tools/model -p test_quicksrnet.py
```

Optional `--private-input PATH --private-out PRIVATE_DIRECTORY` evaluates an original's 128px native center crop with conservation-only scores. Never direct that output into this repository. Converter output hashes may vary across TensorFlow releases; review parity, provenance and checksum deliberately before replacing the asset. No Android runtime or capture code was changed by this model work.
