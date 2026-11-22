"""Convert official Real-ESRGAN SRVGG weights to builtin-only float32 CPU TFLite.

Python 3.12; requires torch (CPU), tensorflow-cpu, numpy. No training or quantization.
Input/output nominal RGB range is 0..255; official model overshoot is retained for parity.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
os.environ.setdefault("TF_ENABLE_ONEDNN_OPTS", "0")
import numpy as np
import tensorflow as tf
import torch
from tensorflow.lite.python import schema_py_generated as schema

WEIGHTS_SHA256 = "8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292"
WEIGHTS_URL = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load_reference(path):
    assert sha256(path) == WEIGHTS_SHA256, "Weights differ from the reviewed official release asset"
    checkpoint = torch.load(path, map_location="cpu", weights_only=True)
    weights = checkpoint.get("params_ema", checkpoint.get("params", checkpoint))
    source = (Path(__file__).parent / "vendor/srvgg_arch.py").read_text(encoding="utf-8")
    # Execute the unmodified official class, without pulling in BasicSR's unrelated registry stack.
    namespace = {"nn": torch.nn, "F": torch.nn.functional}
    exec(compile(source.split("@ARCH_REGISTRY.register()", 1)[1], "official_srvgg_arch.py", "exec"), namespace)
    reference = namespace["SRVGGNetCompact"](num_in_ch=3, num_out_ch=3, num_feat=64,
        num_conv=32, upscale=4, act_type="prelu")
    reference.load_state_dict(weights, strict=True)
    reference.eval()
    torch.set_num_threads(2)
    return reference, weights


class PortableSRVGG(tf.Module):
    def __init__(self, weights):
        super().__init__()
        self.layers = []
        for i in range(67):
            weight = weights[f"body.{i}.weight"].detach().cpu().numpy().astype(np.float32)
            if weight.ndim == 4:
                # PyTorch OIHW -> TensorFlow HWIO. TFLite converter owns its final OHWI storage.
                kernel = tf.constant(weight.transpose(2, 3, 1, 0))
                bias = tf.constant(weights[f"body.{i}.bias"].detach().cpu().numpy().astype(np.float32))
                self.layers.append((kernel, bias))
            else:
                # One PReLU slope per feature channel, shared over BOTH spatial axes.
                self.layers.append((tf.constant(weight.reshape(1, 1, 1, -1)),))

    @tf.function(input_signature=[tf.TensorSpec([1, 50, 50, 3], tf.float32, name="rgb_0_255")])
    def serve(self, rgb):
        source = rgb / tf.constant(255., tf.float32)
        value = source
        for layer in self.layers:
            if len(layer) == 2:
                kernel, bias = layer
                value = tf.nn.bias_add(tf.nn.conv2d(value, kernel, strides=1, padding="SAME"), bias)
            else:
                value = tf.nn.relu(value) + tf.minimum(value, 0.) * layer[0]
        # Torch PixelShuffle reads [output_channel, offset_y, offset_x] (DCR).
        # TF depth_to_space NHWC reads [offset_y, offset_x, output_channel] (CRD).
        # Explicit reorder prevents spatially interleaved R/G/B corruption.
        order = [channel * 16 + offset for offset in range(16) for channel in range(3)]
        value = tf.gather(value, order, axis=3)
        value = tf.nn.depth_to_space(value, 4, data_format="NHWC")
        nearest = tf.raw_ops.ResizeNearestNeighbor(images=source, size=[200, 200],
            align_corners=False, half_pixel_centers=False)
        return {"restored_rgb_0_255": (value + nearest) * tf.constant(255., tf.float32)}


def convert(weights_path, output):
    reference, weights = load_reference(weights_path)
    module = PortableSRVGG(weights)
    converter = tf.lite.TFLiteConverter.from_concrete_functions([module.serve.get_concrete_function()], module)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    converter.optimizations = []
    model = converter.convert()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(model)
    interpreter = tf.lite.Interpreter(model_content=model, num_threads=2)
    interpreter.allocate_tensors()
    inputs, outputs = interpreter.get_input_details(), interpreter.get_output_details()
    assert len(inputs) == len(outputs) == 1
    assert inputs[0]["shape"].tolist() == [1, 50, 50, 3]
    assert outputs[0]["shape"].tolist() == [1, 200, 200, 3]
    assert inputs[0]["dtype"] == outputs[0]["dtype"] == np.float32
    operators = sorted({op["op_name"] for op in interpreter._get_ops_details()})
    assert not any("Flex" in name or "CUSTOM" in name for name in operators)
    flatbuffer = schema.Model.GetRootAsModel(model, 0)
    versions = [{"builtin_code": flatbuffer.OperatorCodes(i).BuiltinCode(),
        "version": flatbuffer.OperatorCodes(i).Version()} for i in range(flatbuffer.OperatorCodesLength())]
    result = {"source_url": WEIGHTS_URL, "weights_sha256": sha256(weights_path),
        "model_sha256": sha256(output), "model_bytes": len(model), "operators": operators,
        "input": [1, 50, 50, 3], "output": [1, 200, 200, 3], "dtype": "float32",
        "nominal_rgb_range": [0, 255], "normalization_inside_graph": True,
        "operator_versions": versions, "license": "BSD-3-Clause",
        "license_file": "RealESRGAN_LICENSE_BSD3.txt",
        "architecture_source": "https://raw.githubusercontent.com/xinntao/Real-ESRGAN/v0.2.5.0/realesrgan/archs/srvgg_arch.py",
        "tensorflow": tf.__version__, "torch": torch.__version__}
    print(json.dumps(result, indent=2))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = convert(args.weights, args.output)
    if args.report:
        args.report.write_text(json.dumps(report, indent=2), encoding="utf-8")
