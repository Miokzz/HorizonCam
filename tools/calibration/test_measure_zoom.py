import unittest

import cv2
import numpy as np

from measure_zoom import measure


class ZoomMeasurementTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rng = np.random.default_rng(24)
        cls.scene = cv2.GaussianBlur(rng.integers(0, 256, (800, 1000), dtype=np.uint8), (5, 5), 1)

    def test_central_crop_reports_optical_scale_across_jpeg_resolutions(self):
        narrow = cv2.resize(self.scene[200:600, 250:750], (600, 480))
        result = measure(self.scene, narrow, 2)
        self.assertEqual("PASS", result["status"], result)
        self.assertAlmostEqual(2, result["measured_scale"], delta=0.01)

    def test_matching_preview_and_jpeg_do_not_prove_requested_zoom(self):
        result = measure(self.scene, self.scene, 3)
        self.assertEqual("FAIL", result["status"], result)
        self.assertAlmostEqual(1, result["measured_scale"], delta=0.01)

    def test_textureless_scene_is_inconclusive(self):
        flat = np.zeros((200, 200), dtype=np.uint8)
        self.assertEqual("INCONCLUSIVE", measure(flat, flat, 1)["status"])
