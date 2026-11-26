import unittest

import numpy as np

from raw_fidelity import SHAPES, metrics, tile_metrics


class RawFidelityTest(unittest.TestCase):
    def test_float_metrics_count_signed_zero_as_a_bit_difference(self):
        result = metrics(np.array([0., .25], dtype="<f4"), np.array([-0., .25], dtype="<f4"))
        self.assertEqual(result["different"], 1)
        self.assertEqual(result["max"], 0)
        self.assertEqual(result["first"], [0])

    def test_padded_noise_is_excluded_but_tensor_padding_is_compared(self):
        a = {name: np.zeros(shape, dtype="<f4") for name, shape in SHAPES.items()}
        b = {name: values.copy() for name, values in a.items()}
        b["noise"][0, 0, 0, 0] = 1.  # Tile 0 starts outside the image.
        b["input"][0, 0, 0] = 1.
        result = tile_metrics(a, b, a, 0)
        self.assertEqual(result["sum3x3"]["different"], 0)
        self.assertEqual(result["cnnSignal"]["different"], 1)
        self.assertEqual(result["cnnReplayInput"]["different"], 0)

    def test_replay_readback_must_match_the_reference_tensor(self):
        a = {name: np.zeros(shape, dtype="<f4") for name, shape in SHAPES.items()}
        replay = {name: values.copy() for name, values in a.items()}
        replay["input"][10, 10, 5] = .25
        with self.assertRaisesRegex(ValueError, "exact NEON tensor"):
            tile_metrics(a, a, replay, 0)


if __name__ == "__main__":
    unittest.main()
