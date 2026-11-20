import os
ML_DIR = os.environ.get('ML_DIR', 'ml')
import numpy as np, tensorflow as tf, sys
sys.argv=['x']; exec(open('convert.py').read().split("import tensorflow as tf")[0])
from PIL import Image
def torch_ref(sd, n, x):  # x: HWC float
    y = x.transpose(2,0,1)
    def conv(y, w, b):
        C,H,W = y.shape; p = np.pad(y, ((0,0),(1,1),(1,1)))
        cols = np.stack([p[:, i:i+H, j:j+W] for i in range(3) for j in range(3)], 1)  # C,9,H,W
        return np.einsum('ock,ckhw->ohw', w.reshape(w.shape[0], w.shape[1], 9), cols) + b[:, None, None]
    i = 0
    y = conv(y, sd['body.0.weight'], sd['body.0.bias']); a = sd['body.1.weight']; y = np.maximum(y,0) + a[:,None,None]*np.minimum(y,0); i = 2
    for _ in range(n):
        y = conv(y, sd[f'body.{i}.weight'], sd[f'body.{i}.bias']); a = sd[f'body.{i+1}.weight']; y = np.maximum(y,0) + a[:,None,None]*np.minimum(y,0); i += 2
    y = conv(y, sd[f'body.{i}.weight'], sd[f'body.{i}.bias'])
    C,H,W = y.shape; r=4; y = y.reshape(3, r, r, H, W).transpose(0, 3, 1, 4, 2).reshape(3, H*r, W*r)
    return y.transpose(1,2,0) + np.repeat(np.repeat(x, 4, 0), 4, 1)
img = np.asarray(Image.open(sys.argv[1] if len(sys.argv)>1 else ML_DIR + '/test.png').convert('RGB'), dtype=np.float32)/255
for name, n in [('animevideov3',16),('general-x4v3',32)]:
    sd = {k: v.astype(np.float32) for k, v in load_pth(f'{name}.pth').items()}
    it = tf.lite.Interpreter(model_path=f'{name}.tflite'); it.allocate_tensors()
    hr = img[:512, :512]; lr = np.asarray(Image.fromarray((hr*255).astype(np.uint8)).resize((128,128), Image.BICUBIC), dtype=np.float32)/255
    it.set_tensor(it.get_input_details()[0]['index'], lr[None]); it.invoke(); out = np.clip(it.get_tensor(it.get_output_details()[0]['index'])[0], 0, 1)
    ref = np.clip(torch_ref(sd, n, lr[:32,:32]), 0, 1)
    # border effects: compare the interior of the 32x32 crop
    print(name, 'max |tflite-ref| interior', float(np.abs(out[16:112,16:112] - ref[16:112,16:112]).max()))
    lan = np.asarray(Image.fromarray((lr*255).astype(np.uint8)).resize((512,512), Image.LANCZOS), dtype=np.float32)/255
    def psnr(a,b): return 10*np.log10(1/np.mean((a-b)**2))
    print(name, 'PSNR model', round(psnr(out, hr),2), 'lanczos', round(psnr(lan, hr),2))
    Image.fromarray((out*255).astype(np.uint8)).save(f'out_{name}.png')
