"""Host-only conditional 4x latent diffusion against equal-size bicubic and measured references."""
import argparse
import hashlib
import json
from pathlib import Path
import time

import numpy as np
from PIL import Image, ImageDraw
from skimage.metrics import structural_similarity
import torch


REVISION = "0b55ddf931a8e3a1b426b3a50ddcf325ff84f668"
FILES = {"model_index.json": "e11242c98f43fbe00b540d13099bc0d985524f399c9eb72e6c88fe371e6c2bba",
         "scheduler/scheduler_config.json": "4e7f8fc406951a0c6248e69a04d4f913f92c2ce40aafca301b06dd111f49ff9f",
         "unet/config.json": "ed247e46ef8887aeeeaede9f8e1c04bfa0ec227a15d87a62b95ce34d6aac1b2c",
         "unet/diffusion_pytorch_model.bin": "932989fbccc2aa77e143bb8e3f5db46b650c1a7bb0003b5d541d417e18c6f3b3",
         "vqvae/config.json": "d7f8c701978c486cc24c57784fe685d7a7083820f1db620e639901fc0ec01948",
         "vqvae/diffusion_pytorch_model.bin": "c58407fc41070ccb9ab63f4ddfb1d13a3e607881033541e41232e41c66b877c9"}


def sha(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def scores(proposal, reference, perceptual):
    a, b = [np.asarray(x, dtype=np.float32) / 255 for x in (proposal, reference)]
    mse = float(np.mean((a - b) ** 2))
    def tensor(x):
        return torch.from_numpy(x.transpose(2, 0, 1).copy())[None] * 2 - 1
    with torch.inference_mode():
        distance = float(perceptual(tensor(a), tensor(b)).item())
    return dict(psnrDb=float(-10 * np.log10(mse)) if mse else None,
                ssim=float(structural_similarity(a, b, channel_axis=2, data_range=1)), lpipsAlex=distance)


def centered(image, side, x, y):
    if min(image.size) < side or not (0 <= x <= 1 and 0 <= y <= 1):
        raise ValueError("Invalid crop for photo dimensions")
    left = int(round((image.width - side) * x))
    top = int(round((image.height - side) * y))
    return image.crop((left, top, left + side, top + side)), [left, top, side, side]


def experiment(args):
    import diffusers
    import lpips
    from diffusers.pipelines.latent_diffusion.pipeline_latent_diffusion_superresolution import LDMSuperResolutionPipeline
    if any(sha(args.model / name) != expected for name, expected in FILES.items()):
        raise ValueError("Expected the pinned FP32 weights and matching model configs")
    if not args.steps or any(s < 1 or s > 1000 for s in args.steps) or args.threads < 1:
        raise ValueError("Invalid step count or thread budget")
    args.out.mkdir(parents=True, exist_ok=False)
    torch.set_num_threads(args.threads)
    clock = time.perf_counter()
    pipe = LDMSuperResolutionPipeline.from_pretrained(str(args.model), local_files_only=True,
                    use_safetensors=False, low_cpu_mem_usage=False, torch_dtype=torch.float32).to("cpu")
    pipe.set_progress_bar_config(disable=True)
    init = time.perf_counter() - clock
    perceptual = lpips.LPIPS(net="alex", verbose=False).eval()
    report = dict(model="CompVis/ldm-super-resolution-4x-openimages", revision=REVISION, weights=FILES,
                  torch=str(torch.__version__), diffusers=diffusers.__version__, device="host CPU FP32",
                  threads=args.threads, modelLoadSeconds=init, scheduler=type(pipe.scheduler).__name__,
                  parameterCount=sum(p.numel() for mod in (pipe.unet, pipe.vqvae) for p in mod.parameters()), cases=[])
    for index, spec in enumerate(json.loads(args.cases.read_text(encoding="utf-8"))):
        path = (args.cases.parent / spec["path"]).resolve()
        if sha(path) != spec["sha256"] or spec["kind"] not in ("controlled-downsample", "field-input"):
            raise ValueError("Case provenance or evaluation kind is invalid")
        with Image.open(path) as source:
            crop, roi = centered(source.convert("RGB"), spec["side"], *spec["xy"])
        truth = crop if spec["kind"] == "controlled-downsample" else None
        small = crop.resize((crop.width // 4, crop.height // 4), Image.Resampling.BICUBIC) if truth else crop
        if small.width < 32 or small.width % 32:
            raise ValueError("Use a 32-pixel multiple to avoid an implicit pipeline resize")
        target_size = (small.width * 4, small.height * 4)
        base = small.resize(target_size, Image.Resampling.BICUBIC)
        directory = args.out / f"case-{index:02d}"
        directory.mkdir()
        small.save(directory / "input.png")
        base.save(directory / "bicubic.png")
        row = dict(id=spec["id"], sourceSha256=spec["sha256"], kind=spec["kind"], roi=roi,
                   inputSize=small.size, runs=[])
        if truth:
            truth.save(directory / "truth.png")
            row["bicubic"] = scores(base, truth, perceptual)
        panels = [("bicubic", base)] + ([("reference", truth)] if truth else [])
        for steps in args.steps:
            first = None
            for seed in args.seeds:
                start = time.perf_counter()
                with torch.inference_mode():
                    image = pipe(image=small, num_inference_steps=steps, eta=0.0,
                                 generator=torch.Generator(device="cpu").manual_seed(seed)).images[0]
                elapsed = time.perf_counter() - start
                if image.size != target_size:
                    raise ValueError("Unexpected generated dimensions")
                name = f"ldm-{steps}-{seed}.png"
                image.save(directory / name)
                entry = dict(steps=steps, seed=seed, seconds=elapsed, sha256=sha(directory / name),
                             inputCycleMae8=float(np.abs(np.asarray(image.resize(small.size, Image.Resampling.BICUBIC),
                                                     dtype=float) - np.asarray(small)).mean()))
                if truth:
                    entry["againstReference"] = scores(image, truth, perceptual)
                if first is None:
                    first = image
                    panels.append((f"{steps} DDIM", image))
                else:
                    entry["seedSensitivity"] = scores(image, first, perceptual)
                row["runs"].append(entry)
                print(row["id"], steps, seed, round(elapsed, 2), flush=True)
        sheet = Image.new("RGB", (len(panels) * base.width, base.height + 18), "white")
        draw = ImageDraw.Draw(sheet)
        for column, (title, panel) in enumerate(panels):
            sheet.paste(panel, (column * base.width, 18))
            draw.text((column * base.width + 2, 1), title, fill="black")
        sheet.save(directory / "comparison.png")
        report["cases"].append(row)
        (args.out / "report.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("model", "cases", "out"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--steps", type=int, nargs="+", default=[12, 50])
    p.add_argument("--seeds", type=int, nargs="+", default=[193, 817])
    p.add_argument("--threads", type=int, default=4)
    experiment(p.parse_args())
