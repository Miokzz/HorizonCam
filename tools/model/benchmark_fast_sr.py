"""Controlled natural-image SR/restoration comparison. Private captures stay outside reports unless --private-out.
Uses pinned QuickSR + existing x4v3, CPU2threads. LPIPS downloads AlexNet once if not cached.
"""
import argparse
import io
import json
import time
import os
os.environ.setdefault("TF_ENABLE_ONEDNN_OPTS","0")
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL","2")
from pathlib import Path
import numpy as np
from PIL import Image, ImageFilter, ImageOps, ImageDraw
import tensorflow as tf
import torch
import lpips
from skimage import data
from skimage.metrics import peak_signal_noise_ratio, structural_similarity
from convert_quicksrnet import digest


def uint8(array):
    return np.clip(np.rint(array),0,255).astype(np.uint8)


def resize(array,size,method=Image.Resampling.BICUBIC):
    return np.asarray(Image.fromarray(uint8(array)).resize(size,method))


class TiledModel:
    def __init__(self,path,halo,overlap):
        self.runner=tf.lite.Interpreter(model_path=str(path),num_threads=2)
        self.runner.allocate_tensors()
        self.input=self.runner.get_input_details()[0]
        self.output=self.runner.get_output_details()[0]
        self.tile=int(self.input['shape'][1]);self.halo=halo;self.core=self.tile-2*halo;self.overlap=overlap
        self.path=path
    def __call__(self,rgb):
        height,width=rgb.shape[:2]
        result=np.zeros((height*4,width*4,3),np.float32)
        weights=np.zeros((height*4,width*4,1),np.float32)
        def origins(size):
            values=[0]
            while values[-1]+self.core<size:values.append(min(values[-1]+self.core-self.overlap,size-self.core))
            return values
        def reflect(index,size):
            if size==1:return np.zeros_like(index)
            folded=index%(size*2-2)
            return np.where(folded<size,folded,size*2-2-folded)
        started=time.perf_counter();calls=0
        for y in origins(height):
            for x in origins(width):
                ys=reflect(np.arange(y-self.halo,y-self.halo+self.tile),height)
                xs=reflect(np.arange(x-self.halo,x-self.halo+self.tile),width)
                patch=rgb[ys[:,None],xs[None,:]].astype(np.float32)[None]
                self.runner.set_tensor(self.input['index'],patch);self.runner.invoke();calls+=1
                output=self.runner.get_tensor(self.output['index'])[0]
                hh=min(self.core,height-y)*4;ww=min(self.core,width-x)*4
                core=output[self.halo*4:self.halo*4+hh,self.halo*4:self.halo*4+ww]
                wx=np.minimum(1,np.minimum(np.arange(ww)+1,ww-np.arange(ww))/max(1,self.overlap*4))
                wy=np.minimum(1,np.minimum(np.arange(hh)+1,hh-np.arange(hh))/max(1,self.overlap*4))
                weight=(wy[:,None]*wx[None,:])[...,None].astype(np.float32)
                result[y*4:y*4+hh,x*4:x*4+ww]+=core*weight
                weights[y*4:y*4+hh,x*4:x*4+ww]+=weight
        assert np.all(weights>0)
        return uint8(result/weights),{'elapsed_ms':(time.perf_counter()-started)*1000,'calls':calls}


