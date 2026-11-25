"""Export and verify the learned Bayer residual convolutions as builtin-only LiteRT."""

import argparse
import json
import os
from pathlib import Path
import time

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
os.environ.setdefault("TF_ENABLE_ONEDNN_OPTS", "0")
import numpy as np
import tensorflow as tf
import torch

from compare_tele_raw import file_digest, require
from tele_raw_restore import HALO, WIDTH, features, load_network, torch_invoke


TILE = 128
SIZE = TILE + 2 * HALO
MODEL_INPUT = [1, SIZE, SIZE, 8]


class PortableResidual(tf.Module):
    def __init__(self, model):
        super().__init__()
        self.convolutions = [
            (tf.constant(layer.weight.detach().numpy().transpose(2, 3, 1, 0)),
             tf.constant(layer.bias.detach().numpy()))
            for layer in model.layers if isinstance(layer, torch.nn.Conv2d)
        ]

    @tf.function(input_signature=[tf.TensorSpec(MODEL_INPUT, tf.float32, name="bayer_and_noise")])
    def serve(self, inputs):
        value = inputs
        for index, (weights, bias) in enumerate(self.convolutions):
            value = tf.nn.bias_add(tf.nn.conv2d(value, weights, strides=1, padding="SAME"), bias)
            if index < len(self.convolutions) - 1:
                value = tf.nn.relu(value)
        return {"residual_in_noise_units": value}


def benchmark(model, graph, seed=4981):
    started = time.perf_counter()
    interpreter = tf.lite.Interpreter(model_content=graph, num_threads=4)
    interpreter.allocate_tensors()
    allocate_ms = 1000 * (time.perf_counter() - started)
    source, = interpreter.get_input_details()
    destination, = interpreter.get_output_details()
    require(source["shape"].tolist() == MODEL_INPUT and destination["shape"].tolist() == [1, SIZE, SIZE, 4],
            "Unexpected LiteRT tensor shapes")
    require(source["dtype"] == destination["dtype"] == np.float32, "Expected float32 IO")
    operators = sorted({op["op_name"] for op in interpreter._get_ops_details()})
    require(set(operators) <= {"CONV_2D", "RELU", "DELEGATE"}, "Unexpected LiteRT operator")
    rng = np.random.default_rng(seed)
    max_error_residual = 0.0
    max_error_linear = 0.0
    cold_ms = None
    for multiplier, luminance in ((.5, .035), (1., .25), (3.2, .70)):
        patch = rng.normal(luminance, .085, (SIZE, SIZE, 4)).astype(np.float32)
        profile = np.broadcast_to(np.array([.001, .000005], np.float32) * multiplier, (3, 2))
        values, sigma = features(patch, profile)
        reference = torch_invoke(model, values)
        interpreter.set_tensor(source["index"], values[None])
        started = time.perf_counter()
        interpreter.invoke()
        if cold_ms is None:
            cold_ms = 1000 * (time.perf_counter() - started)
        delta = np.abs(reference - interpreter.get_tensor(destination["index"])[0])
        max_error_residual = max(max_error_residual, float(delta.max()))
        max_error_linear = max(max_error_linear, float((delta * sigma).max()))
    require(max_error_residual < 3e-4 and max_error_linear < 3e-5, "LiteRT and PyTorch differ")
    timings = []
    for _ in range(30):
        started = time.perf_counter()
        interpreter.invoke()
        timings.append(1000 * (time.perf_counter() - started))
    return {"operators": [op for op in operators if op != "DELEGATE"], "hostDelegateObserved": "DELEGATE" in operators,
            "allocateMs": allocate_ms, "firstInvokeMs": cold_ms, "warmP50Ms": float(np.median(timings)),
            "warmP95Ms": float(np.percentile(timings, 95)), "peakResidualDifferenceSigma": max_error_residual,
            "peakLinearDifference": max_error_linear}


def convert(weights, output):
    output.mkdir(parents=True, exist_ok=False)
    torch.set_num_threads(4)
    model = load_network(weights)
    module = PortableResidual(model)
    converter = tf.lite.TFLiteConverter.from_concrete_functions([module.serve.get_concrete_function()], module)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    graph = converter.convert()
    evidence = benchmark(model, graph)
    graph_path = output / "bayer-residual-fp32.tflite"
    graph_path.write_bytes(graph)
    positions = SIZE * SIZE
    macs = positions * 9 * (8 * WIDTH + 2 * WIDTH * WIDTH + WIDTH * 4)
    full_tiles = ((1368 + TILE - 1) // TILE) * ((1824 + TILE - 1) // TILE)
    explicit_io = positions * (8 + 4) * 4
    report = {"weightsSha256": file_digest(weights), "graphSha256": file_digest(graph_path),
              "graphBytes": len(graph), "parameters": sum(p.numel() for p in model.parameters()),
              "inputShape": MODEL_INPUT, "outputShape": [1, SIZE, SIZE, 4], "haloPackedPixels": HALO,
              "macsPerTile": macs, "tilesForTele10Mp": full_tiles, "macsForTele10Mp": macs * full_tiles,
              "explicitFp32IOBytesPerTile": explicit_io,
              "estimatedFp32BytesWithTwoFeatureBuffers": explicit_io + positions * 2 * WIDTH * 4,
              "tensorflow": tf.__version__, "torch": str(torch.__version__), "hostCpu4Threads": evidence}
    (output / "litert-report.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    convert(args.weights, args.out)
