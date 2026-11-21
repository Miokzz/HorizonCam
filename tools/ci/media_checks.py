"""Pure media assertions used by real virtual-device smoke and its regression tests."""
import math

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


