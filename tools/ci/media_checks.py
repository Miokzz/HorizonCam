"""Pure media assertions used by real virtual-device smoke and its regression tests."""
import math
import re

def require(condition, message):
    if not condition:
        raise AssertionError(message)


def media_geometry(width, height, rotation):
    require(width > 0 and height > 0, "Missing or invalid encoded dimensions")
    require(math.isfinite(rotation) and abs(rotation / 90 - round(rotation / 90)) < 0.01,
            f"Unexpected non-cardinal display rotation {rotation}")
    return (height, width) if round(rotation) % 180 else (width, height)


def validate_geometry(width, height, portrait, aspect):
    require(min(width, height) >= 240, f"Unexpected tiny camera output: {width}x{height}")
    require((height > width) == portrait,
            f"Wrong displayed orientation: {width}x{height}, portrait expected={portrait}")
    actual = max(width, height) / min(width, height)
    require(abs(actual - aspect) < 0.035, f"Wrong displayed aspect: {actual:.4f}, expected {aspect:.4f}")


def validate_probe(probe, portrait, locked=False):
    streams = [s for s in probe.get("streams", []) if s.get("codec_type") == "video"]
    require(len(streams) == 1, "Expected exactly one encoded video stream")
    video = streams[0]
    rotation = float(video.get("tags", {}).get("rotate", 0))
    for side in video.get("side_data_list", []):
        if "rotation" in side:
            rotation = float(side["rotation"])
    width, height = int(video["width"]), int(video["height"])
    effective = media_geometry(width, height, rotation)
    validate_geometry(*effective, portrait=portrait, aspect=16 / 9)
    sar = video.get("sample_aspect_ratio", "1:1")
    require(sar in ("1:1", "N/A", None), f"Non-square video pixels: {sar}")
    duration = float(probe.get("format", {}).get("duration", video.get("duration", 0)))
    require(math.isfinite(duration) and 2 <= duration <= 45, f"Invalid recording duration {duration}")
    require(int(probe.get("format", {}).get("size", 0)) > 10_000, "MP4 is missing usable encoded data")
    if locked:
        require(width > height and round(rotation) % 180 == 0,
                "Horizon Lock must encode landscape pixels with landscape playback metadata")
    return {"encoded": [width, height], "displayed": list(effective),
            "rotation": rotation, "duration": duration, "codec": video.get("codec_name"), "sar": sar}


def validate_processing_dimensions(description, source_size, output_size):
    dimensions = re.search(r"input/output\s+(\d+)[×x](\d+)\s*(?:→|->)\s*(\d+)[×x](\d+)", description)
    require(dimensions is not None, "AI JPEG must describe actual input/output dimensions")
    require(tuple(map(int, dimensions.groups())) == (*source_size, *output_size),
            "AI JPEG description disagrees with actual input/output dimensions")


def auto_photo_kind(description):
    if not isinstance(description, str):
        return None
    if "Horizon AI 2" in description and "textura estimada" in description:
        return "neural"
    if "Horizon AI" in description or "textura estimada" in description:
        return None
    if "sem restauracao neural" in description:
        if "detalhe nativo" in description:
            return "native"
        if "detalhe multiframe" in description:
            return "fusion"
    return None


def validate_auto_photo(logs, capture, displayed_size, description):
    kind = auto_photo_kind(description)
    require(kind is not None, "Automatic JPEG is missing its processing provenance")
    pattern = (rf"\brender capture={re.escape(capture)} source=(\d+)x(\d+) output=(\d+)x(\d+)"
               r" [^\n]*?\btiles=(\d+) frames=(\d+) originalScan=(true|false)\b")
    renders = re.findall(pattern, logs)
    require(bool(renders), f"No worker render evidence for {capture}")
    source_w, source_h, out_w, out_h, tiles, frames, original_scan = renders[-1]
    source = (int(source_w), int(source_h))
    output = (int(out_w), int(out_h))
    tiles, frames = int(tiles), int(frames)
    require(output == displayed_size, "Worker dimensions differ from the saved JPEG")
    validate_processing_dimensions(description, source, output)
    require(re.search(rf"\bframes={frames}\b", description), "EXIF and worker frame counts differ")
    require(frames >= 1 and (tiles > 0) == (kind == "neural"),
            "EXIF neural attribution differs from performed inference")
    if kind != "neural":
        require(source == output and (frames > 1) == (kind == "fusion"),
                "Non-neural output attribution disagrees with worker measurements")
    require(original_scan == "false" or kind == "native", "Only native output can retain the original JPEG scan")
    return {"kind": kind, "tiles": tiles, "frames": frames, "original_scan": original_scan == "true"}


def validate_ai_output(width, height, patch_edge, description):
    require(0 < patch_edge <= 256, "AI smoke must use a bounded native patch")
    require((width, height) == (patch_edge * 4, patch_edge * 4),
            f"AI output {width}x{height} is not 4x native {patch_edge}x{patch_edge} patch")
    require(isinstance(description, str) and "pretrained ESRGAN" in description and
            "estimated details" in description, "AI JPEG is missing its model-generated-detail disclosure")
    validate_processing_dimensions(description, (patch_edge, patch_edge), (width, height))


