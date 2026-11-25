import json
import math
import os
import struct
import unittest
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import tifffile

from tele_dng_optics import read_optics


def opcode(code, body, flags=1, version=0x01030000):
    return struct.pack(">4I", code, version, flags, len(body)) + body


def opcodes(*entries):
    return struct.pack(">I", len(entries)) + b"".join(entries)


def gain(phase, shape, values, spacing=(1.0, 1.0), origin=(0.0, 0.0), flags=1):
    samples = np.asarray(values, dtype=">f4")
    body = struct.pack(">4i6I4dI", *phase, *shape, 0, 1, 2, 2, *samples.shape, *spacing, *origin, 1)
    return opcode(9, body + samples.tobytes(), flags)


def warp(terms=(1, 0, 0, 0, 0, 0), center=(0.5, 0.5), flags=1):
    return opcode(1, struct.pack(">I8d", 1, *terms, *center), flags)


def page(shape=(4, 8), stage1=None, stage2=None, stage3=None, extras=None):
    tags = {33421: (2, 2), 33422: b"\x01\x02\x00\x01"}
    for tag, value in zip((51008, 51009, 51022), (stage1, stage2, stage3)):
        if value is not None:
            tags[tag] = value
    tags.update(extras or {})
    return SimpleNamespace(imagelength=shape[0], imagewidth=shape[1], photometric=32803, samplesperpixel=1,
                           tags={tag: SimpleNamespace(value=value) for tag, value in tags.items()})


def ramp(shape):
    height, width = shape
    rgb = np.empty((height, width, 3), dtype=np.float32)
    rgb[:, :, 0] = np.arange(width)
    rgb[:, :, 1] = np.arange(height)[:, None]
    rgb[:, :, 2] = 13
    return rgb


class GainMapTests(unittest.TestCase):
    def test_four_parities_interpolate_centers_and_keep_distinct_greens(self):
        shape = (4, 8)
        stage = opcodes(gain((1, 0), shape, [[1, 3], [2, 4]], (0.5, 0.5), (0.25, 0.25)),
                        gain((0, 0), shape, [[1.5]]), gain((1, 1), shape, [[1.75]]), gain((0, 1), shape, [[2.25]]))
        optics = read_optics(page(shape, stage2=stage))
        field = optics.gain_field(shape)
        self.assertEqual(field.dtype, np.float32)
        self.assertEqual(optics.diagnostic()["cfaPattern"], "GBRG")
        np.testing.assert_array_equal(field[0::2, 0::2], 1.5)
        np.testing.assert_array_equal(field[1::2, 1::2], 1.75)
        np.testing.assert_array_equal(field[0::2, 1::2], 2.25)
        # At y=1.5/4, the v coordinate in the 2×2 table is 1/4. x=2.5/8 gives 1/8.
        np.testing.assert_allclose(field[1, 0::2], [1.25, 1.50, 2.50, 3.25])
        # Vertical edge clamp at y=3.5/4 and horizontal edge clamp at x=6.5/8.
        self.assertEqual(field[3, 6], 4)

    def test_optional_incomplete_maps_are_explicitly_skipped(self):
        shape = (4, 8)
        duplicate = gain((0, 0), shape, [[2]])
        optics = read_optics(page(shape, stage2=opcodes(duplicate, duplicate)))
        np.testing.assert_array_equal(optics.gain_field(shape), 1)
        self.assertEqual(len(optics.diagnostic()["unsupportedOpcodes"]), 2)

    def test_rejects_malformed_tables_and_invalid_cfa(self):
        shape = (4, 8)
        for invalid in (0, np.nan, np.inf):
            with self.subTest(invalid=invalid), self.assertRaisesRegex(ValueError, "Invalid GainMap entries"):
                read_optics(page(shape, stage2=opcodes(gain((0, 0), shape, [[invalid]]))))
        with self.assertRaisesRegex(ValueError, "Bayer phases"):
            read_optics(page(shape, extras={33422: b"\x01\x01\x00\x02"}))
        with self.assertRaisesRegex(ValueError, "ActiveArea"):
            read_optics(page(shape, extras={50829: (1, 0, *shape)}))


