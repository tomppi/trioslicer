# SuperVINS conformance

_Rule: SuperVINS is the reference. OpenVINS is implementation groundwork, not the behavioural spec._

## Current deviations that matter

| Area | SuperVINS | Current app | Action |
|---|---|---|---|
| Feature count | max 150, dynamic output | fixed 100 | align to SuperVINS max, model permitting |
| Feature spacing | 20 px | 10 px | align to 20 px and measure |
| SuperPoint input | 512 long side, grayscale | 640x480 RGB | re-export SuperPoint if matching SuperVINS preprocessing |
| Tracker | SuperPoint+LightGlue only | optional KLT fallback | default off; remove if strict conformance |
| LightGlue threshold | config 0.5 / model | VIO 0.10, loop 0.20 | use SuperVINS matcher threshold semantics |
| F-RANSAC | 3 px, configurable, adaptive | 2.5/3/4 px adaptive | close; keep SuperVINS config names |
| Time offset | VINS-Fusion estimate_td projection term | OpenVINS clone-covariance augmentation | port projection td or pin offset until then |
| Backend | VINS-Fusion sliding-window optimizer | OpenVINS MSCKF | live-mesh path can stay, but true conformance needs backend decision |
| Loop detection | DBoW3 + SuperPoint | DBoW3 + SuperPoint | aligned |
| Loop verification | LightGlue + PnP RANSAC | LightGlue + PnP RANSAC | aligned |
| Pose graph | g2o 4-DoF | Java SE(3) | align to 4-DoF semantics |
| ZUPT | none | off | aligned |
| Live mesh | not in SuperVINS | PatchMap/TSDF extension | app-specific layer, not a substitute for SuperVINS front-end |

## Immediate order

1. Restore SuperVINS front-end semantics before touching more tuning: 150/20, F-RANSAC 3 px, no automatic KLT fallback by default.
2. Decide the time-offset path: SuperVINS estimate_td needs a projection factor; the current OpenVINS covariance augmentation is not SuperVINS behaviour.
3. Align pose-graph semantics to SuperVINS 4-DoF g2o rather than full SE(3) if loop correction is to be called SuperVINS.
4. Keep the live-mesh patch graph as an app extension, but source its features and loop rules from the SuperVINS front-end.

## Not to copy
- ROS, Docker, CUDA/ONNX Runtime.
- VINS keyframe/marginalization internals unless the backend is replaced.