def degradation(rgb,level):
    sigma,jpeg,blur={'clean':(0,100,0),'mild':(3,75,0.35),'strong':(10,35,0.8)}[level]
    rng=np.random.default_rng(20261121)
    array=uint8(rgb.astype(float)+rng.normal(0,sigma,rgb.shape))
    image=Image.fromarray(array)
    if blur:image=image.filter(ImageFilter.GaussianBlur(blur))
    if jpeg<100:
        buffer=io.BytesIO();image.save(buffer,format='JPEG',quality=jpeg,subsampling=0);image=Image.open(io.BytesIO(buffer.getvalue())).convert('RGB')
    return np.asarray(image)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--quick',type=Path,default=Path('app/src/main/assets/ai/QuickSRNet-small-x4-128.tflite'))
    parser.add_argument('--x4v3',type=Path,default=Path('app/src/main/assets/ai/RealESRGAN-general-x4v3.tflite'))
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--private-input',type=Path,action='append',default=[])
    parser.add_argument('--private-out',type=Path)
    args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2)
    perceptual=lpips.LPIPS(net='alex',verbose=False).eval()
    def metrics(truth,output):
        def tensor(a):return torch.from_numpy(np.ascontiguousarray(a.transpose(2,0,1))).float()[None]/127.5-1
        with torch.inference_mode():distance=float(perceptual(tensor(truth),tensor(output)).item())
        return {'psnr_db':float(peak_signal_noise_ratio(truth,output,data_range=255)),
                'ssim':float(structural_similarity(truth,output,channel_axis=2,data_range=255)),
                'lpips_alex':distance,'mae':float(np.abs(truth.astype(float)-output).mean())}
    models={'quicksr':TiledModel(args.quick,4,8),'x4v3':TiledModel(args.x4v3,9,8)}
    for model in models.values():model(np.full((32,32,3),127,np.uint8))
    fixtures={'vegetation_grass':data.grass(),'architecture_brick':data.brick(),
              'fur_cat':data.chelsea(),'face_astronaut':data.astronaut()[40:300,120:380]}
    report={'runtime':'TensorFlow2.20 CPU XNNPACK2threads;desktop,NOT handset/GPU','model_hashes':{k:digest(v.path) for k,v in models.items()},
            'perceptual':'lpips0.1.4 AlexNet ImageNet pretrained, RGB[-1,1], lower better','results':[]}
    for name,array in fixtures.items():
        if array.ndim==2:array=np.repeat(array[...,None],3,axis=2)
        truth=resize(array,(192,192))
        for level in ['clean','mild','strong']:
            low=degradation(resize(truth,(48,48)),level)
            outputs={'bicubic':resize(low,(192,192))}
            entry={'fixture':name,'task':'matched4xSR','degradation':level,'input':[48,48],'truth':[192,192],'metrics':{},'timings':{}}
            for key,model in models.items():outputs[key],entry['timings'][key]=model(low)
            for key,output in outputs.items():entry['metrics'][key]=metrics(truth,output)
            report['results'].append(entry)
            if level in ['clean','strong']:
                strip=Image.new('RGB',(192*4,216))
                for i,(key,output) in enumerate([('truth',truth)]+list(outputs.items())):
                    strip.paste(Image.fromarray(output),(192*i,24));ImageDraw.Draw(strip).text((192*i+4,5),key,fill='white')
                strip.save(args.out/f'{name}-{level}.png')
        truth=resize(array,(96,96))
        low=degradation(truth,'mild')
        entry={'fixture':name,'task':'matchedNative1xRestoration','degradation':'mild','input':[96,96],'truth':[96,96],'metrics':{'degraded':metrics(truth,low)},'timings':{}}
        for key,model in models.items():
            output,entry['timings'][key]=model(low)
            entry['metrics'][key]=metrics(truth,resize(output,(96,96),Image.Resampling.BOX))
        report['results'].append(entry)
        print(name,'done',flush=True)
    (args.out/'benchmark.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    if args.private_input:
        assert args.private_out is not None,'Private output directory required'
        args.private_out.mkdir(parents=True,exist_ok=True)
        private=[]
        for path in args.private_input:
            original=np.asarray(ImageOps.exif_transpose(Image.open(path)).convert('RGB'))
            h,w=original.shape[:2];crop=original[h//2-64:h//2+64,w//2-64:w//2+64]
            entry={'source':str(path),'native_crop':[128,128],'warning':'No ground truth; conservation relative to camera JPEG is NOT restoration accuracy','measurements':{}}
            strip=Image.new('RGB',(128*3,152));strip.paste(Image.fromarray(crop),(0,24));ImageDraw.Draw(strip).text((4,5),'camera',fill='white')
            for i,(key,model) in enumerate(models.items(),1):
                output,timing=model(crop);same=resize(output,(128,128),Image.Resampling.BOX)
                entry['measurements'][key]={'timing':timing,'camera_conservation':metrics(crop,same)}
                strip.paste(Image.fromarray(same),(128*i,24));ImageDraw.Draw(strip).text((128*i+4,5),key,fill='white')
            strip.save(args.private_out/(path.stem+'-comparison.png'));private.append(entry)
        (args.private_out/'private-benchmark.json').write_text(json.dumps(private,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':main()