class WarpTests(unittest.TestCase):
    def test_identity_preserves_original_and_no_opcode_does_not_require_color_conversion(self):
        for stage in (None, opcodes(warp())):
            with self.subTest(stage=stage):
                optics = read_optics(page(stage3=stage))
                pixels = ramp((4, 8)).astype(np.uint16)
                self.assertIs(optics.warp(pixels), pixels)
                self.assertEqual(optics.diagnostic()["warpRectilinear"]["status"], "identity" if stage else "absent")

    def test_radial_warp_maps_dest_to_source_without_halfpixel_offset(self):
        shape = (51, 77)
        payload = warp((0.958, 0, 0, 0, 0, 0), center=(0.32, 0.72))
        optics = read_optics(page(shape, stage3=opcodes(payload)))
        image = ramp(shape)
        corrected = optics.warp(image)
        self.assertEqual(corrected.shape, image.shape)
        self.assertEqual(corrected.dtype, np.float32)
        cx, cy = 77 * 0.32, 51 * 0.72
        np.testing.assert_allclose(corrected[19, 64], [cx + 0.958 * (64 - cx), cy + 0.958 * (19 - cy), 13], atol=0.016)
        self.assertEqual(optics.diagnostic()["warpRectilinear"]["status"], "supported")
        np.testing.assert_array_equal(corrected[:, :, 2], 13)
        np.testing.assert_array_equal(image, ramp(shape))

    def test_tangential_coefficients_use_vertical_then_horizontal_order(self):
        shape = (86, 139)
        p0, p1 = 0.013, -0.006
        optics = read_optics(page(shape, stage3=opcodes(warp((1, 0, 0, 0, p0, p1)))))
        corrected = optics.warp(ramp(shape))
        # Along y=cy, the SDK tangential terms reduce to 3*p1*dx² in x and p0*dx² in y.
        radius = math.hypot(shape[1] / 2, shape[0] / 2)
        distance_x = 126 - shape[1] / 2
        np.testing.assert_allclose(corrected[43, 126], [126 + 3 * p1 * distance_x**2 / radius,
                                                       43 + p0 * distance_x**2 / radius, 13], atol=0.016)

    def test_unsupported_optional_warp_keeps_geometry_and_mandatory_refuses(self):
        for flags in (0, 1):
            with self.subTest(flags=flags):
                optics = read_optics(page(stage3=opcodes(warp((1e308, 0, 0, 0, 0, 0), flags=flags))))
                json.dumps(optics.diagnostic(), allow_nan=False)
                self.assertEqual(optics.diagnostic()["warpRectilinear"]["status"], "unsupported")
                image = ramp((4, 8))
                if flags:
                    self.assertIs(optics.warp(image), image)
                else:
                    with self.assertRaisesRegex(ValueError, "Unsupported mandatory"):
                        optics.warp(image)


class ParserTests(unittest.TestCase):
    def test_unknown_and_bad_byte_counts_are_handled_separately(self):
        valid = opcodes(opcode(222, b"data"))
        optics = read_optics(page(stage1=valid))
        self.assertEqual(optics.diagnostic()["unsupportedOpcodes"][0]["opcodeId"], 222)
        for invalid, message in ((valid[:-1], "Truncated OpcodeList1 payload"), (valid + b"x", "Trailing bytes")):
            with self.subTest(invalid=invalid), self.assertRaisesRegex(ValueError, message):
                read_optics(page(stage1=invalid))

    def test_application_requires_unrotated_uncropped_shape(self):
        optics = read_optics(page())
        with self.assertRaisesRegex(ValueError, "full unrotated"):
            optics.gain_field((8, 4))
        with self.assertRaisesRegex(ValueError, "full unrotated"):
            optics.warp(ramp((3, 8)))


class PrivateFieldFixtureTests(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("HORIZONCAM_TELE_DNG_FIXTURES"), "no private DNG fixtures configured")
    def test_tele_gain_and_warp_on_private_field_fixtures(self):
        fixture_paths = os.environ["HORIZONCAM_TELE_DNG_FIXTURES"].split(os.pathsep)
        for index, name in enumerate(fixture_paths):
            with self.subTest(index=index), tifffile.TiffFile(Path(name)) as document:
                source = document.pages[0]
                optics = read_optics(source)
                field = optics.gain_field(source.shape)
                diagnostics = optics.diagnostic()
                self.assertEqual(diagnostics["cfaPattern"], "GBRG")
                self.assertEqual(len(diagnostics["gainMaps"]), 4)
                self.assertEqual(diagnostics["unsupportedOpcodes"], [])
                self.assertTrue(np.isfinite(field).all())
                self.assertGreater(float(field.min()), 1)
                self.assertLess(float(field.max()), 2.1)
                warp_stats = diagnostics["warpRectilinear"]
                self.assertEqual(warp_stats["status"], "supported")
                self.assertLessEqual(warp_stats["maxDisplacementPx"], 6)
                self.assertEqual(warp_stats["outsideRasterSampleFraction"], 0)
                # Run a synthetic coordinate ramp at the real raster extent without decoding private pixels.
                corrected = optics.warp(ramp(source.shape))
                self.assertEqual(corrected.shape[:2], source.shape)
                self.assertTrue(np.all(corrected[:, :, 2] == 13))


if __name__ == "__main__":
    unittest.main()
