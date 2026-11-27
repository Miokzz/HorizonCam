import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image
from scipy.ndimage import uniform_filter

from offdevice_restoration import (
    REPO, SCALE, area_project, blend, comparisons, evidence, invoke_tiled, load_cases, output_directory,
)


def replicate(rgb):
    return rgb.repeat(SCALE, axis=0).repeat(SCALE, axis=1)


class OffDeviceRestorationTest(unittest.TestCase):
    def test_odd_rectangular_tiles_match_full_context(self):
        rgb = np.random.default_rng(42).integers(0, 256, (11, 17, 3)).astype(np.float32)

        def smooth(patch):
            return uniform_filter(patch, size=(3, 3, 1), mode="mirror")

        assembled, calls = invoke_tiled(rgb, lambda patch: replicate(smooth(patch)), halo=1, tile=8)
        self.assertEqual(calls, 6)
        np.testing.assert_allclose(assembled, replicate(smooth(rgb)), atol=1e-5)

    def test_blends_and_source_projection_preserve_scale_and_endpoints(self):
        baseline = np.full((28, 36, 3), 20, dtype=np.uint8)
        prediction = np.full_like(baseline, 60)
        for alpha, expected in ((0, 20), (0.25, 30), (1, 60)):
            actual = blend(baseline, prediction, alpha)
            np.testing.assert_array_equal(actual, expected)
            np.testing.assert_array_equal(area_project(actual), np.full((7, 9, 3), expected))

    def test_conservation_metrics_keep_ground_truth_optional(self):
        rgb = np.full((7, 9, 3), 127, dtype=np.uint8)
        exact = replicate(rgb)
        report = evidence(rgb, exact, exact, None)
        self.assertEqual(report["source_roundtrip"], comparisons(rgb, rgb))
        self.assertAlmostEqual(report["source_roundtrip"]["ssim_rgb"], 1, places=5)
        self.assertIsNone(report["source_roundtrip"]["psnr_db"])
        self.assertIsNone(report["source_strong_edge_cosine"])
        self.assertNotIn("controlled_aligned_reference", report)
        self.assertEqual(report["beyond_native_luma_envelope_fraction"], 0)
        bright = np.full_like(exact, 140)
        report = evidence(rgb, exact, bright, exact)
        self.assertEqual(report["beyond_native_luma_envelope_fraction"], 1)
        self.assertEqual(report["controlled_aligned_reference"]["mae_dn"], 13)

    def test_manifest_orients_before_crop_and_derives_controlled_pair(self):
        rgb = np.random.default_rng(31).integers(0, 256, (44, 40, 3), dtype=np.uint8)
        with tempfile.TemporaryDirectory() as scratch:
            directory = Path(scratch)
            exif = Image.Exif()
            exif[274] = 6
            Image.fromarray(rgb).save(directory / "tagged.png", exif=exif)
            manifest = directory / "manifest.json"
            manifest.write_text(json.dumps([{"id": "test", "source": "tagged.png", "mode": "controlled",
                                             "scene": "fixture", "box": [4, 0, 44, 32]}]), encoding="utf-8")
            (metadata, low, reference), = load_cases(manifest)
            self.assertEqual(metadata["input_width_height"], [10, 8])
            np.testing.assert_array_equal(reference, np.rot90(rgb, -1)[:32, 4:44])
            self.assertEqual(low.shape, (8, 10, 3))

    def test_private_output_guard(self):
        with self.assertRaises(ValueError):
            output_directory(REPO / "tools" / "private-output")


if __name__ == "__main__":
    unittest.main()
