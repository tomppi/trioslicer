#!/usr/bin/env python3
"""
Export verlab/accelerated_features XFeat + LighterGlue to static QNN-HTP-friendly TFLite models,
and verify numerical equivalence against the official PyTorch / Kornia implementations.

Usage:
  python3 scripts/export_xfeat_lighterglue_tflite.py \
      --weights-dir /tmp/xfeat-export/accelerated_features/weights \
      --repo-dir /tmp/xfeat-export/accelerated_features \
      --out-dir easyscanapp/src/main/assets \
      --max-kp 200 \
      --test-img0 /tmp/imu-calib-1795527756266/f01198.jpg \
      --test-img1 /tmp/imu-calib-1795527756266/f01199.jpg
"""

import argparse
import os
import sys
import numpy as np
import torch
import tensorflow as tf


def fuse_conv_bn(sd, conv_w_key, bn_prefix, eps=1e-5):
    w = sd[conv_w_key].detach().cpu().numpy().astype(np.float32)  # [O, I, kH, kW]
    mean = sd[f"{bn_prefix}.running_mean"].detach().cpu().numpy().astype(np.float32)
    var = sd[f"{bn_prefix}.running_var"].detach().cpu().numpy().astype(np.float32)
    inv_std = 1.0 / np.sqrt(var + eps)
    w_fused = w * inv_std[:, None, None, None]
    b_fused = -mean * inv_std
    # PyTorch [O, I, kH, kW] -> TF NHWC [kH, kW, I, O]
    return np.transpose(w_fused, (2, 3, 1, 0)).copy(), b_fused.copy()


def raw_conv(sd, w_key, b_key):
    w = sd[w_key].detach().cpu().numpy().astype(np.float32)
    b = sd[b_key].detach().cpu().numpy().astype(np.float32)
    return np.transpose(w, (2, 3, 1, 0)).copy(), b.copy()


def tf_basic_layer(x, w, b, stride=1, padding=1):
    k = w.shape[0]
    if padding == 1 and stride == 2 and k == 3:
        x = tf.pad(x, [[0, 0], [1, 1], [1, 1], [0, 0]])
        x = tf.nn.conv2d(x, w, strides=[1, 2, 2, 1], padding="VALID")
    elif padding == 1 and stride == 1 and k == 3:
        x = tf.nn.conv2d(x, w, strides=[1, 1, 1, 1], padding="SAME")
    else:
        assert padding == 0 and stride == 1 and k == 1
        x = tf.nn.conv2d(x, w, strides=[1, 1, 1, 1], padding="VALID")
    x = tf.nn.bias_add(x, b)
    return tf.nn.relu(x)


