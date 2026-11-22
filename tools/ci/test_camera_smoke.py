"""Regression tests for the CI oracle, not substitutes for emulator captures."""
import unittest

from media_checks import media_geometry, validate_geometry, validate_probe, validate_ai_output, validate_auto_photo


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

    def test_ai_requires_actual_4x_patch_dimensions_and_model_disclosure(self):
        description = "Horizon AI; pretrained ESRGAN; estimated details; input/output 256×256 → 1024×1024; zoom=1.0"
        validate_ai_output(1024, 1024, 256, description)
        with self.assertRaises(AssertionError):
            validate_ai_output(256, 256, 256, description)
        with self.assertRaises(AssertionError):
            validate_ai_output(1024, 1024, 256, "ordinary camera JPEG")

    def test_ai_rejects_metadata_disagreeing_with_real_output(self):
        validate_ai_output(1024, 1024, 256,
            "pretrained ESRGAN; estimated details; input/output 256x256 -> 1024x1024")
        with self.assertRaises(AssertionError):
            validate_ai_output(1024, 1024, 256,
                "pretrained ESRGAN; estimated details; input/output 128×128 → 512×512")

    def test_ai_small_native_patch_uses_real_source_size(self):
        validate_ai_output(800, 800, 200,
            "pretrained ESRGAN; estimated details; input/output 200×200 → 800×800")
        with self.assertRaises(AssertionError):
            validate_ai_output(2560, 2560, 640,
                "pretrained ESRGAN; estimated details; input/output 640×640 → 2560×2560")

    def test_auto_native_jpeg_requires_a_finished_matching_zero_tile_render(self):
        description = ("HorizonCam | detalhe nativo | sem restauracao neural | "
                       "zoom 1.0x | frames=1 | input/output 960x1280 -> 960x1280")
        logs = ("I HorizonAuto: render capture=other.jpg source=960x1280 output=960x1280 zoom=1.0 "
                "tiles=80 frames=1 originalScan=false\n"
                "I HorizonAuto: render capture=shot.jpg source=960x1280 output=960x1280 zoom=1.0 "
                "tiles=0 frames=1 originalScan=true")
        self.assertEqual(validate_auto_photo(logs, "shot.jpg", (960, 1280), description),
                         {"kind": "native", "tiles": 0, "frames": 1, "original_scan": True})
        with self.assertRaises(AssertionError):
            validate_auto_photo(logs, "other.jpg", (960, 1280), description)
        with self.assertRaises(AssertionError):
            validate_auto_photo(logs, "shot.jpg", (1280, 960), description)
        with self.assertRaises(AssertionError):
            validate_auto_photo(logs, "shot.jpg", (960, 1280), "JPEG da camera")

    def test_auto_neural_and_fusion_require_consistent_provenance(self):
        logs = ("render capture=tele.jpg source=296x394 output=1184x1576 "
                "zoom=30.0 tiles=35 frames=2 originalScan=false")
        description = ("Horizon AI 2 | Real-ESRGAN general-x4v3 | textura estimada | "
                       "zoom 30.0x | frames=2 | input/output 296x394 -> 1184x1576")
        self.assertEqual(validate_auto_photo(logs, "tele.jpg", (1184, 1576), description)["kind"], "neural")
        with self.assertRaises(AssertionError):
            validate_auto_photo(logs, "tele.jpg", (1184, 1576), description.replace("frames=2", "frames=1"))
        with self.assertRaises(AssertionError):
            validate_auto_photo(logs.replace("tiles=35", "tiles=0"), "tele.jpg", (1184, 1576), description)
        fusion_log = ("render capture=fusion.jpg source=960x720 output=960x720 "
                      "tiles=0 frames=3 originalScan=false")
        fusion = ("HorizonCam | detalhe multiframe | sem restauracao neural | "
                  "frames=3 | input/output 960x720 -> 960x720")
        self.assertEqual(validate_auto_photo(fusion_log, "fusion.jpg", (960, 720), fusion)["kind"], "fusion")


if __name__ == "__main__":
    unittest.main()
