"""Regression tests for the CI oracle, not substitutes for emulator captures."""
import unittest

from media_checks import media_geometry, validate_geometry, validate_probe


class MediaOracleTest(unittest.TestCase):
    def probe(self, rotation=0, width=1280, height=720):
        return {"streams": [{"codec_type": "video", "codec_name": "h264",
            "width": width, "height": height, "sample_aspect_ratio": "1:1",
            "side_data_list": [{"rotation": rotation}]}],
            "format": {"duration": "5.2", "size": "175000"}}

    def test_portrait_metadata_is_applied_before_dimensions_are_checked(self):
        report = validate_probe(self.probe(rotation=-90), portrait=True)
        self.assertEqual(report["displayed"], [720, 1280])
        self.assertEqual(media_geometry(1280, 720, 270), (720, 1280))

    def test_sideways_video_fails_instead_of_being_reported_as_portrait(self):
        with self.assertRaises(AssertionError):
            validate_probe(self.probe(), portrait=True)

    def test_lock_requires_landscape_encoded_pixels_and_landscape_metadata(self):
        validate_probe(self.probe(), portrait=False, locked=True)
        with self.assertRaises(AssertionError):
            validate_probe(self.probe(90, 720, 1280), portrait=False, locked=True)

    def test_4by3_or_non_square_pixels_fail_video_validation(self):
        with self.assertRaises(AssertionError):
            validate_probe(self.probe(width=640, height=480), portrait=False)
        probe = self.probe()
        probe["streams"][0]["sample_aspect_ratio"] = "4:3"
        with self.assertRaises(AssertionError):
            validate_probe(probe, portrait=False)

    def test_empty_or_truncated_recording_fails(self):
        for duration, size in [(0, 175000), (1.1, 175000), (5, 800), (float("nan"), 175000)]:
            probe = self.probe()
            probe["format"] = {"duration": duration, "size": size}
            with self.assertRaises(AssertionError):
                validate_probe(probe, portrait=False)
        with self.assertRaises(AssertionError):
            validate_probe({"streams": []}, portrait=False)

    def test_jpeg_display_geometry_requires_correct_4by3_orientation(self):
        validate_geometry(960, 1280, portrait=True, aspect=4 / 3)
        with self.assertRaises(AssertionError):
            validate_geometry(1280, 960, portrait=True, aspect=4 / 3)
        with self.assertRaises(AssertionError):
            validate_geometry(720, 1280, portrait=True, aspect=4 / 3)

    def test_arbitrary_angle_does_not_silently_swap_dimensions(self):
        with self.assertRaises(AssertionError):
            media_geometry(1280, 720, 45)


if __name__ == "__main__":
    unittest.main()
