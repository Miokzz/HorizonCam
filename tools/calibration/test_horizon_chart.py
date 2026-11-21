"""Synthetic oracle regressions. These do not validate any physical camera."""
import unittest

import cv2
import numpy as np

from horizon_chart import analyze, render_target


class HorizonChartTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.target = render_target()

    def report(self, image, confirmed=True):
        return analyze(image, setup_confirmed=confirmed)[0]

    def test_matching_upright_circle_and_horizon_pass(self):
        report = self.report(self.target)
        self.assertEqual("PASS", report["status"], report)
        self.assertEqual([1, 7, 13, 29], report["marker_ids"])
        self.assertGreater(report["confidence"], 0.95)
        self.assertAlmostEqual(1, report["circle_vertical_to_horizontal_scale"], delta=0.01)

    def test_upside_down_is_not_confused_with_level_horizon(self):
        report = self.report(cv2.rotate(self.target, cv2.ROTATE_180))
        self.assertEqual("FAIL", report["status"], report)
        self.assertGreater(abs(report["orientation_angle_deg"]), 170)
        self.assertLess(abs(report["horizon_angle_deg"]), 1)

    def test_clockwise_and_counterclockwise_roll_fail(self):
        for degrees in (-8, 8):
            matrix = cv2.getRotationMatrix2D((600, 400), degrees, 1)
            image = cv2.warpAffine(self.target, matrix, (1200, 800), borderValue=(255,255,255))
            report = self.report(image)
            self.assertEqual("FAIL", report["status"], report)
            self.assertAlmostEqual(abs(degrees), abs(report["horizon_angle_deg"]), delta=0.3)

    def test_anisotropic_vertical_and_horizontal_stretch_are_measured_not_rectified_away(self):
        for width, height, expected in [(1200, 1040, 1.3), (1560, 800, 1/1.3), (1200, 2528, 3.16)]:
            report = self.report(cv2.resize(self.target, (width, height)))
            self.assertEqual("FAIL", report["status"], report)
            self.assertAlmostEqual(expected, report["vertical_to_horizontal_scale"], delta=0.015)
            self.assertAlmostEqual(expected, report["circle_vertical_to_horizontal_scale"], delta=0.015)

    def test_uniform_resize_and_reasonable_compression_preserve_geometry(self):
        small = cv2.resize(self.target, (600,400), interpolation=cv2.INTER_AREA)
        self.assertEqual("PASS", self.report(small)["status"])
        blurred = cv2.GaussianBlur(self.target, (3,3), 0.7)
        success, jpeg = cv2.imencode(".jpg", cv2.cvtColor(blurred,cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY,75])
        self.assertTrue(success)
        decoded = cv2.cvtColor(cv2.imdecode(jpeg,cv2.IMREAD_COLOR),cv2.COLOR_BGR2RGB)
        self.assertEqual("PASS", self.report(decoded)["status"])

    def test_unconfirmed_physical_setup_never_passes(self):
        report = self.report(self.target, confirmed=False)
        self.assertEqual("INCONCLUSIVE", report["status"])

    def test_missing_wrong_cropped_mirrored_and_blank_targets_never_pass(self):
        blank = np.full_like(self.target,255)
        cropped = self.target[100:650, 300:1000]
        circle_only = blank.copy()
        cv2.circle(circle_only,(600,344),140,(0,0,0),9)
        for image in [blank, cropped, circle_only, cv2.flip(self.target,1)]:
            self.assertEqual("INCONCLUSIVE", self.report(image)["status"])

    def test_missing_circle_or_independent_horizon_line_is_inconclusive(self):
        no_circle = self.target.copy()
        no_circle[190:495,445:755] = 255
        no_line = self.target.copy()
        no_line[525:565,340:860] = 255
        for image in [no_circle,no_line]:
            self.assertEqual("INCONCLUSIVE", self.report(image)["status"])

    def test_perspective_setup_violation_does_not_become_a_runtime_stretch_claim(self):
        source = np.float32([[0,0],[1199,0],[1199,799],[0,799]])
        destination = np.float32([[180,50],[1020,50],[1199,799],[0,799]])
        homography = cv2.getPerspectiveTransform(source,destination)
        image = cv2.warpPerspective(self.target,homography,(1200,800),borderValue=(255,255,255))
        report = self.report(image)
        self.assertEqual("INCONCLUSIVE", report["status"], report)


if __name__ == "__main__":
    unittest.main()
