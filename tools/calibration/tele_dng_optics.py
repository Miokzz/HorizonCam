"""Opt-in DNG lens correction on the full, unrotated Camera2 tele raster.

Stage 2 gains are for normalized, black-subtracted CFA samples before demosaicing.
Stage 3 warping is for linear RGB before crop and EXIF orientation. The mapping
follows Adobe DNG SDK dng_gain_map.cpp and dng_lens_correction.cpp. OpenCV bilinear
resampling differs from the SDK's bicubic kernel and is an experimental choice.
"""

import math
import struct
from dataclasses import dataclass

import cv2
import numpy as np


_LIST_TAGS = (51008, 51009, 51022)
_GAIN_MAP = 9
_WARP_RECTILINEAR = 1
_DNG_1_3 = 0x01030000
_GAIN_HEADER = struct.Struct(">4i6I4dI")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def _tag(page, key, default=None):
    entry = page.tags.get(key)
    return entry.value if entry is not None else default


@dataclass(frozen=True)
class _Opcode:
    stage: int
    code: int
    version: int
    flags: int
    data: memoryview

    @property
    def optional(self):
        return bool(self.flags & 1)

    def diagnostic(self):
        return {"opcodeList": self.stage, "opcodeId": self.code, "minDngVersion": f"{self.version:08x}",
                "flags": self.flags, "optional": self.optional, "bytes": len(self.data)}


