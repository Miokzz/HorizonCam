"""Build and verify a scene-disjoint host corpus. Original images stay outside Git."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps
from skimage import data


# Split whole photographic scenes before any crop, degradation or noise draw.
SCENES = (
    ("coffee", "train", "objects", "Rachel Michetti", "CC0"),
    ("camera", "train", "person", "Lav Varshney", "CC0"),
    ("gravel", "train", "texture", "CC0Textures", "CC0"),
    ("rocket", "train", "architecture", "SpaceX", "public domain"),
    ("retina", "validation", "vessels", "Mikael Häggström", "CC0"),
    ("brick", "validation", "architecture", "CC0Textures", "CC0"),
    ("grass", "test", "vegetation", "linolafett", "CC0"),
    ("astronaut", "test", "face", "NASA", "public domain"),
    ("chelsea", "test", "fur", "Stefan van der Walt", "CC0"),
    ("text", "test", "text", "Wikimedia Commons, Corner.png", "public domain"),
)
DRAGONFLY_URL = "https://commons.wikimedia.org/wiki/File:Sympetrum_flaveolum_-_side_(aka).jpg"
DRAGONFLY_SHA = "642d49c4cd8c95ce63c686637c546f3126019bb37fb4b9b11fa954070d660fae"


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def read_rgb(path):
    with Image.open(path) as image:
        return np.asarray(ImageOps.exif_transpose(image).convert("RGB")).copy()


def raster_id(pixels):
    h = hashlib.sha256(str(pixels.shape).encode("ascii"))
    h.update(np.ascontiguousarray(pixels))
    return h.hexdigest()


def validate(path):
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("schema") != 1:
        raise ValueError("Unsupported corpus schema")
    names, groups, fingerprints, splits = set(), {}, {}, set()
    for row in manifest["scenes"]:
        name, split, group = (row[k] for k in ("id", "split", "group"))
        if not all(isinstance(row.get(k), str) and row[k].strip()
                   for k in ("id", "group", "source", "license", "author", "category")):
            raise ValueError("Every scene needs identity, attribution, rights and category")
        if (not name.isascii() or not name.replace("-", "").isalnum() or name in names
                or split not in {"train", "validation", "test"}):
            raise ValueError("Invalid scene identity or split")
        if group in groups and groups[group] != split:
            raise ValueError("Scene group leaked across splits")
        names.add(name)
        groups[group] = split
        splits.add(split)
        file = (path.parent / row["path"]).resolve()
        if not file.is_relative_to(path.parent.resolve()) or digest(file) != row["sha256"]:
            raise ValueError(f"Missing, out-of-corpus or modified source {name}")
        pixels = read_rgb(file)
        key = raster_id(pixels)
        if key in fingerprints:
            raise ValueError(f"Duplicate raster: {name}, {fingerprints[key]}")
        if key != row["rgbSha256"] or list(pixels.shape) != row["shape"]:
            raise ValueError(f"Decoded scene differs: {name}")
        fingerprints[key] = name
    if splits != {"train", "validation", "test"}:
        raise ValueError("Need three non-empty scene partitions")
    return manifest


def record(out, name, split, category, author, license_name, source, file):
    rgb = read_rgb(file)
    return dict(id=name, group=name, split=split, category=category, author=author,
                license=license_name, source=source, path=file.relative_to(out).as_posix(),
                sha256=digest(file), rgbSha256=raster_id(rgb), shape=list(rgb.shape))


def prepare(out, dragonfly=None):
    if dragonfly and digest(dragonfly) != DRAGONFLY_SHA:
        raise ValueError("Dragonfly original differs from the attributed photograph")
    out.mkdir(parents=True, exist_ok=False)
    entries = []
    for name, split, category, author, license_name in SCENES:
        pixels = getattr(data, name)()
        file = out / (name + ".png")
        Image.fromarray(pixels).save(file)
        source = "https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data." + name
        entries.append(record(out, name, split, category, author, license_name, source, file))
    if dragonfly:
        from shutil import copyfile
        file = out / "dragonfly.jpg"
        copyfile(dragonfly, file)
        entries.append(record(out, "dragonfly", "test", "insect", "André Karwath (Aka)",
                              "CC BY-SA 2.5", DRAGONFLY_URL, file))
    path = out / "corpus.json"
    path.write_text(json.dumps({"schema": 1, "scenes": entries}, indent=2) + "\n", encoding="utf-8")
    validate(path)
    return path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    create = subs.add_parser("prepare")
    create.add_argument("--out", type=Path, required=True)
    create.add_argument("--dragonfly", type=Path)
    subs.add_parser("check").add_argument("manifest", type=Path)
    args = parser.parse_args()
    if args.command == "prepare":
        print(prepare(args.out, args.dragonfly))
    else:
        print(f"{len(validate(args.manifest)['scenes'])} verified scenes")
