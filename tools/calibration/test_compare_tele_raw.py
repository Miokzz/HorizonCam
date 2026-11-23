import copy
import json
import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np
import tifffile
from PIL import Image, ImageOps

from compare_tele_raw import (align_pair, compare_pair, describe_pixels, display_srgb, dng_tags,
                              file_digest, orient_rgb, validate_manifest)


class ManifestTests(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        self.manifest = {"schemaVersion": 1, "pairId": "fixture-01", "deviceModel": "sm-s9210",
                         "firmware": "test", "logicalCameraId": "0", "physicalCameraId": "6",
                         "focalLengthMm": 7.0, "iso": 100, "exposureNs": 10_000_000,
                         "sensorTimestampNs": 123456789, "frameNumber": 0, "nominalOpticalZoom": 3,
                         "digitalZoomRequested": 1, "logicalZoomRatio": 1, "physicalZoomRatio": None,
                         "sensorCropRegion": [0, 0, 24, 16], "previewEnabled": True}
        for kind, filename in (("dng", "source.dng"), ("jpeg", "source.jpg")):
            path = self.root / filename
            path.write_bytes(kind.encode())
            self.manifest[kind] = {"filename": filename, "width": 24, "height": 16,
                                   "timestampNs": self.manifest["sensorTimestampNs"], "orientation": 1,
                                   "bytes": path.stat().st_size, "sha256": file_digest(path)}

    def save(self, manifest=None):
        document = self.manifest if manifest is None else manifest
        (self.root / "manifest.json").write_text(json.dumps(document), encoding="utf-8")

    def test_manifest_accepts_null_optional_camera_ratio(self):
        self.save()
        self.assertEqual(validate_manifest(self.root), self.manifest)

    def test_checksum_tamper_detected_without_output(self):
        self.save()
        (self.root / "source.jpg").write_bytes(b"JEpg")
        with self.assertRaisesRegex(ValueError, "jpeg SHA-256 mismatch"):
            compare_pair(self.root)
        self.assertFalse((self.root / "comparison").exists())

    def test_size_tamper_detected_before_any_decode(self):
        self.save()
        (self.root / "source.dng").write_bytes(b"longer fake DNG")
        with self.assertRaisesRegex(ValueError, "dng byte count mismatch"):
            validate_manifest(self.root)

    def test_timestamp_and_path_validation(self):
        for key, value, expected in (("timestampNs", 123456790, "jpeg timestamp mismatch"),
                                     ("filename", "../source.jpg", "Invalid jpeg filename")):
            with self.subTest(key=key):
                manifest = copy.deepcopy(self.manifest)
                manifest["jpeg"][key] = value
                self.save(manifest)
                with self.assertRaisesRegex(ValueError, expected):
                    validate_manifest(self.root)

    def test_existing_output_is_never_modified(self):
        output = self.root / "comparison"
        output.mkdir()
        sentinel = output / "comparison.json"
        sentinel.write_text("existing", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Output already exists"):
            compare_pair(self.root)
        self.assertEqual(sentinel.read_text(encoding="utf-8"), "existing")

    def test_plain_rgb_tiff_is_not_a_raw_cfa(self):
        source = self.root / "source.dng"
        tifffile.imwrite(source, np.zeros((16, 24, 3), dtype=np.uint16), photometric="rgb")
        with self.assertRaisesRegex(ValueError, "Expected DNG version and one mosaic CFA IFD"):
            dng_tags(source, self.manifest)

    def test_cfa_geometry_uses_stored_orientation(self):
        source = self.root / "source.dng"
        tifffile.imwrite(source, np.zeros((16, 24), dtype=np.uint16), photometric=32803, extratags=[
            (50706, "B", 4, (1, 4, 0, 0), False), (33421, "H", 2, (2, 2), False),
            (33422, "B", 4, (1, 2, 0, 1), False), (274, "H", 1, 6, False),
        ])
        self.manifest["dng"]["orientation"] = 6
        result = dng_tags(source, self.manifest)
        self.assertEqual(result["orientation"], 6)
        self.assertEqual(result["cfaPattern"], "GBRG")
        self.manifest["dng"]["width"] = 16
        self.manifest["dng"]["height"] = 24
        with self.assertRaisesRegex(ValueError, "DNG stored dimensions mismatch"):
            dng_tags(source, self.manifest)


class DevelopmentAndAlignmentTests(unittest.TestCase):
    def test_orientation_matches_pillow_for_non_square_raster(self):
        source = np.arange(18, dtype=np.uint8).reshape(2, 3, 3)
        for orientation in range(1, 9):
            with self.subTest(orientation=orientation):
                image = Image.fromarray(source)
                image.getexif()[274] = orientation
                np.testing.assert_array_equal(orient_rgb(source, orientation), np.asarray(ImageOps.exif_transpose(image)))

    def test_transparent_ev_applies_to_linear_then_srgb_and_preserves_array(self):
        original = np.array([[[0, 11796, 52428]]], dtype=np.uint16)
        before = original.copy()
        normal, clipped_normal = display_srgb(original, 0)
        brighter, clipped_brighter = display_srgb(original, 1)
        self.assertAlmostEqual(int(normal[0, 0, 1]), 118, delta=1)
        self.assertAlmostEqual(int(brighter[0, 0, 1]), 162, delta=1)
        self.assertEqual(int(brighter[0, 0, 2]), 255)
        self.assertEqual((clipped_normal, clipped_brighter), (0, 1))
        np.testing.assert_array_equal(original, before)

    def test_alignment_tracks_heldout_points_and_shared_metrics(self):
        rng = np.random.default_rng(80)
        surface = cv2.GaussianBlur(rng.integers(20, 230, (520, 690), dtype=np.uint8), (5, 5), 1.1)
        surface[320:420, 310:460] = 125
        source = cv2.cvtColor(surface, cv2.COLOR_GRAY2RGB)
        expected = np.float32([[1, 0, 7.0], [0, 1, -5.0]])
        target = cv2.warpAffine(source, expected, (source.shape[1], source.shape[0]))
        report = describe_pixels(source, target)
        alignment = report["alignment"]
        self.assertEqual(alignment["status"], "aligned", alignment)
        self.assertGreater(alignment["heldoutInlierFraction"], 0.9)
        np.testing.assert_allclose(alignment["rawToJpegMatrix"], expected, atol=0.2)
        self.assertLess(report["metrics"]["deltaE76Median"], 0.05)
        self.assertGreater(report["metrics"]["flatSamples"], 128)

    def test_low_texture_alignment_is_inconclusive(self):
        flat = np.zeros((224, 320, 3), dtype=np.uint8)
        matrix, alignment = align_pair(flat, flat)
        self.assertIsNone(matrix)
        self.assertEqual(alignment["status"], "inconclusive")
        self.assertIsNone(describe_pixels(flat, flat)["metrics"])


if __name__ == "__main__":
    unittest.main()
