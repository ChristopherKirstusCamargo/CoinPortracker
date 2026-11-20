import os
ML_DIR = os.environ.get('ML_DIR', 'ml')
import zipfile, pickle, numpy as np, sys, collections
def load_pth(path):
    z = zipfile.ZipFile(path); names = z.namelist()
    root = names[0].split('/')[0]
    class Storage:
        def __init__(self, key, dtype): self.key, self.dtype = key, dtype
    def rebuild(storage, offset, size, stride, *a):
        raw = np.frombuffer(z.read(f'{root}/data/{storage.key}'), dtype=storage.dtype)
        n = int(np.prod(size)) if len(size) else 1
        arr = np.lib.stride_tricks.as_strided(raw[offset:], shape=size, strides=[s*raw.itemsize for s in stride]) if len(size) else raw[offset:offset+1].reshape(())
        return np.array(arr)
    class U(pickle.Unpickler):
        def find_class(self, mod, name):
            if name == '_rebuild_tensor_v2': return rebuild
            if name == 'FloatStorage': return np.float32
            if name == 'HalfStorage': return np.float16
            if mod == 'collections' and name == 'OrderedDict': return collections.OrderedDict
            if name == '_rebuild_parameter': return lambda d, *a: d
            return super().find_class(mod, name)
        def persistent_load(self, pid):
            _, typ, key, loc, n = pid
            return Storage(key, typ if isinstance(typ, type) else np.float32)
    d = U(z.open(f'{root}/data.pkl')).load()
    return d.get('params_ema', d.get('params', d))

import tensorflow as tf
def build(sd, num_conv, tile=128):
    layers = []
    idx = 0
    def convw(i, perm=None):
        w = np.transpose(sd[f'body.{i}.weight'], (2, 3, 1, 0)).astype(np.float32); b = sd[f'body.{i}.bias'].astype(np.float32)
        if perm is not None: w = w[..., perm]; b = b[perm]
        return tf.constant(w), tf.constant(b)
    convs = [convw(0)]; prelus = [tf.constant(sd['body.1.weight'].astype(np.float32))]; i = 2
    for _ in range(num_conv): convs.append(convw(i)); prelus.append(tf.constant(sd[f'body.{i+1}.weight'].astype(np.float32))); i += 2
    perm = [c * 16 + a * 4 + b for a in range(4) for b in range(4) for c in range(3)]
    last = convw(i, perm)
    @tf.function(input_signature=[tf.TensorSpec([1, tile, tile, 3], tf.float32)])
    def f(x):
        y = x
        for (w, b), a in zip(convs, prelus):
            y = tf.nn.conv2d(y, w, 1, 'SAME') + b
            y = tf.maximum(y, 0.0) + a * tf.minimum(y, 0.0)
        y = tf.nn.conv2d(y, last[0], 1, 'SAME') + last[1]
        y = tf.nn.depth_to_space(y, 4)
        base = tf.repeat(tf.repeat(x, 4, axis=1), 4, axis=2)
        return y + base
    return f

import os
TILE = int(os.environ.get('SR_TILE', '128'))  # v3.2: 256 (menos sobreposição entre blocos = ~2-3x mais rápido)
for name, n in [('general-x4v3', 32), ('animevideov3', 16)]:
    sd = load_pth(f'{ML_DIR}/{name}.pth')
    print(name, len(sd), [k for k in list(sd)[:3]])
    m = build(sd, n, TILE)
    conv = tf.lite.TFLiteConverter.from_concrete_functions([m.get_concrete_function()], m)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]; conv.target_spec.supported_types = [tf.float16]
    open(f'{ML_DIR}/{name}' + ('' if TILE == 128 else f'-t{TILE}') + '.tflite', 'wb').write(conv.convert())
    # sanity: smooth gradient input should stay close to nearest upsample
    t = np.linspace(0, 1, TILE*TILE*3, dtype=np.float32).reshape(1,TILE,TILE,3)
    o = m(tf.constant(t)).numpy(); print(name, 'out', o.shape, float(o.min()), float(o.max()), 'mean diff vs input', float(abs(o[:, ::4, ::4] - t).mean()))