def _read_list(stage, payload):
    raw = memoryview(payload)
    require(len(raw) >= 4, f"Truncated OpcodeList{stage}")
    count, = struct.unpack_from(">I", raw)
    require(count <= min(1024, (len(raw) - 4) // 16), f"Invalid OpcodeList{stage} count")
    position = 4
    for _ in range(count):
        require(position + 16 <= len(raw), f"Truncated OpcodeList{stage} header")
        code, version, flags, size = struct.unpack_from(">4I", raw, position)
        start = position + 16
        position = start + size
        require(position <= len(raw), f"Truncated OpcodeList{stage} payload")
        yield _Opcode(stage, code, version, flags, raw[start:position])
    require(position == len(raw), f"Trailing bytes in OpcodeList{stage}")


@dataclass(frozen=True)
class _Gain:
    phase: tuple[int, int]
    spacing: tuple[float, float]
    origin: tuple[float, float]
    samples: np.ndarray

    def _axis(self, axis, size):
        # SDK evaluates pixel centers in normalized full-raster coordinates.
        positions = (np.arange(self.phase[axis], size, 2) + 0.5) / size
        last = self.samples.shape[axis] - 1
        spacing = self.spacing[axis]
        index = np.clip(positions - self.origin[axis], 0.0, last * spacing) / spacing
        lower = index.astype(np.intp)
        return lower, np.minimum(lower + 1, last), (index - lower).astype(np.float32)

    def render(self, shape):
        low_x, high_x, weight_x = self._axis(1, shape[1])
        left = self.samples[:, low_x]
        columns = left + (self.samples[:, high_x] - left) * weight_x
        low_y, high_y, weight_y = self._axis(0, shape[0])
        above = columns[low_y]
        return above + (columns[high_y] - above) * weight_y[:, None]

    def diagnostic(self, cfa_pattern):
        row, column = self.phase
        return {"phaseRowColumn": self.phase, "color": cfa_pattern[2 * row + column],
                "mapShape": self.samples.shape, "spacingVerticalHorizontal": self.spacing,
                "originVerticalHorizontal": self.origin, "minimum": float(self.samples.min()),
                "maximum": float(self.samples.max())}


def _gain(op, shape):
    raw = op.data
    require(len(raw) >= _GAIN_HEADER.size, "Truncated GainMap header")
    (top, left, bottom, right, plane, planes, row_pitch, col_pitch, rows, columns,
     spacing_y, spacing_x, origin_y, origin_x, map_planes) = _GAIN_HEADER.unpack_from(raw)
    require(rows > 0 and columns > 0 and map_planes > 0, "Empty GainMap table")
    require(planes > 0 and row_pitch > 0 and col_pitch > 0, "Invalid GainMap plane or pitch")
    require(len(raw) == _GAIN_HEADER.size + 4 * rows * columns * map_planes, "Invalid GainMap byte count")
    require(all(math.isfinite(x) for x in (spacing_y, spacing_x, origin_y, origin_x)), "Nonfinite GainMap coordinates")
    require((rows == 1 or spacing_y > 0) and (columns == 1 or spacing_x > 0), "Invalid GainMap spacing")
    supported = (top in (0, 1) and left in (0, 1) and (bottom, right) == shape
                 and (plane, planes, row_pitch, col_pitch, map_planes) == (0, 1, 2, 2, 1))
    if not supported:
        return None
    values = np.frombuffer(raw, dtype=">f4", offset=_GAIN_HEADER.size).astype(np.float32).reshape(rows, columns)
    require(np.all(np.isfinite(values)) and np.all(values > 0), "Invalid GainMap entries")
    if np.max(values) > 16:
        return None
    values.setflags(write=False)
    return _Gain((top, left), (1.0 if rows == 1 else spacing_y, 1.0 if columns == 1 else spacing_x),
                 (0.0 if rows == 1 else origin_y, 0.0 if columns == 1 else origin_x), values)


@dataclass(frozen=True)
class _Warp:
    radial: tuple[float, float, float, float]
    tangential: tuple[float, float]
    center: tuple[float, float]

    @property
    def identity(self):
        return self.radial == (1, 0, 0, 0) and self.tangential == (0, 0)

    def coordinates(self, shape, x, y):
        height, width = shape
        cx, cy = (self.center[0] * width, self.center[1] * height)
        radius = math.hypot(max(cx, width - cx), max(cy, height - cy))
        dx, dy = (x[None, :] - cx) / radius, (y[:, None] - cy) / radius
        r2 = np.minimum(dx * dx + dy * dy, 1.0)
        k0, k1, k2, k3 = self.radial
        ratio = k0 + r2 * (k1 + r2 * (k2 + r2 * k3))
        p0, p1 = self.tangential
        cross = 2 * dx * dy
        source_x = cx + radius * (dx * ratio + p1 * (r2 + 2 * dx * dx) + p0 * cross)
        source_y = cy + radius * (dy * ratio + p0 * (r2 + 2 * dy * dy) + p1 * cross)
        return source_x, source_y

    def check(self, shape):
        k0, k1, k2, k3 = self.radial
        # Reject out-of-domain coefficients before computing any coordinates.
        if sum(map(abs, (k0 - 1, k1, k2, k3))) > 0.15 or sum(map(abs, self.tangential)) > 0.025:
            return False, {"geometryCheck": "Coefficients exceed experiment limits"}
        height, width = shape
        x = np.linspace(0, width - 1, min(33, width))
        y = np.linspace(0, height - 1, min(33, height))
        mapped_x, mapped_y = self.coordinates(shape, x, y)
        dx_dy, dx_dx = np.gradient(mapped_x, y, x)
        dy_dy, dy_dx = np.gradient(mapped_y, y, x)
        determinant = dx_dx * dy_dy - dx_dy * dy_dx
        displacement = np.hypot(mapped_x - x[None, :], mapped_y - y[:, None])
        outside = ((mapped_x < -1e-6) | (mapped_x > width - 1 + 1e-6)
                   | (mapped_y < -1e-6) | (mapped_y > height - 1 + 1e-6))
        # Bound how far the warp can move and deform the image. Limits below
        # deliberately admit mild experimental lens corrections only.
        supported = (np.min(determinant) >= 0.5 and np.max(determinant) <= 1.5
                     and np.max(displacement) <= 0.10 * math.hypot(height, width))
        return supported, {"sampleShape": displacement.shape, "maxDisplacementPx": float(np.max(displacement)),
                           "p95DisplacementPx": float(np.percentile(displacement, 95)),
                           "minimumJacobian": float(np.min(determinant)),
                           "maximumJacobian": float(np.max(determinant)),
                           "outsideRasterSampleFraction": float(np.mean(outside))}

    def render(self, rgb):
        if self.identity:
            return rgb
        height, width = rgb.shape[:2]
        output = np.empty_like(rgb)
        x = np.arange(width, dtype=np.float64)
        for top in range(0, height, 128):
            y = np.arange(top, min(top + 128, height), dtype=np.float64)
            map_x, map_y = self.coordinates((height, width), x, y)
            output[top:top + len(y)] = cv2.remap(rgb, map_x.astype(np.float32), map_y.astype(np.float32),
                                                 cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
        return output


def _warp(op):
    raw = op.data
    require(len(raw) >= 4, "Truncated WarpRectilinear header")
    planes, = struct.unpack_from(">I", raw)
    require(planes >= 1 and len(raw) == 4 + 16 + 48 * planes, "Invalid WarpRectilinear byte count or planes")
    terms = struct.unpack_from(f">{6 * planes + 2}d", raw, 4)
    require(all(math.isfinite(value) for value in terms), "Nonfinite WarpRectilinear parameters")
    require(all(0 <= value <= 1 for value in terms[-2:]), "Invalid WarpRectilinear center")
    if planes != 1:
        return None
    return _Warp(terms[:4], terms[4:6], terms[6:])


class Optics:
    def __init__(self, shape, cfa_pattern, gains, warp, warp_diagnostic, unsupported):
        self.shape = shape
        self.cfa_pattern = cfa_pattern
        self._gains = tuple(gains)
        self._warp = warp
        self._warp_diagnostic = warp_diagnostic
        self._unsupported = tuple(unsupported)

    def _check_applicable(self, shape):
        require(tuple(shape) == self.shape, "Optics require the full unrotated DNG raster")
        require(not any(not op["optional"] for op in self._unsupported), "Unsupported mandatory DNG opcode")

    def gain_field(self, shape):
        """Multipliers for full-raster, already black-subtracted CFA values."""
        self._check_applicable(shape)
        result = np.ones(self.shape, dtype=np.float32)
        for gain in self._gains:
            row, column = gain.phase
            result[row::2, column::2] = gain.render(self.shape)
        return result

    def warp(self, rgb):
        """Remap full-raster linear RGB, keeping dimensions, dtype and orientation."""
        require(rgb.ndim == 3 and rgb.shape[2] == 3, "Expected H×W×3 RGB")
        self._check_applicable(rgb.shape[:2])
        if self._warp is None:
            return rgb
        require(rgb.dtype in (np.uint8, np.uint16, np.float32, np.float64), "Unsupported RGB dtype")
        return self._warp.render(rgb)

    def diagnostic(self):
        return {"rasterShape": self.shape, "cfaPattern": self.cfa_pattern,
                "gainMaps": [gain.diagnostic(self.cfa_pattern) for gain in self._gains],
                "warpRectilinear": self._warp_diagnostic, "unsupportedOpcodes": list(self._unsupported)}


def read_optics(page):
    """Read opcodes from the full-active CFA IFD of a Camera2 tele DNG."""
    shape = (page.imagelength, page.imagewidth)
    require(2 <= min(shape) and max(shape) < 32767, "Unsupported DNG raster extent")
    require(page.photometric == 32803 and page.samplesperpixel == 1, "Expected single-plane Bayer CFA IFD")
    require(tuple(_tag(page, 50829, (0, 0, *shape))) == (0, 0, *shape), "Expected full raster ActiveArea")
    pattern = tuple(_tag(page, 33422, ()))
    require(tuple(_tag(page, 33421, ())) == (2, 2) and sorted(pattern) == [0, 1, 1, 2]
            and (pattern[0] == pattern[3] == 1 or pattern[1] == pattern[2] == 1), "Expected 2×2 Bayer phases")
    require(tuple(_tag(page, 50710, (0, 1, 2))) == (0, 1, 2), "Expected RGB CFAPlaneColor order")
    require(_tag(page, 50711, 1) == 1, "Unsupported CFALayout")
    pattern_string = "".join("RGB"[value] for value in pattern)
    scale = tuple(_tag(page, 50718, (1, 1, 1, 1)))
    require(len(scale) == 4 and all(value > 0 for value in scale), "Invalid DefaultScale")
    unit_scale = scale[0] == scale[1] and scale[2] == scale[3]
    gains, warps, unsupported = [], [], []
    for stage, tag in enumerate(_LIST_TAGS, 1):
        payload = _tag(page, tag)
        if payload is None:
            continue
        for op in _read_list(stage, payload):
            if op.version != _DNG_1_3 or op.flags & ~3:
                unsupported.append(op.diagnostic() | {"reason": "Unsupported version or flags"})
            elif op.code == _GAIN_MAP and stage == 2:
                gain = _gain(op, shape)
                if gain is None:
                    unsupported.append(op.diagnostic() | {"reason": "Unsupported GainMap area, phase or gain range"})
                else:
                    gains.append((op, gain))
            elif op.code == _WARP_RECTILINEAR and stage == 3:
                warp = _warp(op)
                if warp is None or not unit_scale:
                    unsupported.append(op.diagnostic() | {"reason": "Warp needs one coefficient plane and unit DefaultScale"})
                else:
                    warps.append((op, warp))
            else:
                unsupported.append(op.diagnostic() | {"reason": "Opcode or stage is outside this tele experiment"})

    phases = {gain.phase for _, gain in gains}
    skipped_gain = any(op["opcodeId"] == _GAIN_MAP and op["opcodeList"] == 2 for op in unsupported)
    if gains and (len(gains) != 4 or len(phases) != 4 or skipped_gain):
        unsupported.extend(op.diagnostic() | {"reason": "Gain maps need a complete, unique four-phase set"} for op, _ in gains)
        gains = []

    warp_diagnostic = {"status": "absent"}
    ready_warp = None
    skipped_warp = any(op["opcodeId"] == _WARP_RECTILINEAR and op["opcodeList"] == 3 for op in unsupported)
    if len(warps) == 1 and not skipped_warp:
        op, warp = warps[0]
        valid, geometry = warp.check(shape)
        warp_diagnostic = {"status": "identity" if warp.identity else "supported" if valid else "unsupported",
                           "radial": warp.radial, "tangential": warp.tangential, "centerXY": warp.center,
                           "resampling": "bilinear; replicate border", **geometry}
        if valid:
            ready_warp = warp
        else:
            unsupported.append(op.diagnostic() | {"reason": "Warp coefficients or geometry exceed experiment limits"})
    elif warps or skipped_warp:
        warp_diagnostic = {"status": "unsupported"}
        unsupported.extend(op.diagnostic() | {"reason": "Only one valid WarpRectilinear may be applied"} for op, _ in warps)
    return Optics(shape, pattern_string, [gain for _, gain in gains], ready_warp, warp_diagnostic, unsupported)
