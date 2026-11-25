"""Generate and score a synthetic Bayer ISP scene against aligned linear RGB truth.

The local GBRG phase starts with G/B on the first row and R/G on the second.
Samples represent normalized, black-subtracted sensor values. Noise variance is
`slope * signal + offset` before clipping. The scene has no camera-specific
color matrix, optical blur, gamma, or quantization.

For a degradation control, score the clean reference and then score a copy
whose channels were blurred identically. The latter should lose edge and texture
gain. For a false-color control, perturb only one channel in a neutral region.
"""

import math

import numpy as np


# The same Bayer origin is used for every patch and both image arrays.
_HEIGHT, _WIDTH = 384, 576
_PATCH_HEIGHT, _PATCH_WIDTH = 128, 144
_LUMA = (0.2126, 0.7152, 0.0722)


def synthetic_fixture(seed, slope, offset):
    """Return H×W×3 RGB truth, H×W noisy GBRG, and named `(y, x)` ROI slices.

    Both arrays are float32 in [0, 1]. Only the noise realization depends on seed.
    `slope` and `offset` are nonnegative in the normalized sensor domain.
    """
    if not all(math.isfinite(value) and value >= 0 for value in (slope, offset)):
        raise ValueError("slope and offset must be finite and nonnegative")

    truth = np.empty((_HEIGHT, _WIDTH, 3), dtype=np.float32)
    regions = {}

    def patch(name, row, column, rgb):
        y0 = row * _PATCH_HEIGHT
        x0 = column * _PATCH_WIDTH
        truth[y0:y0 + _PATCH_HEIGHT, x0:x0 + _PATCH_WIDTH] = rgb
        # Keep seams between neighboring patches out of localized scores.
        regions[name] = (
            slice(y0 + 10, y0 + _PATCH_HEIGHT - 10),
            slice(x0 + 10, x0 + _PATCH_WIDTH - 10),
        )

    y, x = np.mgrid[:_PATCH_HEIGHT, :_PATCH_WIDTH]
    patch("uniform_shadow", 0, 0, 0.04)
    patch("uniform_mid", 0, 1, 0.20)
    patch("uniform_bright", 0, 2, 0.65)
    highlight = np.minimum(0.66 + 0.37 * x / (_PATCH_WIDTH - 1), 0.985)
    patch("highlight", 0, 3, highlight[..., None])

    # About eight degrees off vertical, with a one-pixel transition to sample
    # multiple subpixel edge phases without hard raster stair steps.
    edge = 0.12 + 0.68 * np.clip(x - 0.14 * y - 62.0 + 0.5, 0, 1)
    patch("edge", 1, 0, edge[..., None])
    periods = np.take([12.0, 7.0, 4.0, 2.8], y // 32)
    lines = 0.43 + 0.22 * np.cos(2 * np.pi * (x + 0.21 * y) / periods)
    patch("lines", 1, 1, lines[..., None])
    texture = (
        0.45
        + 0.095 * np.sin(0.85 * x + 0.38 * y + 2 * np.sin(y / 19))
        + 0.065 * np.cos(0.47 * x - 0.62 * y + 1.2 * np.sin(x / 14))
        + 0.036 * np.sin(1.61 * x - 0.31 * y)
    )
    patch("texture", 1, 2, texture[..., None])
    tone = 0.012 + 0.86 * (x / (_PATCH_WIDTH - 1)) ** 2
    patch("tone", 1, 3, tone[..., None])

    patch("color_red", 2, 0, (0.65, 0.15, 0.09))
    patch("color_green", 2, 1, (0.13, 0.50, 0.20))
    patch("color_blue", 2, 2, (0.08, 0.21, 0.64))
    patch("color_warm", 2, 3, (0.55, 0.32, 0.19))

    raw = np.empty((_HEIGHT, _WIDTH), dtype=np.float32)
    raw[0::2, 0::2] = truth[0::2, 0::2, 1]
    raw[0::2, 1::2] = truth[0::2, 1::2, 2]
    raw[1::2, 0::2] = truth[1::2, 0::2, 0]
    raw[1::2, 1::2] = truth[1::2, 1::2, 1]

    rng = np.random.default_rng(seed)
    variance = slope * raw.astype(np.float64) + offset
    raw = np.clip(raw + rng.standard_normal(raw.shape) * np.sqrt(variance), 0, 1)
    return truth, raw.astype(np.float32), regions


def fixture_metrics(render_rgb_float32, truth_rgb_float32, regions):
    """Score aligned RGB in the same linear exposure, color space and pixel grid.

    `psnr_db` uses full RGB and a unit peak, with a 120 dB ceiling. Error metrics
    use units of normalized linear samples. Uniform noise is residual spatial
    RMS after removing each patch's per-channel bias. Color MAE compares patch
    means. Detail gain is the signed gradient projected onto the reference
    gradient (1 is ideal); gradient RMSE penalizes both lost and invented detail.
    False chroma is RMS opponent-channel error on neutral edge/line/texture ROIs.
    """
    render = np.asarray(render_rgb_float32, dtype=np.float64)
    truth = np.asarray(truth_rgb_float32, dtype=np.float64)
    if render.shape != truth.shape or truth.ndim != 3 or truth.shape[2] != 3:
        raise ValueError("render and reference must be aligned arrays of shape H×W×3")
    if not np.isfinite(render).all() or not np.isfinite(truth).all():
        raise ValueError("render and reference must contain finite linear RGB")

    def rms(values):
        return float(np.sqrt(np.mean(values ** 2)))

    residual = render - truth
    mse = float(np.mean(residual ** 2))
    metrics = {"psnr_db": -10.0 * math.log10(max(mse, 1e-12))}

    color_error = [residual[roi].mean(axis=(0, 1)) for name, roi in regions.items()
                   if name.startswith("color_")]
    metrics["color_patch_mae"] = float(np.mean(np.abs(color_error)))

    for tone in ("shadow", "mid", "bright"):
        error = residual[regions[f"uniform_{tone}"]]
        bias = error.mean(axis=(0, 1))
        metrics[f"uniform_{tone}_bias_mae"] = float(np.mean(np.abs(bias)))
        metrics[f"uniform_{tone}_noise_rms"] = rms(error - bias)

    for name in ("tone", "highlight"):
        metrics[f"{name}_mae"] = float(np.mean(np.abs(residual[regions[name]])))
    metrics["highlight_clipped_fraction"] = float(np.mean(render[regions["highlight"]] >= 1.0))

    for name in ("edge", "lines", "texture"):
        roi = regions[name]
        clean_luma = truth[roi] @ _LUMA
        rendered_luma = render[roi] @ _LUMA
        clean_gradient = np.concatenate([np.diff(clean_luma, axis=axis).ravel() for axis in (0, 1)])
        rendered_gradient = np.concatenate([np.diff(rendered_luma, axis=axis).ravel() for axis in (0, 1)])
        metrics[f"{name}_gain"] = float(np.sum(rendered_gradient * clean_gradient) / np.sum(clean_gradient ** 2))
        metrics[f"{name}_gradient_rmse"] = rms(rendered_gradient - clean_gradient)

    neutral_error = np.concatenate([residual[regions[name]].reshape(-1, 3)
                                    for name in ("edge", "lines", "texture")])
    metrics["false_chroma_rms"] = rms(neutral_error[:, (0, 2)] - neutral_error[:, 1:2])
    return metrics
