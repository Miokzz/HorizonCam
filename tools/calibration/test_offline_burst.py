"""Controlled regressions for the host Bayer burst laboratory."""

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import numpy as np

from offline_burst import (OFFSETS, SATURATION, SYNTHETIC_PROFILE, bilinear_phase, create_simulation,
                           file_digest, fuse, json_read, json_write, load_burst, phase_crop_origin,
                           simulation_metrics)


class OfflineBurstTest(unittest.TestCase):
    def test_known_subpixel_motion_noise_structure_and_occlusion(self):
        for seed in (20261126, 1928, 517):
            with self.subTest(seed=seed), TemporaryDirectory() as directory:
                manifest, truth, regions, moving = create_simulation(Path(directory), seed)
                burst = load_burst(manifest)
                reference, _, _ = burst.read(burst.reference)
                fused, _, _ = fuse(burst)
                ungated, _, _ = fuse(burst, reject_motion=False)
                for record, expected in zip(fused.evidence, OFFSETS):
                    self.assertTrue(record["accepted"], record)
                    self.assertLess(np.linalg.norm(np.subtract(record["dxdy"], expected)), .20)

                stats = simulation_metrics(reference, truth, regions, moving, fused, ungated)
                scores = stats["motionGated"]
                baseline = stats["reference"]
                self.assertLess(scores["rawStaticRmse"], .60 * baseline["rawStaticRmse"])
                self.assertLess(scores["isp"]["uniform_mid_noise_rms"], .30 * baseline["isp"]["uniform_mid_noise_rms"])
                self.assertGreater(scores["isp"]["edge_gain"], .85)
                self.assertGreater(scores["isp"]["texture_gain"], .85)
                self.assertGreater(scores["isp"]["lines_gain"], .85)
                self.assertLess(scores["isp"]["false_chroma_rms"], .70 * baseline["isp"]["false_chroma_rms"])
                for region in ("rawMovingEnterRmse", "rawMovingLeaveRmse"):
                    self.assertLess(scores[region], .15 * stats["ungated"][region])
                    self.assertLess(scores[region], 1.01 * baseline[region])
                self.assertEqual(stats["uncertainty"]["movingEffectiveFramesMedian"], [1.0, 1.0])
                self.assertGreater(stats["uncertainty"]["staticEffectiveFramesMedian"], 6.)
                ratio = stats["uncertainty"]["empiricalMidFlatRmse"] / stats["uncertainty"]["predictedMidFlatRmsStd"]
                self.assertAlmostEqual(ratio, 1., delta=.08)

                saturated = reference >= SATURATION
                self.assertTrue(saturated.any())
                np.testing.assert_array_equal(fused.mosaic[saturated], reference[saturated])
                self.assertTrue(np.isnan(fused.sigma[saturated]).all())
                self.assertTrue(np.isfinite(fused.sigma[~saturated]).all())

    def test_bilinear_phase_accounts_for_taps_and_saturation(self):
        phase = (np.arange(12, dtype=np.float32).reshape(3, 4) - 3) / 12
        registered, valid, variance_factor, fraction = bilinear_phase(phase, .5, -.25)
        expected = .375 * (phase[1:, :-1] + phase[1:, 1:]) + .125 * (phase[:-1, :-1] + phase[:-1, 1:])
        np.testing.assert_allclose(registered[1:, :-1], expected)
        self.assertEqual(valid.sum(), 6)
        self.assertFalse(valid[0].any())
        self.assertFalse(valid[:, -1].any())
        self.assertEqual(variance_factor, 2 * (.375 ** 2 + .125 ** 2))
        self.assertEqual(fraction, (.5, .75))

        phase[1, 0] = 1.
        _, valid, _, _ = bilinear_phase(phase, .5, -.25)
        self.assertFalse(valid[1, 0] or valid[2, 0])
        _, valid, factor, fraction = bilinear_phase(phase, 0, 0)
        np.testing.assert_array_equal(valid, phase < SATURATION)
        self.assertEqual(factor, 1.)
        self.assertEqual(fraction, (0., 0.))

    def test_flat_without_motion_evidence_falls_back_to_exact_reference(self):
        with TemporaryDirectory() as directory:
            folder = Path(directory)
            sources = [np.full((80, 90), .16, np.float32), np.full((80, 90), -.02, np.float32)]
            entries = []
            for i, raw in enumerate(sources):
                path = folder / f"frame{i}.npy"
                np.save(path, raw, allow_pickle=False)
                entries.append({"source": path.name, "sha256": file_digest(path)})
            path = folder / "burst.json"
            json_write(path, {"schemaVersion": 1, "kind": "synthetic", "cfa": "GBRG", "reference": 1,
                              "noiseProfileRgb": SYNTHETIC_PROFILE.tolist(), "frames": entries})
            burst = load_burst(path)
            result, _, _ = fuse(burst)
            self.assertFalse(result.evidence[0]["accepted"])
            self.assertTrue(result.evidence[1]["reference"])
            np.testing.assert_array_equal(result.mosaic, sources[1])
            np.testing.assert_array_equal(result.frames_used, np.ones(sources[1].shape))

    def test_manifest_rejects_wrong_hash_duplicate_and_cfa(self):
        with TemporaryDirectory() as directory:
            folder = Path(directory)
            source, _, _, _ = create_simulation(folder, 517)
            data = json_read(source)
            broken = folder / "wrong-cfa.json"
            json_write(broken, dict(data, cfa="RGGB"))
            with self.assertRaisesRegex(ValueError, "GBRG"):
                load_burst(broken)
            broken = folder / "wrong-sha.json"
            json_write(broken, dict(data, frames=[dict(data["frames"][0], sha256="0"), data["frames"][1]]))
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                load_burst(broken)
            broken = folder / "duplicate.json"
            json_write(broken, dict(data, frames=[data["frames"][0]] * 2))
            with self.assertRaisesRegex(ValueError, "Repeated RAW frame"):
                load_burst(broken)

    def test_capture_import_requires_one_ordered_fixed_exposure_sequence(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            json_write(path, {"schemaVersion": 1, "kind": "capture", "cfa": "GBRG", "reference": 0,
                              "provenance": {"captureSessionId": "camera-session-42"},
                              "frames": [{"source": "first"}, {"source": "second"}]})
            first = {"deviceModel": "SM-S9210", "firmware": "fixture", "logicalCameraId": "0",
                     "physicalCameraId": "6", "focalLengthMm": 7., "iso": 100, "exposureNs": 5_000_000,
                     "sensorCropRegion": [0, 0, 80, 96], "sensorTimestampNs": 200_000_000, "frameNumber": 40,
                     "pairId": "first", "dng": {"width": 80, "height": 96, "orientation": 1, "sha256": "a" * 64}}
            second = dict(first, sensorTimestampNs=235_000_000, frameNumber=41, pairId="second",
                          dng=dict(first["dng"], sha256="b" * 64))
            cases = ((second, None), (dict(second, iso=200), "settings or geometry changed"),
                     (dict(second, frameNumber=40), "frameNumber must increase"),
                     (dict(second, sensorTimestampNs=3_000_000_000), "over two seconds"))
            for frame, failure in cases:
                with (self.subTest(failure=failure),
                      patch("offline_burst.validate_manifest", side_effect=(first, frame)),
                      patch("offline_burst.dng_tags", return_value={"cfaPattern": "GBRG"})):
                    if failure:
                        with self.assertRaisesRegex(ValueError, failure):
                            load_burst(path)
                    else:
                        burst = load_burst(path)
                        self.assertEqual([item.source.name for item in burst.frames], ["first", "second"])
                        self.assertEqual(burst.kind, "capture")

    def test_hdrplus_crop_reindexes_four_phases_without_interpolation(self):
        for pattern in ("GBRG", "BGGR", "RGGB", "GRBG"):
            with self.subTest(pattern=pattern):
                active = np.tile(np.array(list(pattern)).reshape(2, 2), (48, 64))
                y, x = phase_crop_origin(active.shape, (64, 72), pattern)
                crop = active[y:y + 64, x:x + 72]
                self.assertEqual("".join(crop[:2, :2].flatten()), "GBRG")
                self.assertEqual(crop.shape, (64, 72))


if __name__ == "__main__":
    unittest.main()
