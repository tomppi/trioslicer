// Splat camera UBO layout and parameter activations, shared by all splat shaders.
//
// The splat buffer stores unconstrained optimiser parameters:
//   posScale     = world x, y, z, log(sigma_world)
//   colorOpacity = rgb colour logits, opacity logit
// The render and training shaders apply the activations below, and the training
// shaders differentiate through them.
//
// Each capture frame also has a 6-DoF correction (translation + small-angle
// rotation) optimised jointly with the splats, because raw ARCore poses drift.
#ifndef SPLAT_CAMERA_GLSL
#define SPLAT_CAMERA_GLSL

layout(binding = 0, std140) uniform Camera {
    mat4 view;    // world -> camera (OpenCV: +x right, +y down, +z forward)
    vec4 intr;    // fx, fy, cx, cy
    vec4 params;  // width, height, frame index, unused
} cam;

struct Splat {
    vec4 posScale;     // xyz world position, w log sigma
    vec4 colorOpacity; // rgb colour logits, w opacity logit
};

struct CamDelta {
    vec4 dt;  // camera-space translation correction (m)
    vec4 dw;  // small-angle rotation correction (rad)
};

layout(binding = 12, std430) buffer CamParams { CamDelta camDeltas[]; };

float sigmoid1(float x) { return 1.0 / (1.0 + exp(-x)); }
vec3 sigmoid3(vec3 x) { return 1.0 / (1.0 + exp(-x)); }

float splatSigma(vec4 posScale) { return exp(posScale.w); }
vec3 splatColor(vec4 colorOpacity) { return sigmoid3(colorOpacity.rgb); }
float splatOpacity(vec4 colorOpacity) { return sigmoid1(colorOpacity.w); }

// World point -> corrected camera coordinates.
vec4 applyPose(vec3 pw) {
    vec4 wp = cam.view * vec4(pw, 1.0);
    uint fi = uint(cam.params.z);
    CamDelta d = camDeltas[fi];
    vec3 x = wp.xyz;
    return vec4(x + cross(d.dw.xyz, x) + d.dt.xyz, 1.0);
}

const uint TILE = 16u;
const uint MAX_PER_TILE = 2048u;
#endif
