"""Native1x candidate sweep using cached real model outputs; no app/runtime edits.
Spatial holdouts come from separate regions of the same four public photos, not independent datasets.
"""
import argparse
import json
from pathlib import Path
import numpy as np
import torch
import lpips
from PIL import Image,ImageDraw
from skimage import data
from skimage.metrics import peak_signal_noise_ratio,structural_similarity
import benchmark_adaptive_transfer as host


def components(original,zoom,iso,seconds,models):
    h,w=original.shape[:2];s=host.scene(original,iso,seconds)
    prior,gw,gh,scale=host.plan(w,h,w,h,zoom,s)
    guide=host.resize(original,gw,gh)
    neural,calls=host.overlap_save(guide,models[prior],4 if prior=='fast' else 34,scale)
    return host.resize(guide,w,h),host.resize(neural,w,h),s,prior,calls


def candidate(base,low,prediction,zoom,s,blind,alpha=1.,highpass_cap=1.):
    # Same V2 transfer; additionally limit fractional loss of original highpass
    # and scale the neural injection separately from measured high-frequency loss.
    y=host.luminance(base);ly=host.luminance(low);py=host.luminance(prediction)
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
    correction=np.clip(delta,-cap,cap)*strength*alpha-high*np.minimum(remove,highpass_cap)
    chroma=np.clip(prediction.astype(np.int32)-low.astype(np.int32)-delta[...,None],-14,14)
    return host.pack(base+correction[...,None]+strength[...,None]*.46*chroma*alpha)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--large',action='store_true');args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    cache=args.out/'cache';cache.mkdir(exist_ok=True)
    torch.set_num_threads(2);perceptual=lpips.LPIPS(net='alex',verbose=False).eval()
    def scores(truth,output):
        tensor=lambda a:torch.from_numpy(a.transpose(2,0,1).copy()).float()[None]/127.5-1
        with torch.inference_mode():p=float(perceptual(tensor(truth),tensor(output)).item())
        return {'psnr_db':float(peak_signal_noise_ratio(truth,output,data_range=255)),
            'ssim':float(structural_similarity(truth,output,channel_axis=2,data_range=255)),'lpips_alex':p}
    models={'fast':host.Model(host.ASSETS/'QuickSRNet-small-x4-128.tflite'),'max':host.Model(host.ASSETS/'RealESRGAN-context-f16.tflite')}
    fixtures={'foliage':np.repeat(data.grass()[...,None],3,2),'buildings':np.repeat(data.brick()[...,None],3,2),
              'fur':data.chelsea(),'face':data.astronaut()[40:300,120:380]}
    cases=[]
    for split in ['selection','spatial_holdout']:
        for name,array in fixtures.items():
            if split=='spatial_holdout':
                h,w=array.shape[:2];array=array[h//3:,:w*2//3]
            truth=host.resize(array,192,192)
            for level,zoom,iso in [('mild',1.,200),('strong',4.,1200)]:
                cases.append((f'{split}-{name}-{level}',split,truth,host.degrade(truth,level),zoom,iso))
    if args.large:
        truth=host.resize(fixtures['face'],1024,768)
        cases.append(('spatial_holdout-large-face-guide-selection','spatial_holdout',truth,host.degrade(truth,'strong'),4.,1200))
    report={'scope':'HOST representative native1x tuning; spatial holdout from same source photographs;not independent-camera proof',
            'policies':{},'cases':[]}
    policies=[('unchanged',None,None),('bypass',0.,0.)]
    policies += [(f'a{alpha}_h{cap}',alpha,cap) for alpha in [.1,.2,.35,.5,1.] for cap in [.0,.05,.10]]
    policies += [('selective',None,.05)]
    for name,split,truth,base,zoom,iso in cases:
        location=cache/(name+'.npz')
        if location.exists():
            stored=np.load(location);low=stored['low'];prediction=stored['prediction'];s=json.loads(str(stored['scene']));prior=str(stored['prior'])
        else:
            low,prediction,s,prior,calls=components(base,zoom,iso,1/60,models)
            np.savez_compressed(location,low=low,prediction=prediction,scene=json.dumps(s),prior=prior)
        entry={'name':name,'split':split,'scene':s,'prior':prior,'metrics':{}}
        for policy,alpha,cap in policies:
            if policy=='unchanged':output=host.transfer(base,low,prediction,zoom,s,prior=='max')
            elif policy=='selective':
                amount=.1 if zoom<4 and iso<900 else (.2 if s['texture']>.85 else 1.)
                output=candidate(base,low,prediction,zoom,s,prior=='max',amount,cap)
            else:output=candidate(base,low,prediction,zoom,s,prior=='max',alpha,cap)
            entry['metrics'][policy]=scores(truth,output)
            if policy in ['unchanged','selective']:
                # Native100%center samples, no collage resizing.
                h,w=base.shape[:2];x=(w-192)//2;y=(h-192)//2
                Image.fromarray(output[y:y+192,x:x+192]).save(args.out/(name+'-'+policy+'.png'))
        report['cases'].append(entry);print(name,'done',flush=True)
    for split in ['selection','spatial_holdout']:
        for policy,_,_ in policies:
            entries=[e['metrics'][policy] for e in report['cases'] if e['split']==split]
            report['policies'][split+':'+policy]={key:float(np.mean([e[key] for e in entries])) for key in ['psnr_db','ssim','lpips_alex']}
    (args.out/'tuning.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':main()
