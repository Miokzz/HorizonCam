"""Assemble observed benchmark PNGs; no generated/simulated result replaces model output."""
import argparse
import json
from pathlib import Path
from PIL import Image, ImageDraw

parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
args = parser.parse_args()
report = json.loads((args.directory / "validation.json").read_text(encoding="utf-8"))
columns = [("Ground truth", "truth", None), ("Degraded JPEG+noise", "degraded", "degraded_1x"),
    ("Bicubic up/down 1x", "bicubic_1x", "bicubic_up_down_1x"),
    ("Previous ESRGAN 1x", "previous_esrgan_1x", "previous_esrgan_area_downsampled_1x"),
    ("Real-ESRGAN area 1x", "raw_model_area_1x", "raw_realesrgan_area_downsampled_1x")]
canvas = Image.new("RGB", (len(columns)*280, 700), "#15181c")
draw = ImageDraw.Draw(canvas)
draw.text((15,10), "Actual CPU inference; all evaluated at the SAME 65 x 65 pixels (display enlarged 4x, nearest)", fill="white")
for row, benchmark in enumerate(report["benchmarks"]):
    top = 45 + row*320
    for col, (title, suffix, metric_key) in enumerate(columns):
        left = col*280+10
        draw.text((left,top), title, fill="white")
        panel = Image.open(args.directory / f"{benchmark['name']}-{suffix}.png").convert("RGB")
        canvas.paste(panel.resize((260,260),Image.Resampling.NEAREST), (left,top+22))
        if metric_key:
            metric = benchmark[metric_key]
            draw.text((left,top+287), f"PSNR {metric['psnr_db']:.2f} dB / SSIM {metric['ssim']:.4f}", fill="white")
draw.text((15,685), "Two synthetic degradations of NASA public-domain astronaut crops; perceptual detail is estimated, not recovered ground truth.", fill="#c8cbd0")
canvas.save(args.directory / "same-scale-comparison.png")
