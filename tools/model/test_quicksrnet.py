"""Actual model/tile parity tests; run in restoration-venv, not lightweight CI Python."""
import unittest
from pathlib import Path
import numpy as np
import torch
from benchmark_fast_sr import TiledModel
from convert_quicksrnet import load_weights,reference

class QuickSrTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.reference=reference(load_weights(Path('../fast-model-validation/quicksrnet_small_4x_checkpoint_float32.pth.tar')))
        cls.model=TiledModel(Path('app/src/main/assets/ai/QuickSRNet-small-x4-128.tflite'),4,8)
        torch.set_num_threads(2)

    def test_complete_receptive_field_core_matches_full_official_torch_away_from_image_edges(self):
        rng=np.random.default_rng(21)
        rgb=rng.integers(0,256,(157,173,3),dtype=np.uint8)
        actual,timing=self.model(rgb)
        with torch.inference_mode():
            expected=self.reference(torch.from_numpy(rgb.transpose(2,0,1).copy()).float()[None]/255)[0].permute(1,2,0).numpy()*255
        # Mirrored full-image outer padding intentionally differs from official
        # zero boundary padding. Core seams must match full-image predictions.
        difference=np.abs(actual[20:-20,20:-20].astype(float)-expected[20:-20,20:-20])
        self.assertLess(float(difference.max()),0.501)
        self.assertEqual((157*4,173*4,3),actual.shape)
        self.assertEqual(4,timing['calls'])

    def test_odd_tiny_and_single_pixel_native_inputs_are_bounded_and_finite(self):
        rng=np.random.default_rng(3)
        for height,width in [(1,1),(17,29),(127,131)]:
            actual,_=self.model(rng.integers(0,256,(height,width,3),dtype=np.uint8))
            self.assertEqual((height*4,width*4,3),actual.shape)
            self.assertTrue(np.isfinite(actual).all())

if __name__=='__main__':unittest.main()
