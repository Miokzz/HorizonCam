import hashlib
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from capture_tele_raw import check_file, check_manifest, verified_pair


class CaptureTeleRawTest(unittest.TestCase):
    pair_id = "00000000-0000-4000-8000-000000000001"

    def test_retained_pair_requires_verified_status_and_exactly_one_test(self):
        marker = f"HC_RAW_PAIR privateCalibration={self.pair_id}\n"
        verified = "HC_RAW_PAIR preview=true status=verified raw=3648x2736 equalTimestamps=true\n"
        passed = "OK (1 test)\n"
        self.assertEqual(verified_pair(marker + verified + passed), self.pair_id)
        for bad_log in (marker + verified + "OK (0 tests)\n", marker + passed,
                        marker + marker + verified + passed):
            with self.assertRaises(ValueError):
                verified_pair(bad_log)

    def test_manifest_rejects_different_timestamp_and_unexpected_filename(self):
        capture = {
            "schemaVersion": 1, "pairId": self.pair_id, "deviceModel": "SM-S9210",
            "logicalCameraId": "0", "physicalCameraId": "6", "sensorTimestampNs": 12345,
            "dng": {"filename": "source.dng", "bytes": 21, "timestampNs": 12345, "sha256": "a" * 64},
            "jpeg": {"filename": "source.jpg", "bytes": 7, "timestampNs": 12345, "sha256": "b" * 64},
        }
        check_manifest(capture, self.pair_id)
        capture["jpeg"]["timestampNs"] += 1
        with self.assertRaises(ValueError):
            check_manifest(capture, self.pair_id)
        capture["jpeg"]["timestampNs"] -= 1
        capture["dng"]["filename"] = "../source.dng"
        with self.assertRaises(ValueError):
            check_manifest(capture, self.pair_id)

    def test_transfer_rejects_same_length_corruption(self):
        source = b"test-pair"
        proof = {"bytes": len(source), "sha256": hashlib.sha256(source).hexdigest()}
        with TemporaryDirectory() as directory:
            path = Path(directory) / "source.dng.part"
            path.write_bytes(source)
            check_file(path, proof)
            path.write_bytes(b"best-pair")
            with self.assertRaises(ValueError):
                check_file(path, proof)


if __name__ == "__main__":
    unittest.main()
