import unittest

from raw_device_run import battery_window, delegation, distribution


class RawDeviceReportingTest(unittest.TestCase):
    def test_gpu_evidence_reports_partial_plan(self):
        trace = ("Replacing 3 out of 4 node(s) with delegate (TfLiteGpuDelegateV2) node, "
                 "yielding 1 partitions for subgraph 0.")
        self.assertEqual(delegation(trace, "gpu-fp16"),
                         {"acceleratorNodes": 3, "cpuNodes": 1, "partitions": 1, "backend": "TfLiteGpuDelegateV2"})

    def test_qnn_evidence_reads_vendor_result(self):
        trace = "INFO: [Qnn Delegate] QNN HTP delegate: 4 nodes delegated out of 4 nodes with 1 partitions."
        self.assertEqual(delegation(trace, "qnn")["acceleratorNodes"], 4)

    def test_delegate_creation_alone_is_inconclusive(self):
        self.assertIsNone(delegation("Created 1 GPU delegate kernels.", "gpu")["acceleratorNodes"])
        self.assertEqual(delegation("INFO: Created TensorFlow Lite XNNPACK delegate for CPU.", "cpu")["cpuNodes"], 4)

    def test_battery_estimate_uses_the_charge_gauge_and_rejects_charging(self):
        sample = dict(thermalStatus=0, interactive=False, temperatureDeciC=275, energyNwh=None, chargeUah=1500000,
                      timeNs=0, plugged=0, currentUa=-500, voltageMv=4000)
        later = sample | {"timeNs": 3_600_000_000, "chargeUah": 1499500}
        window = {"samples": [sample, later], "frames": 1}
        result = battery_window(window)
        self.assertAlmostEqual(result["totalSystemDischargeJ"], 7.2)
        self.assertAlmostEqual(result["grossWatts"], 2)
        self.assertEqual(result["currentNowReturned"]["p50"], -500)
        later["plugged"] = 1
        self.assertIsNone(battery_window(window)["grossWatts"])

    def test_percentile_is_interpolated(self):
        self.assertEqual(distribution([1, 2, 3, 4, 5])["p95"], 4.8)


if __name__ == "__main__":
    unittest.main()
