import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import numpy as np
from PIL import Image
from offdevice_corpus import record, validate
from offdevice_n2n import observations, quality, scene_planes
from offdevice_opencl import axis, lut


class TestHostCorpus(unittest.TestCase):
    def test_source_identity_and_scene_partition_precede_patching(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            rows = []
            for i, split in enumerate(("train", "validation", "test")):
                file = root / f"{i}.png"
                Image.new("RGB", (64, 64), (i, 12, 80)).save(file)
                rows.append(record(root, f"s{i}", split, "chart", "test author", "test fixture", "local", file))
            manifest = root / "corpus.json"
            def write():
                manifest.write_text(json.dumps({"schema":1,"scenes":rows}), encoding="utf-8")
            write()
            self.assertEqual(len(validate(manifest)["scenes"]), 3)
            rows[1]["group"] = rows[0]["group"]
            write()
            with self.assertRaisesRegex(ValueError, "leaked"):
                validate(manifest)
            rows[1]["group"] = "new"
            for key in ("path","rgbSha256","sha256"):
                rows[1][key] = rows[0][key]
            write()
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                validate(manifest)

    def test_independent_observations_and_repeatable_seeds(self):
        base = np.full((64, 64, 3), 128, np.uint8)
        clean = scene_planes(base)
        self.assertEqual((32, 32, 4), clean.shape)
        self.assertAlmostEqual(.21586, float(clean.mean()), places=4)
        profile = np.full((3,2), .001, np.float32)
        a,b = observations(clean, profile, np.random.default_rng(42))
        self.assertFalse(np.array_equal(a,b))
        c,d = observations(clean, profile, np.random.default_rng(42))
        np.testing.assert_array_equal(a,c)
        np.testing.assert_array_equal(b,d)
        self.assertEqual(quality(clean,clean)["ssimPacked"], 1.)
        self.assertEqual(quality(clean,clean)["flatRmse"], 0.)

    def test_gain_coordinates_keep_cfa_phases_and_lut_endpoints(self):
        a = axis(4, 3, 0, .5, .1).reshape(-1,3)
        b = axis(4, 3, 1, .5, .1).reshape(-1,3)
        np.testing.assert_array_equal(a[0], [0,1,0])
        self.assertGreater(b[0,2], a[0,2])
        self.assertTrue(np.all(np.diff(lut().astype(float)) >= 0))
        np.testing.assert_array_equal(lut()[[0,-1]], [0,255])
        with self.assertRaises(ValueError):
            axis(16,3,0,0,.5)