class XFeatTF(tf.Module):
    """Exact static NHWC TensorFlow implementation of XFeatModel (640x480 grayscale)."""

    def __init__(self, sd, height=480, width=640):
        super().__init__()
        self.H = height
        self.W = width
        self.skip1_w, self.skip1_b = raw_conv(sd, "skip1.1.weight", "skip1.1.bias")

        def load_block(name, specs):
            layers = []
            for idx, (s, p) in enumerate(specs):
                w, b = fuse_conv_bn(sd, f"{name}.{idx}.layer.0.weight", f"{name}.{idx}.layer.1")
                layers.append((tf.constant(w), tf.constant(b), s, p))
            return layers

        self.block1 = load_block("block1", [(1, 1), (2, 1), (1, 1), (2, 1)])
        self.block2 = load_block("block2", [(1, 1), (1, 1)])
        self.block3 = load_block("block3", [(2, 1), (1, 1), (1, 0)])
        self.block4 = load_block("block4", [(2, 1), (1, 1), (1, 1)])
        self.block5 = load_block("block5", [(2, 1), (1, 1), (1, 1), (1, 0)])

        self.fusion_b = load_block("block_fusion", [(1, 1), (1, 1)])
        fw, fb = raw_conv(sd, "block_fusion.2.weight", "block_fusion.2.bias")
        self.fusion_last = (tf.constant(fw), tf.constant(fb))

        self.hm_b = load_block("heatmap_head", [(1, 0), (1, 0)])
        hw, hb = raw_conv(sd, "heatmap_head.2.weight", "heatmap_head.2.bias")
        self.hm_last = (tf.constant(hw), tf.constant(hb))

        self.kp_b = load_block("keypoint_head", [(1, 0), (1, 0), (1, 0)])
        kw, kb = raw_conv(sd, "keypoint_head.3.weight", "keypoint_head.3.bias")
        self.kp_last = (tf.constant(kw), tf.constant(kb))

        self.skip1_w = tf.constant(self.skip1_w)
        self.skip1_b = tf.constant(self.skip1_b)

    @tf.function(
        input_signature=[
            tf.TensorSpec(shape=[1, 480, 640, 1], dtype=tf.float32, name="image"),
        ]
    )
    def __call__(self, image):
        # Input `image` is already InstanceNorm2d-normalized ((I - mean) / sqrt(var + 1e-5))
        # by the caller in FP64/FP32 so QNN HTP avoids two 480x640 global DDR reductions.
        x = image

        # skip1: AvgPool2d(4, 4) + 1x1 conv
        s1 = tf.nn.avg_pool2d(x, ksize=4, strides=4, padding="VALID")
        s1 = tf.nn.bias_add(tf.nn.conv2d(s1, self.skip1_w, strides=[1, 1, 1, 1], padding="VALID"), self.skip1_b)

        # block1
        x1 = x
        for w, b, s, p in self.block1:
            x1 = tf_basic_layer(x1, w, b, stride=s, padding=p)

        # block2
        x2 = x1 + s1
        for w, b, s, p in self.block2:
            x2 = tf_basic_layer(x2, w, b, stride=s, padding=p)

        # block3 -> [1, 60, 80, 64]
        x3 = x2
        for w, b, s, p in self.block3:
            x3 = tf_basic_layer(x3, w, b, stride=s, padding=p)

        # block4 -> [1, 30, 40, 64]
        x4 = x3
        for w, b, s, p in self.block4:
            x4 = tf_basic_layer(x4, w, b, stride=s, padding=p)

        # block5 -> [1, 15, 20, 64]
        x5 = x4
        for w, b, s, p in self.block5:
            x5 = tf_basic_layer(x5, w, b, stride=s, padding=p)

        # Pyramid fusion (bilinear align_corners=False == TF default half_pixel_centers=True)
        x4_up = tf.image.resize(x4, [self.H // 8, self.W // 8], method="bilinear")
        x5_up = tf.image.resize(x5, [self.H // 8, self.W // 8], method="bilinear")
        f = x3 + x4_up + x5_up
        for w, b, s, p in self.fusion_b:
            f = tf_basic_layer(f, w, b, stride=s, padding=p)
        feats = tf.nn.bias_add(
            tf.nn.conv2d(f, self.fusion_last[0], strides=[1, 1, 1, 1], padding="VALID"),
            self.fusion_last[1],
        )

        # Dense L2-normalized 64-D descriptors: [1, 60, 80, 64]
        descriptors = feats * tf.math.rsqrt(tf.reduce_sum(feats * feats, axis=-1, keepdims=True) + 1e-4)

        # Reliability heatmap: [1, 60, 80]
        h = feats
        for w, b, s, p in self.hm_b:
            h = tf_basic_layer(h, w, b, stride=s, padding=p)
        h = tf.nn.bias_add(
            tf.nn.conv2d(h, self.hm_last[0], strides=[1, 1, 1, 1], padding="VALID"),
            self.hm_last[1],
        )
        heatmap = tf.squeeze(tf.nn.sigmoid(h), axis=-1)

        # Keypoint head: _unfold2d(x, ws=8) == space_to_depth(x, 8)
        u = tf.nn.space_to_depth(x, block_size=8)
        for w, b, s, p in self.kp_b:
            u = tf_basic_layer(u, w, b, stride=s, padding=p)
        kp_logits = tf.nn.bias_add(
            tf.nn.conv2d(u, self.kp_last[0], strides=[1, 1, 1, 1], padding="VALID"),
            self.kp_last[1],
        )
        kp_probs = tf.nn.softmax(kp_logits, axis=-1)[..., :64]
        scores = tf.squeeze(tf.nn.depth_to_space(kp_probs, block_size=8), axis=-1)

        return {
            "scores": tf.identity(scores, name="scores"),
            "descriptors": tf.identity(descriptors, name="descriptors"),
            "heatmap": tf.identity(heatmap, name="heatmap"),
        }


def tf_linear(x, w_t, b=None):
    y = tf.matmul(x, w_t)
    if b is not None:
        y = y + b
    return y


def tf_layer_norm(x, gamma, beta, eps=1e-4):
    mean = tf.reduce_mean(x, axis=-1, keepdims=True)
    var = tf.reduce_mean(tf.math.squared_difference(x, mean), axis=-1, keepdims=True)
    return (x - mean) * tf.math.rsqrt(var + eps) * gamma + beta


def tf_apply_rotary(t, cos, sin, max_kp, half_dim):
    # t: [1, MAX_KP, 96], cos/sin: [1, MAX_KP, 48]
    t2 = tf.reshape(t, [1, max_kp, half_dim, 2])
    t_even = t2[..., 0]
    t_odd = t2[..., 1]
    o_even = t_even * cos - t_odd * sin
    o_odd = t_odd * cos + t_even * sin
    out = tf.concat([o_even[..., None], o_odd[..., None]], axis=-1)
    return tf.reshape(out, [1, max_kp, half_dim * 2])


def tf_logsigmoid(z):
    # Numerically stable in FP16 and FP32: -relu(-z) - log(1 + exp(-|z|))
    return -tf.nn.relu(-z) - tf.math.log(1.0 + tf.math.exp(-tf.abs(z)))


class LighterGlueTF(tf.Module):
    """Exact static TensorFlow implementation of XFeat LighterGlue (64-D -> 96-D, 6 layers, 1 head)."""

    def __init__(self, sd, max_kp=200, img_w=640.0, img_h=480.0):
        super().__init__()
        self.max_kp = max_kp
        self.dim = 96
        self.half_dim = 48
        self.n_layers = 6
        self.img_w = float(img_w)
        self.img_h = float(img_h)

        # Strip optional 'matcher.' prefix and normalize legacy keys
        clean = {}
        for k, v in sd.items():
            if not k.startswith("matcher."):
                continue
            nk = k[len("matcher."):]
            for i in range(self.n_layers):
                nk = nk.replace(f"self_attn.{i}", f"transformers.{i}.self_attn")
                nk = nk.replace(f"cross_attn.{i}", f"transformers.{i}.cross_attn")
            clean[nk] = v.detach().cpu().numpy().astype(np.float32)

        def lin(prefix):
            w_t = tf.constant(clean[f"{prefix}.weight"].T.copy())
            b = tf.constant(clean[f"{prefix}.bias"].copy())
            return w_t, b

        self.in_w, self.in_b = lin("input_proj")
        self.pos_w = tf.constant(clean["posenc.Wr.weight"].T.copy())  # [2, 48]

        self.layers = []
        for i in range(self.n_layers):
            p_s = f"transformers.{i}.self_attn"
            # De-interleave Wqkv: Kornia uses unflatten(-1, (num_heads=1, head_dim=96, 3))
            wqkv = clean[f"{p_s}.Wqkv.weight"].reshape(self.dim, 3, self.dim)
            bqkv = clean[f"{p_s}.Wqkv.bias"].reshape(self.dim, 3)
            wq_t, bq = tf.constant(wqkv[:, 0, :].T.copy()), tf.constant(bqkv[:, 0].copy())
            wk_t, bk = tf.constant(wqkv[:, 1, :].T.copy()), tf.constant(bqkv[:, 1].copy())
            wv_t, bv = tf.constant(wqkv[:, 2, :].T.copy()), tf.constant(bqkv[:, 2].copy())
            sout_w, sout_b = lin(f"{p_s}.out_proj")
            sf0_w, sf0_b = lin(f"{p_s}.ffn.0")
            sln_w = tf.constant(clean[f"{p_s}.ffn.1.weight"].copy())
            sln_b = tf.constant(clean[f"{p_s}.ffn.1.bias"].copy())
            sf3_w, sf3_b = lin(f"{p_s}.ffn.3")

            p_c = f"transformers.{i}.cross_attn"
            cqk_w, cqk_b = lin(f"{p_c}.to_qk")
            cv_w, cv_b = lin(f"{p_c}.to_v")
            cout_w, cout_b = lin(f"{p_c}.to_out")
            cf0_w, cf0_b = lin(f"{p_c}.ffn.0")
            cln_w = tf.constant(clean[f"{p_c}.ffn.1.weight"].copy())
            cln_b = tf.constant(clean[f"{p_c}.ffn.1.bias"].copy())
            cf3_w, cf3_b = lin(f"{p_c}.ffn.3")

            self.layers.append({
                "s": (wq_t, bq, wk_t, bk, wv_t, bv, sout_w, sout_b, sf0_w, sf0_b, sln_w, sln_b, sf3_w, sf3_b),
                "c": (cqk_w, cqk_b, cv_w, cv_b, cout_w, cout_b, cf0_w, cf0_b, cln_w, cln_b, cf3_w, cf3_b),
            })

        last = self.n_layers - 1
        self.fp_w, self.fp_b = lin(f"log_assignment.{last}.final_proj")
        self.ma_w, self.ma_b = lin(f"log_assignment.{last}.matchability")

    def _run_ffn(self, x, msg, f0_w, f0_b, ln_w, ln_b, f3_w, f3_b):
        h = tf.concat([x, msg], axis=-1)
        h = tf_linear(h, f0_w, f0_b)
        h = tf_layer_norm(h, ln_w, ln_b)
        h = tf.nn.gelu(h, approximate=False)
        return x + tf_linear(h, f3_w, f3_b)

    def _self_attn(self, x, cos, sin, key_bias, w):
        wq_t, bq, wk_t, bk, wv_t, bv, out_w, out_b, f0_w, f0_b, ln_w, ln_b, f3_w, f3_b = w
        q = tf_apply_rotary(tf_linear(x, wq_t, bq), cos, sin, self.max_kp, self.half_dim)
        k = tf_apply_rotary(tf_linear(x, wk_t, bk), cos, sin, self.max_kp, self.half_dim)
        v = tf_linear(x, wv_t, bv)
        scale = tf.constant(self.dim ** -0.5, dtype=tf.float32)
        logits = tf.matmul(q * scale, k, transpose_b=True) + key_bias
        attn = tf.nn.softmax(logits, axis=-1)
        ctx = tf.matmul(attn, v)
        msg = tf_linear(ctx, out_w, out_b)
        return self._run_ffn(x, msg, f0_w, f0_b, ln_w, ln_b, f3_w, f3_b)

    def _cross_attn(self, x0, x1, key_bias0, key_bias1, w):
        qk_w, qk_b, v_w, v_b, out_w, out_b, f0_w, f0_b, ln_w, ln_b, f3_w, f3_b = w
        qk0 = tf_linear(x0, qk_w, qk_b)
        qk1 = tf_linear(x1, qk_w, qk_b)
        v0 = tf_linear(x0, v_w, v_b)
        v1 = tf_linear(x1, v_w, v_b)
        scale = tf.constant(self.dim ** -0.5, dtype=tf.float32)
        sim = tf.matmul(qk0 * scale, qk1, transpose_b=True)  # [1, M, N]
        a0 = tf.nn.softmax(sim + key_bias1, axis=-1)
        a1 = tf.nn.softmax(tf.transpose(sim, [0, 2, 1]) + key_bias0, axis=-1)
        m0 = tf_linear(tf.matmul(a0, v1), out_w, out_b)
        m1 = tf_linear(tf.matmul(a1, v0), out_w, out_b)
        x0 = self._run_ffn(x0, m0, f0_w, f0_b, ln_w, ln_b, f3_w, f3_b)
        x1 = self._run_ffn(x1, m1, f0_w, f0_b, ln_w, ln_b, f3_w, f3_b)
        return x0, x1

    def build_concrete(self):
        M = self.max_kp

        @tf.function(
            input_signature=[
                tf.TensorSpec(shape=[1, M, 2], dtype=tf.float32, name="keypoints0"),
                tf.TensorSpec(shape=[1, M, 64], dtype=tf.float32, name="descriptors0"),
                tf.TensorSpec(shape=[1], dtype=tf.int32, name="num_keypoints0"),
                tf.TensorSpec(shape=[1, M, 2], dtype=tf.float32, name="keypoints1"),
                tf.TensorSpec(shape=[1, M, 64], dtype=tf.float32, name="descriptors1"),
                tf.TensorSpec(shape=[1], dtype=tf.int32, name="num_keypoints1"),
            ]
        )
        def serve(keypoints0, descriptors0, num_keypoints0, keypoints1, descriptors1, num_keypoints1):
            shift = tf.constant([self.img_w * 0.5, self.img_h * 0.5], dtype=tf.float32)
            inv_scale = tf.constant(2.0 / max(self.img_w, self.img_h), dtype=tf.float32)
            kp0 = (keypoints0 - shift) * inv_scale
            kp1 = (keypoints1 - shift) * inv_scale

            idx = tf.constant(np.arange(M, dtype=np.int32)[None, :])  # [1, M]
            valid0 = idx < num_keypoints0[:, None]  # [1, M]
            valid1 = idx < num_keypoints1[:, None]  # [1, M]
            zero = tf.zeros([1, M], dtype=tf.float32)
            one = tf.ones([1, M], dtype=tf.float32)
            valid0_f = tf.where(valid0, one, zero)  # [1, M]
            valid1_f = tf.where(valid1, one, zero)  # [1, M]
            # -200 gives exp(-200)==0 in both FP16 and FP32 without stressing HTP FP16 range.
            neg_inf = tf.fill([1, M], tf.constant(-200.0, dtype=tf.float32))
            key_bias0 = tf.where(valid0, zero, neg_inf)[:, None, :]  # [1, 1, M]
            key_bias1 = tf.where(valid1, zero, neg_inf)[:, None, :]  # [1, 1, M]

            proj0 = tf.matmul(kp0, self.pos_w)
            proj1 = tf.matmul(kp1, self.pos_w)
            cos0, sin0 = tf.cos(proj0), tf.sin(proj0)
            cos1, sin1 = tf.cos(proj1), tf.sin(proj1)

            d0 = tf_linear(descriptors0, self.in_w, self.in_b)
            d1 = tf_linear(descriptors1, self.in_w, self.in_b)

            for lyr in self.layers:
                d0 = self._self_attn(d0, cos0, sin0, key_bias0, lyr["s"])
                d1 = self._self_attn(d1, cos1, sin1, key_bias1, lyr["s"])
                d0, d1 = self._cross_attn(d0, d1, key_bias0, key_bias1, lyr["c"])

            # Final MatchAssignment computed directly in probability space [0, 1]:
            #   exp(log_softmax0 + log_softmax1 + logsigmoid(z0) + logsigmoid(z1))
            # == softmax0 * softmax1 * sigmoid(z0) * sigmoid(z1).
            # Eliminates LOG_SOFTMAX, LOG, and EXP which underflowed on QNN HTP FP16 with padded masks.
            md_scale = tf.constant(self.dim ** -0.25, dtype=tf.float32)
            md0 = tf_linear(d0, self.fp_w, self.fp_b) * md_scale
            md1 = tf_linear(d1, self.fp_w, self.fp_b) * md_scale
            sim = tf.matmul(md0, md1, transpose_b=True)  # [1, M, M]
            z0 = tf_linear(d0, self.ma_w, self.ma_b)  # [1, M, 1]
            z1 = tf_linear(d1, self.ma_w, self.ma_b)  # [1, M, 1]

            p0 = tf.nn.softmax(sim + key_bias1, axis=-1)
            p1 = tf.transpose(
                tf.nn.softmax(tf.transpose(sim, [0, 2, 1]) + key_bias0, axis=-1),
                [0, 2, 1],
            )
            c0 = tf.nn.sigmoid(z0) * valid0_f[:, :, None]
            c1 = tf.transpose(tf.nn.sigmoid(z1) * valid1_f[:, :, None], [0, 2, 1])
            assign_prob = (p0 * p1) * (c0 * c1)  # [1, M, M] in [0, 1], exact 0 on padded rows/cols

            m0 = tf.argmax(assign_prob, axis=2, output_type=tf.int32)  # [1, M]
            m1 = tf.argmax(assign_prob, axis=1, output_type=tf.int32)  # [1, M]
            max0 = tf.reduce_max(assign_prob, axis=2)  # [1, M]

            m1_of_m0 = tf.gather(m1[0], m0[0])[None, :]
            mutual0 = tf.logical_and(
                tf.logical_and(tf.equal(idx, m1_of_m0), valid0),
                m0 < num_keypoints1[:, None],
            )

            mscores0 = tf.where(mutual0, max0, zero)
            neg1 = tf.fill([1, M], tf.constant(-1, dtype=tf.int32))
            matches0 = tf.where(mutual0, m0, neg1)

            return {
                "matches0": tf.identity(matches0, name="matches0"),
                "mscores0": tf.identity(mscores0, name="mscores0"),
            }

        return serve


def convert_to_tflite(concrete_fn, trackable_obj, out_path, fp16_weights=False):
    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete_fn], trackable_obj)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    if fp16_weights:
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
    tflite_bytes = converter.convert()
    with open(out_path, "wb") as f:
        f.write(tflite_bytes)
    return len(tflite_bytes)


def inspect_tflite(path):
    interp = tf.lite.Interpreter(model_path=path)
    interp.allocate_tensors()
    ins = [(d["name"], d["shape"].tolist(), d["dtype"].__name__) for d in interp.get_input_details()]
    outs = [(d["name"], d["shape"].tolist(), d["dtype"].__name__) for d in interp.get_output_details()]
    sigs = interp.get_signature_list()
    ops = sorted(set(op["op_name"] for op in interp._get_ops_details()))
    print(f"=== {os.path.basename(path)} ({os.path.getsize(path) / 1e6:.2f} MB) ===")
    print("  Inputs :", ins)
    print("  Outputs:", outs)
    print("  Sigs   :", sigs)
    print("  Ops    :", ops)
    return interp


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights-dir", required=True)
    ap.add_argument("--repo-dir", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--max-kp", type=int, default=200)
    ap.add_argument("--test-img0", required=True)
    ap.add_argument("--test-img1", required=True)
    args = ap.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    sd_xf = torch.load(os.path.join(args.weights_dir, "xfeat.pt"), map_location="cpu", weights_only=False)
    sd_lg = torch.load(os.path.join(args.weights_dir, "xfeat-lighterglue.pt"), map_location="cpu", weights_only=False)

    # 1. Export XFeat
    xf_tf = XFeatTF(sd_xf, height=480, width=640)
    xf_path = os.path.join(args.out_dir, "xfeat.tflite")
    convert_to_tflite(xf_tf.__call__.get_concrete_function(), xf_tf, xf_path, fp16_weights=False)
    xf_interp = inspect_tflite(xf_path)

    # 2. Export LighterGlue
    lg_tf = LighterGlueTF(sd_lg, max_kp=args.max_kp, img_w=640.0, img_h=480.0)
    lg_serve = lg_tf.build_concrete()
    lg_path = os.path.join(args.out_dir, "lighterglue.tflite")
    convert_to_tflite(lg_serve.get_concrete_function(), lg_tf, lg_path, fp16_weights=False)
    lg_interp = inspect_tflite(lg_path)

    # 3. Verify PyTorch vs TFLite on real Fold5 frames!
    sys.path.insert(0, args.repo_dir)
    from PIL import Image
    from modules.xfeat import XFeat
    from modules.lighterglue import LighterGlue

    im0 = np.array(Image.open(args.test_img0).convert("L").resize((640, 480)), dtype=np.float32) / 255.0
    im1 = np.array(Image.open(args.test_img1).convert("L").resize((640, 480)), dtype=np.float32) / 255.0

    xfeat_pt = XFeat(weights=os.path.join(args.weights_dir, "xfeat.pt"))
    with torch.inference_mode():
        t0 = torch.from_numpy(im0)[None, None]
        pt_feats, pt_kp_logits, pt_hm = xfeat_pt.net(t0)
        pt_desc = torch.nn.functional.normalize(pt_feats, dim=1).permute(0, 2, 3, 1).numpy()  # [1, 60, 80, 64]
        pt_scores = xfeat_pt.get_kpts_heatmap(pt_kp_logits).squeeze(1).numpy()  # [1, 480, 640]
        pt_hm_np = pt_hm.squeeze(1).numpy()  # [1, 60, 80]

    # Run TFLite XFeat via signature runner (pass InstanceNorm2d-normalized image, matching C++ fillGrayInput)
    xf_sig = xf_interp.get_signature_runner("serving_default")
    im0_norm = ((im0 - im0.mean()) / np.sqrt(im0.var() + 1e-5)).astype(np.float32)
    tf_out = xf_sig(image=im0_norm[None, :, :, None])
    print("\n=== XFeat PyTorch vs TFLite Numerical Check ===")
    print(f"  scores      max|diff| = {np.max(np.abs(pt_scores - tf_out['scores'])):.3e}")
    print(f"  descriptors max|diff| = {np.max(np.abs(pt_desc - tf_out['descriptors'])):.3e}")
    print(f"  heatmap     max|diff| = {np.max(np.abs(pt_hm_np - tf_out['heatmap'])):.3e}")

    # Now compare LighterGlue on BOTH full (MAX_KP) and partial (e.g. 137 vs 119) keypoint counts!
    lg_pt = LighterGlue(weights=os.path.join(args.weights_dir, "xfeat-lighterglue.pt"))
    lg_pt.net.conf.width_confidence = -1  # disable pruning to match static graph
    lg_pt.net.conf.depth_confidence = -1

    lg_sig = lg_interp.get_signature_runner("serving_default")
    for n0, n1 in [(args.max_kp, args.max_kp), (137, 119)]:
        d0 = xfeat_pt.detectAndCompute(torch.from_numpy(im0)[None, None], top_k=n0)[0]
        d1 = xfeat_pt.detectAndCompute(torch.from_numpy(im1)[None, None], top_k=n1)[0]
        k0 = d0["keypoints"].numpy().astype(np.float32)
        desc0 = d0["descriptors"].numpy().astype(np.float32)
        k1 = d1["keypoints"].numpy().astype(np.float32)
        desc1 = d1["descriptors"].numpy().astype(np.float32)
        c0, c1 = len(k0), len(k1)

        with torch.inference_mode():
            pt_res = lg_pt(
                {
                    "keypoints0": torch.from_numpy(k0)[None],
                    "keypoints1": torch.from_numpy(k1)[None],
                    "descriptors0": torch.from_numpy(desc0)[None],
                    "descriptors1": torch.from_numpy(desc1)[None],
                    "image_size0": torch.tensor([[640, 480]]),
                    "image_size1": torch.tensor([[640, 480]]),
                },
                min_conf=0.0,
            )
        pt_m0 = pt_res["matches0"][0].numpy()
        pt_ms0 = pt_res["matching_scores0"][0].numpy()

        kp0_pad = np.zeros((1, args.max_kp, 2), dtype=np.float32)
        kp1_pad = np.zeros((1, args.max_kp, 2), dtype=np.float32)
        d0_pad = np.zeros((1, args.max_kp, 64), dtype=np.float32)
        d1_pad = np.zeros((1, args.max_kp, 64), dtype=np.float32)
        kp0_pad[0, :c0] = k0
        kp1_pad[0, :c1] = k1
        d0_pad[0, :c0] = desc0
        d1_pad[0, :c1] = desc1

        tf_res = lg_sig(
            keypoints0=kp0_pad,
            descriptors0=d0_pad,
            num_keypoints0=np.array([c0], dtype=np.int32),
            keypoints1=kp1_pad,
            descriptors1=d1_pad,
            num_keypoints1=np.array([c1], dtype=np.int32),
        )
        tf_m0 = tf_res["matches0"][0, :c0]
        tf_ms0 = tf_res["mscores0"][0, :c0]

        pt_valid = (pt_m0 >= 0) & (pt_ms0 >= 0.1)
        tf_valid = (tf_m0 >= 0) & (tf_ms0 >= 0.1)
        exact_match = np.sum((pt_m0 == tf_m0) & pt_valid)
        score_diff = np.max(np.abs(pt_ms0 - tf_ms0))
        print(f"\n=== LighterGlue PyTorch vs TFLite (n0={c0}, n1={c1}) ===")
        print(f"  PT matches (>=0.1): {pt_valid.sum()}, TF matches (>=0.1): {tf_valid.sum()}, identical pairs: {exact_match}")
        print(f"  mscores0 max|diff| = {score_diff:.3e}")
        print(f"  padded slots valid count: {(tf_res['matches0'][0, c0:] >= 0).sum()} (expect 0)")


if __name__ == "__main__":
    main()
