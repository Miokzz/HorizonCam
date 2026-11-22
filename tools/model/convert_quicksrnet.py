"""Official QuickSRNetSmall 4x -> portable float32 RGB TFLite, no training.
Requires the existing restoration Python environment. Offline after obtaining pinned weights.
"""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
os.environ.setdefault("TF_ENABLE_ONEDNN_OPTS", "0")
import numpy as np
import tensorflow as tf
import torch

WEIGHTS_URL="https://github.com/quic/aimet-model-zoo/releases/download/phase_2_january_artifacts/quicksrnet_small_4x_checkpoint_float32.pth.tar"
WEIGHTS_SHA256="e7666018b6aa9590f95b932c8ceb1869e976d93a2a632cf73484e27cc2768978"


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load_weights(path):
    assert digest(path)==WEIGHTS_SHA256, "Unexpected upstream checkpoint"
    # Official checkpoint contains an optimizer defaultdict unsupported by Torch's
    # weights-only unpickler. This explicit trusted-load is restricted to the exact
    # reviewed official release hash; never use it for arbitrary input checkpoints.
    checkpoint=torch.load(path,map_location="cpu",weights_only=False)
    return checkpoint["state_dict"]


def reference(weights):
    model=torch.nn.Sequential(collections.OrderedDict([
        ("cnn",torch.nn.Sequential(torch.nn.Conv2d(3,32,3,padding=1),torch.nn.Hardtanh(0,1),
            torch.nn.Conv2d(32,32,3,padding=1),torch.nn.Hardtanh(0,1),
            torch.nn.Conv2d(32,32,3,padding=1),torch.nn.Hardtanh(0,1))),
        ("conv_last",torch.nn.Conv2d(32,48,3,padding=1)),
        ("clip",torch.nn.Hardtanh(0,1)),("shuffle",torch.nn.PixelShuffle(4))]))
    model.load_state_dict(weights,strict=True)
    model.eval()
    return model


class PortableQuickSR(tf.Module):
    def __init__(self,weights):
        super().__init__()
        self.layers=[]
        for name in ["cnn.0","cnn.2","cnn.4","conv_last"]:
            kernel=weights[name+".weight"].numpy().transpose(2,3,1,0)
            bias=weights[name+".bias"].numpy()
            if name=="conv_last":
                # Fold PyTorch DCR PixelShuffle -> TensorFlow CRD into final channels.
                order=[channel*16+offset for offset in range(16) for channel in range(3)]
                kernel=kernel[:,:,:,order];bias=bias[order]
            self.layers.append((tf.constant(kernel),tf.constant(bias)))

    @tf.function(input_signature=[tf.TensorSpec([1,128,128,3],tf.float32,name="rgb_0_255")])
    def serve(self,rgb):
        value=rgb/255.0
        for kernel,bias in self.layers:
            value=tf.clip_by_value(tf.nn.bias_add(tf.nn.conv2d(value,kernel,1,"SAME"),bias),0.0,1.0)
        return {"sr_rgb_0_255":tf.nn.depth_to_space(value,4)*255.0}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weights",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--report",type=Path,required=True)
    args=parser.parse_args()
    weights=load_weights(args.weights)
    torch.set_num_threads(2)
    model=reference(weights)
    module=PortableQuickSR(weights)
    converter=tf.lite.TFLiteConverter.from_concrete_functions([module.serve.get_concrete_function()],module)
    converter.target_spec.supported_ops=[tf.lite.OpsSet.TFLITE_BUILTINS]
    converted=converter.convert()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_bytes(converted)
    interpreter=tf.lite.Interpreter(model_content=converted,num_threads=2)
    interpreter.allocate_tensors()
    rng=np.random.default_rng(42)
    fixtures=[rng.uniform(0,255,(128,128,3)).astype(np.float32),
              np.stack(list(np.meshgrid(np.linspace(0,255,128),np.linspace(0,255,128),indexing="xy"))+[np.zeros((128,128))],axis=2).astype(np.float32)]
    errors=[]
    for rgb in fixtures:
        with torch.inference_mode():truth=model(torch.from_numpy(rgb.transpose(2,0,1).copy())[None]/255)[0].permute(1,2,0).numpy()*255
        interpreter.set_tensor(interpreter.get_input_details()[0]["index"],rgb[None]);interpreter.invoke()
        output=interpreter.get_tensor(interpreter.get_output_details()[0]["index"])[0]
        errors.append(float(np.max(np.abs(truth-output))))
    assert max(errors)<0.02, errors
    report={"weights_url":WEIGHTS_URL,"weights_sha256":WEIGHTS_SHA256,"model_sha256":digest(args.output),
            "model_bytes":len(converted),"input":[1,128,128,3],"output":[1,512,512,3],"dtype":"float32","rgb_range":[0,255],
            "normalization_inside_graph":True,"parameter_count":sum(x.numel() for x in model.parameters()),
            "parity_max_abs_0_255":errors,"operators":sorted({o["op_name"] for o in interpreter._get_ops_details()}),
            "license":"BSD-3-Clause","tensorflow":tf.__version__,"torch":torch.__version__}
    args.report.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(report,indent=2))

if __name__=="__main__":main()
