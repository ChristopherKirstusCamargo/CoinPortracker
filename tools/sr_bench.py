import os
ML_DIR = os.environ.get('ML_DIR', 'ml')
import numpy as np, time, tensorflow as tf
from PIL import Image
def interp(path, threads=2):
    it = tf.lite.Interpreter(model_path=path, num_threads=threads); it.allocate_tensors()
    return it, it.get_input_details()[0]['index'], it.get_output_details()[0]['index']
def run(m, x):
    it,i,o=m; it.set_tensor(i, x[None].astype(np.float32)); it.invoke(); return it.get_tensor(o)[0]
img=np.asarray(Image.open('app/src/androidTest/assets/sr/in128.png').convert('RGB').resize((256,256),Image.BICUBIC),dtype=np.float32)/255
for name in ['general-x4v3','animevideov3']:
    m128=interp(f'{ML_DIR}/{name}.tflite'); m256=interp(f'{ML_DIR}/{name}-t256.tflite')
    for tag,m,x in (('128',m128,img[:128,:128]),('256',m256,img)):
        run(m,x); t=time.time()
        for _ in range(3): y=run(m,x)
        dt=(time.time()-t)/3; print(name,tag,'ms/tile %.0f'%(dt*1000),'us/input px %.2f'%(dt*1e6/x.shape[0]**2))
    full=run(m256,img)
    for P in (8,12,16,24,36):
        x0=64-P; y=run(m128,img[x0:x0+128,x0:x0+128]); core=128-2*P
        a=np.clip(y[P*4:(P+core)*4,P*4:(P+core)*4],0,1); b=np.clip(full[64*4:(64+core)*4,64*4:(64+core)*4],0,1)
        d=np.abs(a-b)*255; print(f'  pad {P}: max {d.max():.2f} mean {d.mean():.4f}')
