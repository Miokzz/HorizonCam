"""Build the 128px-context blind restoration graph. Fold the shuffle into the last convolution.
Use reviewed general-x4v3 weights and compare TFLite float16 storage with official PyTorch.
"""
import argparse
import json
import time
from pathlib import Path
from convert_realesrgan import load_reference, sha256, tf, torch, np

SIZE = 128


class ContextModel(tf.Module):
    def __init__(self, weights):
        super().__init__()
        self.layers = []
        order = [channel * 16 + offset for offset in range(16) for channel in range(3)]
        for i in range(67):
            weight = weights[f"body.{i}.weight"].numpy()
            if i % 2 == 0:
                bias = weights[f"body.{i}.bias"].numpy()
                if i == 66:
                    weight, bias = weight[order], bias[order]
                self.layers.append((tf.constant(weight.transpose(2, 3, 1, 0)), tf.constant(bias)))
            else:
                self.layers.append((tf.constant(weight.reshape(1, 1, 1, -1)),))

    @tf.function(input_signature=[tf.TensorSpec([1, SIZE, SIZE, 3], tf.float32)])
    def serve(self, rgb):
        base = rgb / 255.
        x = base
        for layer in self.layers:
            if len(layer) == 2:
                x = tf.nn.bias_add(tf.nn.conv2d(x, layer[0], 1, "SAME"), layer[1])
            else:
                x = tf.nn.relu(x) + tf.minimum(x, 0.) * layer[0]
        detail = tf.nn.depth_to_space(x, 4)
        residual = tf.raw_ops.ResizeNearestNeighbor(images=base, size=[SIZE*4,SIZE*4])
        return (detail + residual) * 255.


def main():
    parser = argparse.ArgumentParser()
    for key in ("weights", "output", "report"):
        parser.add_argument("--" + key, type=Path, required=True)
    args = parser.parse_args()
    ref, weights = load_reference(args.weights)
    module = ContextModel(weights)
    converter = tf.lite.TFLiteConverter.from_concrete_functions([module.serve.get_concrete_function()], module)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    graph = converter.convert()
    interpreter = tf.lite.Interpreter(model_content=graph, num_threads=4)
    interpreter.allocate_tensors()
    inp, out = interpreter.get_input_details()[0], interpreter.get_output_details()[0]
    assert list(inp["shape"]) == [1,128,128,3] and list(out["shape"]) == [1,512,512,3]
    rgb = np.random.default_rng(72).uniform(0,255,(1,SIZE,SIZE,3)).astype(np.float32)
    with torch.no_grad():
        before = ref(torch.from_numpy(rgb.transpose(0,3,1,2).copy())/255).numpy().transpose(0,2,3,1)*255
    interpreter.set_tensor(inp["index"], rgb)
    costs=[]
    for _ in range(4):
        start=time.perf_counter(); interpreter.invoke(); costs.append((time.perf_counter()-start)*1000)
    after = interpreter.get_tensor(out["index"])
    err = np.abs(before-after)
    assert float(err.mean()) < .4 and float(np.quantile(err,.999)) < 2.5
    args.output.write_bytes(graph)
    data = {"upstream_sha256":sha256(args.weights),"sha256":sha256(args.output),"bytes":len(graph),
            "tile":SIZE,"storage":"FP16 weights, FP32 I/O", "parity_input":"seed72 uniform RGB 0..255",
            "mae_against_official":float(err.mean()),"p999_abs":float(np.quantile(err,.999)),
            "max_abs":float(err.max()),"desktop_cpu4_tile_ms":costs}
    args.report.write_text(json.dumps(data,indent=2)+"\n")
    print(data)

if __name__ == "__main__":
    main()
