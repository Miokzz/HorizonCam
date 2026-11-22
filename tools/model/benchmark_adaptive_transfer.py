"""Representative host comparison of the native restorer and two earlier policies.
Actual TFLite inference; PIL bilinear approximates Android's filtered bitmap operations.
Not proof of Android/GPU parity. Private images/results must use external --private-out.
"""
import argparse
import hashlib
import io
import json
import os
import subprocess
import time
from pathlib import Path
os.environ.setdefault('TF_CPP_MIN_LOG_LEVEL','2')
os.environ.setdefault('TF_ENABLE_ONEDNN_OPTS','0')
import numpy as np
import tensorflow as tf
import torch
import lpips
from PIL import Image, ImageOps, ImageDraw, ImageFilter
from skimage import data
from skimage.metrics import peak_signal_noise_ratio, structural_similarity

BASELINE='6708aca'
ASSETS=Path('app/src/main/assets/ai')


def pack(a): return np.clip(np.floor(a+0.5),0,255).astype(np.uint8)
def resize(a,width,height):
    return np.asarray(Image.fromarray(pack(a)).resize((width,height),Image.Resampling.BILINEAR))
def luminance(a):
    p=a.astype(np.int32)
    return (77*p[...,0]+150*p[...,1]+29*p[...,2]+128)>>8

