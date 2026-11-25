"""Build a separate full-INT8 test graph from the validated Bayer model weights."""

import argparse
import json
from pathlib import Path

import numpy as np
import tensorflow as tf

from compare_tele_raw import file_digest, require
from export_tele_raw_restore import PortableResidual, SIZE
from tele_raw_restore import PHASE_CHANNELS, features, load_network, noisy_cfa, pack, procedural_patch, sample_cfa


WEIGHTS_SHA = "4a3f0fb91a163e229dbe5489c0d45f9432687e6d6b8bc28be21e85af94123190"


def representative(seed=9101):
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[:2 * SIZE, :2 * SIZE]
    for _ in range(64):
        truth = pack(sample_cfa(procedural_patch(rng, 2 * SIZE, y, x)))
        coefficients = np.array([[.001, .000005]], np.float32)
        profile = (coefficients * np.array([[3.08], [2.983], [2.704]], np.float32) *
                   np.exp(rng.uniform(-.6, .6, (1, 2)))).astype(np.float32)
        source = noisy_cfa(truth, profile[PHASE_CHANNELS], rng)
        yield [features(source, profile)[0][None]]


def quantize(weights, output):
    require(file_digest(weights) == WEIGHTS_SHA, "Expected the original 0712e96 trained weights")
    module = PortableResidual(load_network(weights))
    converter = tf.lite.TFLiteConverter.from_concrete_functions([module.serve.get_concrete_function()], module)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.int8
    converter.inference_output_type = tf.int8
    graph = converter.convert()
    interpreter = tf.lite.Interpreter(model_content=graph)
    interpreter.allocate_tensors()
    input_tensor, = interpreter.get_input_details()
    output_tensor, = interpreter.get_output_details()
    require(input_tensor["dtype"] == output_tensor["dtype"] == np.int8, "INT8 tensors expected")
    with output.open("xb") as handle:
        handle.write(graph)
    print(json.dumps({"sha256": file_digest(output), "bytes": len(graph),
                      "inputScaleZero": input_tensor["quantization"],
                      "outputScaleZero": output_tensor["quantization"],
                      "calibration": "64 procedural Bayer tiles, seed 9101"}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    quantize(args.weights, args.out)
