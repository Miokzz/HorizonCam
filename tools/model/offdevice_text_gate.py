"""Make a known-text chart and score its reference and reconstructions with the same OCR."""
import argparse
import hashlib
import json
from pathlib import Path
import re

from PIL import Image, ImageDraw, ImageFont


LINES = ("HORIZON LAB", "A17 B42 C83", "STONE 905", "PIXEL 260", "NORTH 74", "FINE 531")
MODEL_HASH = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"


def chart(file):
    image = Image.new("RGB", (256, 256), (237, 235, 231))
    draw = ImageDraw.Draw(image)
    for i, line in enumerate(LINES):
        draw.text((10, 7 + 40 * i), line, fill=(39, 38, 39), font=ImageFont.load_default(size=24 - i))
    with file.open("xb") as target:
        image.save(target, format="PNG")


def normalize(text):
    return re.sub(r"\s+", " ", text).strip().upper()


def edit_distance(a, b):
    previous = list(range(len(b) + 1))
    for i, char in enumerate(a, 1):
        current = [i]
        for j, other in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[-1] + 1, previous[j - 1] + (char != other)))
        previous = current
    return previous[-1]


def grade(directory, data, out):
    from tesserocr import PyTessBaseAPI, PSM, tesseract_version
    if hashlib.sha256((data / "eng.traineddata").read_bytes()).hexdigest() != MODEL_HASH:
        raise ValueError("English OCR weights differ from the experiment")
    expected = normalize(" ".join(LINES))
    scores = {}
    with PyTessBaseAPI(path=str(data.resolve()), lang="eng", psm=PSM.SINGLE_BLOCK) as engine:
        for file in sorted(directory.glob("*.png")):
            if file.stem in ("comparison", "input"):
                continue
            with Image.open(file) as img:
                if img.size != (256, 256):
                    raise ValueError("Unexpected OCR target size")
                engine.ClearAdaptiveClassifier()
                engine.SetImage(img.convert("L").resize((768, 768), Image.Resampling.LANCZOS))
                text = normalize(engine.GetUTF8Text())
            n = edit_distance(expected, text)
            scores[file.stem] = {"transcript":text, "edits":n, "cer":n/len(expected)}
    if "truth" not in scores or "bicubic" not in scores:
        raise ValueError("Need the original chart and the interpolation control")
    with out.open("x", encoding="utf-8") as f:
        json.dump(dict(reference=expected, chars=len(expected), ocr=tesseract_version().splitlines()[0],
                       traineddata=MODEL_HASH, results=scores), f, indent=2)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("chart").add_argument("file", type=Path)
    g = sub.add_parser("grade")
    for key in ("directory", "data", "out"):
        g.add_argument(key, type=Path)
    args = p.parse_args()
    if args.cmd == "chart":
        chart(args.file)
    else:
        grade(args.directory, args.data, args.out)
