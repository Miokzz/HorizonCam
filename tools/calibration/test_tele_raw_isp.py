import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace

import numpy as np
from PIL import Image

from tele_raw_isp import (bilinear, compare_display, denoise_mosaic, dng_color_matrix, flat_noise_repeat, flatness, malvar, measured_noise_scale,
                          phases, render, save_display, synthetic_comparison, XYZ_D50)


class TeleRawIspTest(unittest.TestCase):
    def test_demosaic_preserves_measured_samples_and_constant_color(self):
        shape = (12, 18)
        color = np.array([.17, .35, .62], np.float32)
        actual = np.empty(shape, np.float32)
        for _, _, channel, region in phases():
            actual[region] = color[channel]
        for method in (bilinear, malvar):
            result = method(actual)
            np.testing.assert_allclose(result, np.broadcast_to(color, result.shape), atol=2e-7)
            noisy = actual + np.random.default_rng(25).normal(0, .02, shape).astype(np.float32)
            result = method(noisy)
            for _, _, channel, region in phases():
                np.testing.assert_array_equal(result[region + (channel,)], noisy[region])

    def test_denoise_preserves_saturated_sites_and_separates_green_phases(self):
        raw = np.full((20, 20), .20, np.float32)
        raw[1::2, 1::2] = .4
        raw[4, 4] = 1.0
        denoised = denoise_mosaic(raw, np.array([[.001, .000005]] * 3, np.float32))
        self.assertEqual(denoised[4, 4], 1.0)
        np.testing.assert_allclose(denoised[1::2, 1::2], .4)
        self.assertEqual(raw[4, 4], 1.0)

    def test_measured_noise_calibration_keeps_textures_and_reduces_flat_noise(self):
        rows = synthetic_comparison(np.array([[.00108, 5.4e-6]] * 3, np.float32))
        for seed in {row["seed"] for row in rows}:
            group = {row["variant"]: row["metrics"] for row in rows if row["seed"] == seed}
            before, after = group["malvar"], group["malvar-noise"]
            self.assertLess(after["uniform_mid_noise_rms"], before["uniform_mid_noise_rms"] / 2)
            self.assertLess(abs(after["texture_gain"] - 1), .07)
            self.assertGreater(after["lines_gain"], group["bilinear"]["lines_gain"])

    def test_white_balance_maps_as_shot_neutral_to_srgb_white(self):
        # Both profiles map unit RGB into D50. The neutral belongs to D65.
        tags = {"CalibrationIlluminant1": 21, "CalibrationIlluminant2": 17,
                "AsShotNeutral": [0.95047, 1, 1.08883]}
        for i in (1, 2):
            tags[f"ColorMatrix{i}"] = np.eye(3)
            tags[f"ForwardMatrix{i}"] = np.diag(XYZ_D50)
        page = SimpleNamespace(tags={k: SimpleNamespace(value=v, dtype=12) for k, v in tags.items()})
        matrix, report = dng_color_matrix(page)
        np.testing.assert_allclose(matrix @ tags["AsShotNeutral"], 1, atol=.001)
        self.assertGreater(report["cctApproxK"], 6200)

    def test_rolloff_preserves_gradient_above_white_and_does_not_touch_linear(self):
        image = np.repeat(np.array([-.04, .55, .89, 1.03, 1.20], np.float32)[None, :, None], 3, axis=2)
        baseline, baseline_clipping = render(image, 0)
        shoulder, shoulder_clipping = render(image, 0, shoulder=True)
        self.assertAlmostEqual(baseline_clipping, .4)
        self.assertEqual(shoulder_clipping, 0)
        np.testing.assert_array_equal(baseline[0, :2], shoulder[0, :2])
        self.assertTrue(np.all(np.diff(shoulder[0, :, 0].astype(int)) > 0))
        self.assertLess(image[0, 0, 0], 0)
        self.assertGreater(image[0, -1, 0], 1)

    def test_flat_gain_statistics_keep_pixel_phases(self):
        values = np.ones((48, 72), np.float32)
        values[1::2, 1::2] = .30
        rows = flatness(values)
        self.assertAlmostEqual(rows[-1]["center"], .30)
        for row in rows:
            np.testing.assert_allclose(row["cornerToCenter"], 1)
            self.assertEqual(row["spreadEvP90P10"], 0)

    def test_display_png_saves_a_real_srgb_profile(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "display.png"
            save_display(path, np.zeros((8, 10, 3), np.uint8))
            with Image.open(path) as image:
                self.assertEqual(image.size, (10, 8))
                self.assertIsInstance(image.info.get("icc_profile"), bytes)

    def test_flat_repeats_recover_known_noise_variance(self):
        profile = np.array([[.001, .000008]] * 3, np.float32)
        sigma = np.sqrt(float(profile[0] @ [.30, 1]))
        first = np.random.default_rng(58).normal(.30, sigma, (384, 576)).astype(np.float32)
        repeat = np.random.default_rng(87).normal(.30, sigma, first.shape).astype(np.float32)
        rows = flat_noise_repeat(first, repeat, profile, profile)
        for row in rows:
            self.assertAlmostEqual(row["firstToRepeatSignalRatio"], 1, delta=.005)
            self.assertAlmostEqual(row["measuredToPredictedVarianceP10P50P90"][1], 1, delta=.08)
        with self.assertRaises(ValueError):
            flat_noise_repeat(first, repeat * .7, profile, profile)

    def test_uniform_target_never_reports_geometry_or_edge_metrics(self):
        image = np.full((40, 50, 3), 110, np.uint8)
        report = compare_display(image, image + 5, flat_field=True)
        self.assertEqual(report["alignment"]["status"], "not_evaluated")
        self.assertIsNone(report["metrics"])
        self.assertEqual(report["flatDisplayCenterMedianRgb255"]["raw"], [110, 110, 110])

    def test_flat_noise_calibration_uses_rgb_order_and_averages_green_phases(self):
        rows = [{"phase": [y, x], "channel": "RGB"[channel], "measuredToPredictedVarianceP10P50P90": [1, value, 5]}
                for (y, x, channel, _), value in zip(phases(), (2, 4, 3, 3))]
        report = {"flatField": {"noiseProfileRepeat": rows}}
        np.testing.assert_allclose(measured_noise_scale(report).ravel(), [3, 2.5, 4])
        rows[0]["phase"] = [0, 1]
        with self.assertRaises(ValueError):
            measured_noise_scale(report)


if __name__ == "__main__":
    unittest.main()
