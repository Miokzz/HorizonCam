import importlib.util
import unittest

import numpy as np

if importlib.util.find_spec("torch"):
    import torch
    from run_tele_raw_restore import restore_pmrid, stroke_dice, text_target
    from tele_raw_restore import (HALO, BayerResidual, blend_structures, features, noisy_cfa, pack,
                                  predict_tiled, restore, sample_cfa, torch_invoke, unpack)
else:
    torch = None


@unittest.skipIf(torch is None, "Install the optional RAW restoration dependencies")
class RawRestorationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(2)

    def test_gbrg_pack_and_inverse(self):
        rgb = np.broadcast_to(np.array([.15, .5, .85], np.float32), (8, 10, 3))
        packed = pack(sample_cfa(rgb))
        np.testing.assert_array_equal(packed[0, 0], rgb[0, 0, [1, 2, 0, 1]])
        np.testing.assert_array_equal(pack(unpack(packed)), packed)

    def test_noise_simulator_respects_center_and_variance(self):
        rng = np.random.default_rng(781)
        signal = np.broadcast_to(np.array([.1, .3, .6], np.float32), (120000, 3))
        profile = np.array([[.001, .000002], [.002, .000008], [.003, .000014]], np.float32)
        noisy = noisy_cfa(signal, profile, rng)
        np.testing.assert_allclose(noisy.mean(axis=0), signal[0], atol=.0006)
        expected = signal[0] * profile[:, 0] + profile[:, 1]
        np.testing.assert_allclose(noisy.var(axis=0), expected, rtol=.025)

    def test_tiles_match_one_forward_with_identical_border_context(self):
        torch.manual_seed(13)
        model = BayerResidual().eval()
        planes = np.random.default_rng(11).normal(.25, .05, (39, 53, 4)).astype(np.float32)
        profile = np.tile([.001, .000003], (3, 1)).astype(np.float32)
        normalized, actual_sigma = predict_tiled(planes, profile, lambda x: torch_invoke(model, x), tile=16)
        inputs, expected_sigma = features(planes, profile)
        padded = np.pad(inputs, ((HALO, HALO), (HALO, HALO), (0, 0)))
        expected = torch_invoke(model, padded)[HALO:-HALO, HALO:-HALO]
        np.testing.assert_allclose(normalized, expected, atol=1e-7)
        np.testing.assert_array_equal(actual_sigma, expected_sigma)

    def test_restoration_preserves_saturation_and_bounds_correction(self):
        photosites = np.full((9, 12, 4), .34, np.float32)
        photosites[4, 6] = 1.02
        sigma = np.full_like(photosites, .02)
        proposed = np.full_like(photosites, 200)
        for guarded in (False, True):
            output = restore(photosites, proposed, sigma, guarded)
            np.testing.assert_array_equal(output[4, 6], photosites[4, 6])
            self.assertLessEqual(float(np.max(output - photosites)), .060001)

    def test_hybrid_uses_classical_result_on_flat_input(self):
        photosites = np.full((15, 13, 4), .34, np.float32)
        classical = photosites - .02
        noisy_proposal = np.random.default_rng(14).normal(.36, .12, photosites.shape).astype(np.float32)
        actual = blend_structures(photosites, classical, noisy_proposal, np.full_like(photosites, .02))
        np.testing.assert_array_equal(actual, classical)

    def test_pmrid_identity_undoes_phase_shift_and_exposure(self):
        mosaic = np.random.default_rng(18).uniform(.05, .95, (74, 124)).astype(np.float32)
        reproduced = restore_pmrid(mosaic, torch.nn.Identity(), 703)
        np.testing.assert_allclose(reproduced, mosaic, atol=1e-7)

    def test_stroke_metric_penalizes_erased_glyphs(self):
        reference, rows = text_target()
        self.assertAlmostEqual(stroke_dice(reference, reference, rows), 1.0)
        blank = np.full_like(reference, 1.0)
        self.assertAlmostEqual(stroke_dice(blank, reference, rows), 0.0)


if __name__ == "__main__":
    unittest.main()