def scene(original,iso,seconds):
    h,w=original.shape[:2];side=min(h,w,15)
    noise_hist=np.zeros(256,np.int64);flat=0;textured=0;sharp=0;samples=0;total=0
    for gy in range(8):
        for gx in range(8):
            # Kotlin integer division; deterministic native neighborhoods.
            y=(h-side)*gy//7;x=(w-side)*gx//7
            p=luminance(original[y:y+side,x:x+side])
            center=p[1:-1,1:-1];left=p[1:-1,:-2];right=p[1:-1,2:];up=p[:-2,1:-1];down=p[2:,1:-1]
            grad=np.abs(right-left)+np.abs(down-up);lap=np.abs(4*center-left-right-up-down)
            noise_hist+=np.bincount(np.minimum(255,lap[grad<18]).ravel(),minlength=256)
            flat+=int(np.sum(grad<18));textured+=int(np.sum(grad>8));sharp+=int(np.sum((grad>8)&(lap>8)&(grad>20)))
            total+=int(center.sum());samples+=center.size
    median=int(np.searchsorted(np.cumsum(noise_hist),flat//2,side='left')) if flat else 0
    return {'noise':median/3.016 if flat else 0,'texture':textured/samples,'sharpFraction':sharp/max(1,textured),
            'meanY':total/samples,'iso':iso,'shutterSeconds':seconds}


def plan(w,h,out_w,out_h,zoom,s):
    dark=s['iso']>=900 or s['noise']>=3.4 or (s['meanY']<65 and s['shutterSeconds']>=1/40)
    degraded=zoom>=4 or dark or (zoom>=2 and s['sharpFraction']<0.18 and s['texture']>0.08)
    prior='max' if degraded else 'fast'
    limit=1152 if prior=='fast' else (656 if zoom>=8 else 560)
    ratio=min(1,limit/max(w,h));gw=max(1,int(w*ratio+0.5));gh=max(1,int(h*ratio+0.5))
    scale=4 if max(out_w,out_h)>2*max(gw,gh) else (2 if max(out_w,out_h)>max(gw,gh) else 1)
    return prior,gw,gh,scale


def reflect(indices,size):
    if size==1:return np.zeros_like(indices)
    folded=indices%(2*size-2)
    return np.where(folded<size,folded,2*size-2-folded)


class Model:
    def __init__(self,path):
        self.runner=tf.lite.Interpreter(model_path=str(path),num_threads=4)
        self.runner.allocate_tensors();self.input=self.runner.get_input_details()[0];self.output=self.runner.get_output_details()[0]
        self.tile=int(self.input['shape'][1]);self.path=path
    def infer(self,rgb):
        self.runner.set_tensor(self.input['index'],np.ascontiguousarray(rgb[None],np.float32));self.runner.invoke()
        return self.runner.get_tensor(self.output['index'])[0]


def area_reduce(a,scale):
    if scale==1:return pack(a)
    h,w=a.shape[:2]
    return pack(a.reshape(h//scale,scale,w//scale,scale,3).mean(axis=(1,3)))


def overlap_save(rgb,model,halo,scale):
    h,w=rgb.shape[:2];core=model.tile-2*halo
    output=np.zeros((h*scale,w*scale,3),np.uint8);calls=0
    for y in range(0,h,core):
        for x in range(0,w,core):
            rows=reflect(np.arange(y-halo,y-halo+model.tile),h);cols=reflect(np.arange(x-halo,x-halo+model.tile),w)
            prediction=model.infer(rgb[rows[:,None],cols[None,:]])
            hh=min(core,h-y);ww=min(core,w-x)
            crop=prediction[halo*4:halo*4+hh*4,halo*4:halo*4+ww*4]
            output[y*scale:(y+hh)*scale,x*scale:(x+ww)*scale]=area_reduce(crop,4//scale);calls+=1
    return output,calls


def transfer(base,low,prediction,zoom,s,blind,native_sampling=False,envelope=None,
             prior_mean=None,starting_policy=False):
    y=luminance(base);ly=luminance(low);py=luminance(prediction)
    padded=np.pad(y,1,mode='edge');left=padded[1:-1,:-2];right=padded[1:-1,2:];up=padded[:-2,1:-1];down=padded[2:,1:-1]
    contrast=np.maximum(np.abs(left-right),np.abs(up-down));lap=np.abs(4*y-left-right-up-down)
    high=y-ly;delta=py-ly
    r,g,b=[base[...,i].astype(np.int32) for i in range(3)]
    skin=(r>=g)&(g>=b)&(r-b>=16)&(r-b<=104)&(r>=56)&(g-b<50)
    reliable=((contrast>42)|(np.abs(high)>27))&(lap>12)
    noise=np.clip(s['noise']/7+s['iso']/4200,0,1)
    strength=np.where(reliable,np.where(skin,.28,.40),np.where(skin,.43+.10*noise,
        .65+.16*min(zoom/10,1)+.10*noise if blind else .50+.12*noise))
    cap=np.where(skin,17,np.where(reliable,24,np.where((contrast<3)&(np.abs(high)<2)&(np.abs(delta)>32),22,62 if blind else 38)))
    remove=np.where(reliable,.04,np.where(skin,.12+.20*noise,.20+.42*noise if blind else .08+.32*noise))
    if native_sampling:
        strength=strength*.5
        remove=np.minimum(remove,.05)
    weak=np.zeros(y.shape,bool)
    detail=delta
    if not native_sampling and envelope is not None:
        lower,upper=envelope
        span=upper-lower
        neutral=(base.max(axis=2).astype(int)-base.min(axis=2))<=24
        weak=(~reliable)&(y>=128)&neutral&(span<=72)
        if not starting_policy:
            weak=weak|(span<=12)|(neutral&(span<=48))
        if prior_mean is not None and not starting_policy:
            anchor=np.where(skin|(neutral&(span<=48)),.30,.60)
            detail=delta-anchor*(prior_mean-ly)
    correction=np.clip(detail,-cap,cap)*strength-high*remove
    if not native_sampling and envelope is not None:
        tolerance=np.maximum(3,np.minimum(8,span*.10))
        correction=np.where(weak,np.clip(correction,lower-tolerance-y,upper+tolerance-y),correction)
    chroma=np.clip(prediction.astype(np.int32)-low.astype(np.int32)-delta[...,None],-14,14)
    chroma_weight=np.where(weak,0.,.46)
    if not native_sampling and envelope is not None and not starting_policy:
        chroma_weight=np.where(~weak&skin&(span<=24),.18,chroma_weight)
    return pack(base+correction[...,None]+(strength*chroma_weight)[...,None]*chroma)


def source_envelope(original,upscale):
    y=luminance(original)
    p=np.pad(y,1,mode='edge')
    h,w=y.shape
    neighbors=[p[dy:dy+h,dx:dx+w] for dy in range(3) for dx in range(3)]
    lower=np.minimum.reduce(neighbors);upper=np.maximum.reduce(neighbors)
    return tuple(np.repeat(np.repeat(a,upscale,axis=0),upscale,axis=1) for a in (lower,upper))


def unsupported_contrast(original,output,upscale):
    lower,upper=source_envelope(original,upscale)
    base=resize(original,output.shape[1],output.shape[0]);y=luminance(base)
    span=upper-lower
    neutral=(base.max(axis=2).astype(int)-base.min(axis=2))<=24
    eligible=neutral&((span<=48)|((y>=128)&(span<=72)))
    darkness=np.maximum(0,lower-luminance(output))
    brightness=np.maximum(0,luminance(output)-upper)
    overshoot=np.maximum(darkness,brightness)
    return {'eligible_fraction':float(eligible.mean()),'mean_below_measured_min':float(darkness[eligible].mean()) if eligible.any() else 0,
            'p99_below_measured_min':float(np.quantile(darkness[eligible],.99)) if eligible.any() else 0,
            'p99_outside_measured_span':float(np.quantile(overshoot[eligible],.99)) if eligible.any() else 0,
            'fraction_over_12levels':float((overshoot[eligible]>12).mean()) if eligible.any() else 0}


def restore_v2(original,zoom,iso,upscale,seconds,models):
    h,w=original.shape[:2];ow=w*upscale;oh=h*upscale
    s=scene(original,iso,seconds);prior,gw,gh,scale=plan(w,h,ow,oh,zoom,s)
    guide=original if (gw,gh)==(w,h) else resize(original,gw,gh)
    then=time.perf_counter();neural,calls=overlap_save(guide,models[prior],4 if prior=='fast' else 34,scale)
    inference_ms=(time.perf_counter()-then)*1000
    prediction=resize(neural,ow,oh);low=resize(guide,ow,oh);base=resize(original,ow,oh)
    envelope=source_envelope(original,upscale) if upscale>1 else None
    starting=transfer(base,low,prediction,zoom,s,prior=='max',native_sampling=upscale==1,
                      envelope=envelope,starting_policy=True)
    preserve=(upscale==1 and zoom<4 and iso<=400 and s['meanY']>=65 and seconds<1/40
              and s['texture']>=.8 and s['sharpFraction']>=.58)
    mean=None
    if upscale>1 and (gw,gh)==(w,h):
        native_mean=pack(luminance(neural).reshape(h,scale,w,scale).mean(axis=(1,3)))
        mean=np.asarray(Image.fromarray(native_mean).resize((ow,oh),Image.Resampling.BILINEAR)).astype(np.int32)
    output=base if preserve else transfer(base,low,prediction,zoom,s,prior=='max',native_sampling=upscale==1,
                                          envelope=envelope,prior_mean=mean)
    details={'prior':'native' if preserve else prior,'guide':[gw,gh],'neural_scale':scale,
             'tiles':0 if preserve else calls,'host_tiles_ms':0 if preserve else inference_ms,
             'anchored':mean is not None,'scene':s}
    return output,details,starting


def old_protect(base,prediction,neighbour,amount):
    original=base.astype(np.float32);guess=prediction.astype(np.float32)
    coefficients=np.array([.299,.587,.114],np.float32)
    y=original@coefficients;delta=guess@coefficients-y
    edge=np.abs(y-neighbour.astype(np.float32)@coefficients)
    r,g,b=[original[...,i] for i in range(3)]
    skin=(r>45)&(r>=g)&(g>=b)&(r-b>=12)&(r-b<=110)
    limit=np.where(skin,8,np.where(edge>=36,12,np.where(edge<4,9,20)))
    luminance=np.clip(delta,-limit,limit)*amount
    return pack(original+luminance[...,None]+np.clip(guess-original-delta[...,None],-5,5)*amount)


def restore_old(original,zoom,iso,upscale,model):
    h,w=original.shape[:2];ow=w*upscale;oh=h*upscale
    sampling=4 if max(ow,oh)>=2400 else (2 if max(ow,oh)>=1000 else 1)
    scale=max(upscale,sampling);gw=(ow+scale-1)//scale;gh=(oh+scale-1)//scale
    guide=original if (gw,gh)==(w,h) else resize(original,gw,gh)
    baseline=resize(original,ow,oh);output=np.zeros_like(baseline);weights=np.zeros((oh,ow),np.int32)
    amount=.24+.50*np.clip((max(1,zoom)-1)/9,0,1)+.12*np.clip((max(100,iso)-100)/1500,0,1)
    def origins(size):
        values=[0]
        while values[-1]+32<size:values.append(min(values[-1]+24,size-32))
        return values
    then=time.perf_counter();calls=0
    for top in origins(gh):
        for left in origins(gw):
            rows=reflect(np.arange(top-9,top+41),gh);cols=reflect(np.arange(left-9,left+41),gw)
            raw=model.infer(guide[rows[:,None],cols[None,:]])
            x0=left*scale;y0=top*scale;tw=min(min(32,gw-left)*scale,ow-x0);th=min(min(32,gh-top)*scale,oh-y0)
            prediction=area_reduce(raw[36:36+th*(4//scale),36:36+tw*(4//scale)],4//scale)
            measured=baseline[y0:y0+th,x0:x0+tw]
            neighbour=np.concatenate([measured[:,:1],measured[:,:-1]],axis=1)
            if th>1:neighbour[1:,0]=measured[:-1,0]
            restored=old_protect(measured,prediction,neighbour,amount)
            stride=4//scale
            def weight(position,extent):return np.minimum(1,np.minimum(position+1,extent-position)/32)
            wx=weight(np.arange(tw)*stride,tw*stride);wy=weight(np.arange(th)*stride,th*stride)
            weight_map=np.maximum(1,np.floor(64*wy[:,None]*wx[None,:]+.5).astype(np.int32))
            previous=weights[y0:y0+th,x0:x0+tw];fraction=weight_map/(previous+weight_map)
            output[y0:y0+th,x0:x0+tw]=pack(output[y0:y0+th,x0:x0+tw]+(restored.astype(float)-output[y0:y0+th,x0:x0+tw])*fraction[...,None])
            weights[y0:y0+th,x0:x0+tw]+=weight_map;calls+=1
    assert np.all(weights>0)
    return output,{'prior':'x4v3-50','guide':[gw,gh],'neural_scale':scale,'tiles':calls,'elapsed_ms':(time.perf_counter()-then)*1000}


def degrade(truth,level):
    noise,jpeg,blur={'clean':(0,100,0),'mild':(3,75,.35),'strong':(10,35,.8)}[level]
    result=Image.fromarray(pack(truth+np.random.default_rng(20261121).normal(0,noise,truth.shape)))
    if blur:result=result.filter(ImageFilter.GaussianBlur(blur))
    if jpeg<100:
        buffer=io.BytesIO();result.save(buffer,format='JPEG',quality=jpeg,subsampling=0);result=Image.open(io.BytesIO(buffer.getvalue())).convert('RGB')
    return np.asarray(result)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--private-input',type=Path,action='append',default=[])
    parser.add_argument('--private-out',type=Path)
    parser.add_argument('--large',action='store_true')
    parser.add_argument('--holdout',action='store_true',help='Run additional crops from the same source photos')
    args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2);metric=lpips.LPIPS(net='alex',verbose=False).eval()
    def tensor(a):return torch.from_numpy(a.transpose(2,0,1).copy()).float()[None]/127.5-1
    def scores(reference,prediction):
        with torch.inference_mode():perceptual=float(metric(tensor(reference),tensor(prediction)).item())
        return {'psnr_db':float(peak_signal_noise_ratio(reference,prediction,data_range=255)),
            'ssim':float(structural_similarity(reference,prediction,channel_axis=2,data_range=255)),
            'lpips_alex':perceptual,'mae':float(np.abs(reference.astype(float)-prediction).mean())}
    models={'fast':Model(ASSETS/'QuickSRNet-small-x4-128.tflite'),'max':Model(ASSETS/'RealESRGAN-context-f16.tflite'),
            'old':Model(Path('app/src/androidTest/assets/baseline/x4v3.tflite'))}
    code_paths=['NativeRestorer.kt','PhotoReconstruction.kt','InferenceRuntime.kt']
    current_hashes={name:hashlib.sha256((Path('app/src/main/java/com/miokzz/horizoncam/ai')/name).read_bytes()).hexdigest() for name in code_paths}
    report={'scope':'REPRESENTATIVE HOST E2E, CPU4threads actualTFLite;PIL bilinear approximates Android filtered bitmap;noGPU/deviceparity',
            'baseline_git':subprocess.check_output(['git','rev-parse',BASELINE],text=True).strip(),'current_source_sha256':current_hashes,
            'models':{k:hashlib.sha256(v.path.read_bytes()).hexdigest() for k,v in models.items()},'cases':[]}
    grass=data.grass();brick=data.brick()
    fixtures={'foliage':np.repeat(grass[...,None],3,2),'buildings_brick':np.repeat(brick[...,None],3,2),
              'fur':data.chelsea(),'face':data.astronaut()[40:300,120:380]}
    chart=Image.fromarray(resize(fixtures['buildings_brick'],192,192));draw=ImageDraw.Draw(chart)
    draw.rectangle((10,80,182,114),fill='white');draw.text((13,87),'HORIZON A0123 / 456',fill='black',stroke_width=0)
    fixtures['text']=np.asarray(chart)
    chart=Image.new('RGB',(192,192))
    draw=ImageDraw.Draw(chart)
    for row,(background,foreground) in enumerate(((32,52),(60,85),(115,140),(200,170),(225,209))):
        draw.rectangle((0,row*38,191,row*38+37),fill=(background,)*3)
        draw.text((30,row*38+10),'HORIZON A0123 / 456',fill=(foreground,)*3,stroke_width=1)
    fixtures['weak_text']=np.asarray(chart)
    if args.holdout:
        for name in ['foliage','buildings_brick','fur','face']:
            array=fixtures[name]
            h,w=array.shape[:2]
            fixtures[name+'_holdout']=array[h//3:,:w*2//3]
    def execute(name,truth,observed,zoom,iso,upscale,seconds,outdir,no_ref=False):
        before=resize(observed,observed.shape[1]*upscale,observed.shape[0]*upscale)
        old,old_plan=restore_old(observed,zoom,iso,upscale,models['old'])
        new,new_plan,starting=restore_v2(observed,zoom,iso,upscale,seconds,models)
        reference=before if no_ref else truth
        case={'name':name,'zoom':zoom,'iso':iso,'upscale':upscale,'input':[observed.shape[1],observed.shape[0]],'output':[new.shape[1],new.shape[0]],
              'reference_scope':'NO_GROUND_TRUTH_camera_conservation_only' if no_ref else 'controlled_reference_samefield_samesampling',
              'old_plan':old_plan,'v2_plan':new_plan,
              'metrics':{'input':scores(reference,before),'baseline6708aca':scores(reference,old),
                         'starting_v3':scores(reference,starting),'candidate':scores(reference,new)},
              'unmeasured_contrast':{label:unsupported_contrast(observed,image,upscale) for label,image in
                                     [('input',before),('starting_v3',starting),('candidate',new)]}}
        # A true100%-pixel-center crop avoids a resized collage hiding texture loss.
        h,w=new.shape[:2];crop_w=min(w,192);crop_h=min(h,192);x=(w-crop_w)//2;y=(h-crop_h)//2
        strip=Image.new('RGB',(crop_w*5,crop_h+24))
        for i,(label,array) in enumerate([('reference' if not no_ref else 'camera',reference),('input',before),
                                          ('baseline6708aca',old),('starting_v3',starting),('candidate',new)]):
            strip.paste(Image.fromarray(array[y:y+crop_h,x:x+crop_w]),(i*crop_w,24));ImageDraw.Draw(strip).text((i*crop_w+3,5),label,fill='white')
        strip.save(outdir/(name+'-100pct.png'))
        print(name,'start',round(case['metrics']['starting_v3']['lpips_alex'],4),
              'candidate',round(case['metrics']['candidate']['lpips_alex'],4),new_plan['prior'],flush=True)
        return case
    for name,array in fixtures.items():
        truth=resize(array,192,192)
        for level,zoom,iso in [('mild',1.,200),('strong',4.,1200)]:
            observed=degrade(truth,level)
            report['cases'].append(execute(name+'-'+level+'-native',truth,observed,zoom,iso,1,1/60,args.out))
        for upscale,zoom in [(2,10.),(4,30.)]:
            observed=degrade(resize(truth,192//upscale,192//upscale),'mild')
            report['cases'].append(execute(f'{name}-mild-{upscale}x',truth,observed,zoom,400,upscale,1/120,args.out))
    if args.large:
        # Triggers real V2guide560/oldguide512 from1024-long-edge input.
        truth=resize(fixtures['face'],1024,768);observed=degrade(truth,'strong')
        report['cases'].append(execute('large-face-strong-guide-selection',truth,observed,4.,1200,1,1/40,args.out))
    final_hashes={name:hashlib.sha256((Path('app/src/main/java/com/miokzz/horizoncam/ai')/name).read_bytes()).hexdigest() for name in code_paths}
    report['runtime_source_changed_during_run']=final_hashes!=current_hashes
    (args.out/'adaptive-benchmark.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    if args.private_input:
        assert args.private_out is not None
        args.private_out.mkdir(parents=True,exist_ok=True);private=[]
        for index,path in enumerate(args.private_input):
            image=ImageOps.exif_transpose(Image.open(path)).convert('RGB');a=np.asarray(image);h,w=a.shape[:2]
            crop=a[h//2-96:h//2+96,w//2-96:w//2+96]
            for zoom,iso in [(1.,200),(4.,1200)]:
                private.append(execute(f'private{index}-native-z{zoom}',None,crop,zoom,iso,1,1/60,args.private_out,True))
        (args.private_out/'private-adaptive.json').write_text(json.dumps(private,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':main()
