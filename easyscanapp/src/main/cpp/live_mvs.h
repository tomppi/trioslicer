#pragma once
/**
 * Coarse real-time Plane-Sweep Multi-View Stereo (MVS) driven by metric VIO/Board poses.
 *
 * Designed as a clean, scale-consistent rough-mesh generator that feeds directly into
 * [livetsdf::NativeTsdfVolume] so the metric laser can refine detail on top of it:
 *   - No monocular scale ambiguity or per-frame affine curvature warp.
 *   - Operates on 160x120 grayscale + RGB keyframes (exact same calibrated camera/focus/exposure).
 *   - 4-thread parallel 9-tap ZNCC across 48 inverse-depth planes around calibrated focus distance.
 *   - Strict correlation, peak-uniqueness, 3x3 speckle/median, and gentle pinhole-fill filtering.
 */

#include "live_tsdf.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <deque>
#include <thread>
#include <vector>

namespace livemvs {

using livetsdf::Camera;
using livetsdf::NativeTsdfVolume;
using livetsdf::Pose;

struct MvsStats {
    int keyframeCount = 0;
    int validPixels = 0;
    int elapsedMs = 0;
    int usedNeighbors = 0;
};

class LiveMvsEngine {
public:
    static constexpr int kW = 160;
    static constexpr int kH = 120;
    static constexpr int kMaxKeyframes = 14;
    static constexpr int kNumPlanes = 48;

    void reset() {
        std::lock_guard<std::mutex> op(opMtx_);
        std::lock_guard<std::mutex> g(kfMtx_);
        keyframes_.clear();
        hasLastKf_ = false;
    }

    int keyframeCount() const {
        std::lock_guard<std::mutex> g(kfMtx_);
        return static_cast<int>(keyframes_.size());
    }

    /**
     * Evaluates whether [pose] has moved far enough from the last stored keyframe
     * to merit adding a new MVS keyframe and running plane-sweep stereo.
     */
    bool shouldAcceptKeyframe(const Pose& pose, double minTransM = 0.032, double minRotDeg = 8.0) const {
        std::lock_guard<std::mutex> g(kfMtx_);
        if (!hasLastKf_ || keyframes_.empty()) return true;
        const Pose& last = keyframes_.back().pose;
        double c0[3], c1[3];
        last.cameraCentre(c0[0], c0[1], c0[2]);
        pose.cameraCentre(c1[0], c1[1], c1[2]);
        double dx = c1[0] - c0[0], dy = c1[1] - c0[1], dz = c1[2] - c0[2];
        double dist = std::sqrt(dx * dx + dy * dy + dz * dz);
        if (dist >= minTransM) return true;
        double tr = 0.0;
        for (int i = 0; i < 9; ++i) tr += last.r[i] * pose.r[i];
        double cosA = std::clamp((tr - 1.0) * 0.5, -1.0, 1.0);
        double deg = std::acos(cosA) * (180.0 / 3.14159265358979323846);
        return deg >= minRotDeg;
    }

    /**
     * Pushes a pre-downscaled 160x120 grayscale + RGB keyframe, runs multi-view plane-sweep
     * stereo against the best overlapping neighbor keyframes, and fuses the resulting metric
     * depth directly into [tsdf] (if non-null).
     */
    MvsStats pushKeyframeAndFuse(
        const uint8_t* gray160x120,
        const uint8_t* rgb160x120,
        const Pose& pose,
        const Camera& fullCam,
        double focusDepthM,
        NativeTsdfVolume* tsdf
    ) {
        std::lock_guard<std::mutex> op(opMtx_);
        MvsStats st;
        if (!gray160x120 || !rgb160x120 || fullCam.width <= 0.0 || fullCam.height <= 0.0) {
            st.keyframeCount = static_cast<int>(keyframes_.size());
            return st;
        }

        const double sx = static_cast<double>(kW) / fullCam.width;
        const double sy = static_cast<double>(kH) / fullCam.height;
        Camera scaledCam = fullCam;
        scaledCam.fx = fullCam.fx * sx;
        scaledCam.fy = fullCam.fy * sy;
        scaledCam.cx = fullCam.cx * sx;
        scaledCam.cy = fullCam.cy * sy;
        scaledCam.width = kW;
        scaledCam.height = kH;

        Keyframe kf;
        kf.pose = pose;
        kf.cam = scaledCam;
        pose.cameraCentre(kf.centre[0], kf.centre[1], kf.centre[2]);
        // Viewing axis (+Z in camera frame) expressed in world coordinates = row 2 of R.
        kf.viewDir[0] = pose.r[6];
        kf.viewDir[1] = pose.r[7];
        kf.viewDir[2] = pose.r[8];
        kf.gray.assign(gray160x120, gray160x120 + kW * kH);
        kf.rgb.assign(rgb160x120, rgb160x120 + kW * kH * 3);
        precomputePatchStats(kf);

        // Select up to 4 best overlapping neighbors and push [kf] under kfMtx_.
        std::vector<int> neighbors;
        {
            std::lock_guard<std::mutex> g(kfMtx_);
            neighbors = selectNeighbors(kf);
            keyframes_.push_back(std::move(kf));
            if (static_cast<int>(keyframes_.size()) > kMaxKeyframes) {
                keyframes_.pop_front();
                std::vector<int> shifted;
                for (int idx : neighbors) {
                    if (idx > 0) shifted.push_back(idx - 1);
                }
                neighbors.swap(shifted);
            }
            hasLastKf_ = true;
            st.keyframeCount = static_cast<int>(keyframes_.size());
        }
        st.usedNeighbors = static_cast<int>(neighbors.size());
        if (neighbors.empty()) return st;

        const Keyframe& ref = keyframes_.back();
        const double fDepth = std::clamp(focusDepthM, 0.22, 1.10);
        const double minDepth = std::max(0.14, fDepth * 0.42);
        const double maxDepth = std::min(1.50, std::max(fDepth * 2.25, minDepth + 0.35));

        std::vector<float> rawDepth(kW * kH, 0.0f);
        std::vector<float> rawConf(kW * kH, 0.0f);
        runPlaneSweep(ref, neighbors, minDepth, maxDepth, rawDepth, rawConf);

        std::vector<float> cleanDepth(kW * kH, 0.0f);
        std::vector<float> cleanConf(kW * kH, 0.0f);
        int valid = filterAndFillDepth(rawDepth, rawConf, cleanDepth, cleanConf);
        st.validPixels = valid;

        if (tsdf && valid >= 60) {
            std::lock_guard<std::mutex> vlck(tsdf->mtx);
            tsdf->integrateMetricDepth(
                ref.pose, ref.cam, cleanDepth.data(), cleanConf.data(),
                ref.rgb.data(), kW, kH, 0.65f);
        }
        return st;
    }

    /** Downscales an arbitrary YUV_420_888 camera frame directly into 160x120 gray + RGB. */
    static void downscaleYuvTo160x120(
        const uint8_t* yPlane, int yStride,
        const uint8_t* uPlane, const uint8_t* vPlane, int uvStride, int uvPixStride,
        int srcW, int srcH,
        uint8_t* outGray, uint8_t* outRgb
    ) {
        const int cw = std::max(1, srcW / 2);
        const int ch = std::max(1, srcH / 2);
        for (int dy = 0; dy < kH; ++dy) {
            int sy = std::min(srcH - 1, (dy * srcH + srcH / 2) / kH);
            int sy0 = std::max(0, sy - 1);
            int sy1 = std::min(srcH - 1, sy + 1);
            int cy = std::min(ch - 1, sy >> 1);
            const uint8_t* yRow0 = yPlane + sy0 * yStride;
            const uint8_t* yRowM = yPlane + sy * yStride;
            const uint8_t* yRow1 = yPlane + sy1 * yStride;
            const uint8_t* uRow = uPlane + cy * uvStride;
            const uint8_t* vRow = vPlane + cy * uvStride;
            int outRow = dy * kW;
            for (int dx = 0; dx < kW; ++dx) {
                int sx = std::min(srcW - 1, (dx * srcW + srcW / 2) / kW);
                int sx0 = std::max(0, sx - 1);
                int sx1 = std::min(srcW - 1, sx + 1);
                // 3x3 cross box filter suppresses aliasing before plane-sweep matching.
                int yv = (2 * yRowM[sx] + yRowM[sx0] + yRowM[sx1] + yRow0[sx] + yRow1[sx] + 3) / 6;
                outGray[outRow + dx] = static_cast<uint8_t>(yv);

                int cx = std::min(cw - 1, sx >> 1);
                int cb = static_cast<int>(uRow[cx * uvPixStride]) - 128;
                int cr = static_cast<int>(vRow[cx * uvPixStride]) - 128;
                int r = std::clamp(yv + ((359 * cr + 128) >> 8), 0, 255);
                int g = std::clamp(yv - ((88 * cb + 183 * cr + 128) >> 8), 0, 255);
                int b = std::clamp(yv + ((454 * cb + 128) >> 8), 0, 255);
                int o3 = (outRow + dx) * 3;
                outRgb[o3] = static_cast<uint8_t>(r);
                outRgb[o3 + 1] = static_cast<uint8_t>(g);
                outRgb[o3 + 2] = static_cast<uint8_t>(b);
            }
        }
    }

private:
    struct Keyframe {
        Pose pose;
        Camera cam;
        double centre[3]{};
        double viewDir[3]{};
        std::vector<uint8_t> gray;
        std::vector<uint8_t> rgb;
        // Precomputed 9-tap (5x5 stride-2) patch mean & 1/(sqrt(9)*std) for instant ZNCC.
        std::vector<float> patchMean;
        std::vector<float> patchInvNorm;
        std::vector<uint8_t> hasTexture;
    };

    struct RelPose {
        float r[9];
        float t[3];
        float fx, fy, cx, cy;
        const uint8_t* gray;
    };

    static constexpr int kTapDx[9] = { -2, 0, 2, -2, 0, 2, -2, 0, 2 };
    static constexpr int kTapDy[9] = { -2, -2, -2, 0, 0, 0, 2, 2, 2 };

    static void precomputePatchStats(Keyframe& kf) {
        kf.patchMean.assign(kW * kH, 0.0f);
        kf.patchInvNorm.assign(kW * kH, 0.0f);
        kf.hasTexture.assign(kW * kH, 0);
        for (int y = 3; y < kH - 3; ++y) {
            for (int x = 3; x < kW - 3; ++x) {
                float sum = 0.0f;
                float sum2 = 0.0f;
                for (int k = 0; k < 9; ++k) {
                    float v = static_cast<float>(kf.gray[(y + kTapDy[k]) * kW + (x + kTapDx[k])]);
                    sum += v;
                    sum2 += v * v;
                }
                float mean = sum * (1.0f / 9.0f);
                float var = std::max(0.0f, sum2 - 9.0f * mean * mean);
                // Require minimum local contrast (std >= 5.0 gray levels) so flat walls don't match noise.
                if (var >= 9.0f * 25.0f) {
                    int idx = y * kW + x;
                    kf.patchMean[idx] = mean;
                    kf.patchInvNorm[idx] = 1.0f / std::sqrt(var);
                    kf.hasTexture[idx] = 1;
                }
            }
        }
    }

    std::vector<int> selectNeighbors(const Keyframe& ref) const {
        struct Cand { int idx; double score; };
        std::vector<Cand> cands;
        for (int i = 0; i < static_cast<int>(keyframes_.size()); ++i) {
            const Keyframe& k = keyframes_[i];
            double dx = ref.centre[0] - k.centre[0];
            double dy = ref.centre[1] - k.centre[1];
            double dz = ref.centre[2] - k.centre[2];
            double b = std::sqrt(dx * dx + dy * dy + dz * dz);
            if (b < 0.032 || b > 0.45) continue;
            double vdot = ref.viewDir[0] * k.viewDir[0] +
                          ref.viewDir[1] * k.viewDir[1] +
                          ref.viewDir[2] * k.viewDir[2];
            if (vdot < 0.72) continue; // > ~44 deg apart
            // Prefer baselines around 0.09-0.14 m and strong view overlap.
            double bScore = std::exp(-std::pow((b - 0.11) / 0.09, 2.0));
            cands.push_back({ i, bScore * vdot });
        }
        std::sort(cands.begin(), cands.end(), [](const Cand& a, const Cand& b) { return a.score > b.score; });
        std::vector<int> out;
        for (int i = 0; i < std::min<int>(4, static_cast<int>(cands.size())); ++i) {
            out.push_back(cands[i].idx);
        }
        return out;
    }

    static inline float sampleBilinear(const uint8_t* img, float u, float v) {
        int x0 = static_cast<int>(u);
        int y0 = static_cast<int>(v);
        float fx = u - x0;
        float fy = v - y0;
        const uint8_t* r0 = img + y0 * kW + x0;
        const uint8_t* r1 = r0 + kW;
        float top = r0[0] + fx * (r0[1] - r0[0]);
        float bot = r1[0] + fx * (r1[1] - r1[0]);
        return top + fy * (bot - top);
    }

    void runPlaneSweep(
        const Keyframe& ref,
        const std::vector<int>& neighborIds,
        double minDepth,
        double maxDepth,
        std::vector<float>& outDepth,
        std::vector<float>& outConf
    ) const {
        std::vector<RelPose> rels;
        rels.reserve(neighborIds.size());
        for (int id : neighborIds) {
            const Keyframe& src = keyframes_[id];
            // T_ref->src = T_src * inv(T_ref):
            //   R_rs = R_src * R_ref^T
            //   t_rs = t_src - R_rs * t_ref
            RelPose rp;
            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 3; ++col) {
                    double s = 0.0;
                    for (int m = 0; m < 3; ++m) {
                        s += src.pose.r[row * 3 + m] * ref.pose.r[col * 3 + m];
                    }
                    rp.r[row * 3 + col] = static_cast<float>(s);
                }
            }
            for (int row = 0; row < 3; ++row) {
                double rt = rp.r[row * 3 + 0] * ref.pose.t[0] +
                            rp.r[row * 3 + 1] * ref.pose.t[1] +
                            rp.r[row * 3 + 2] * ref.pose.t[2];
                rp.t[row] = static_cast<float>(src.pose.t[row] - rt);
            }
            rp.fx = static_cast<float>(src.cam.fx);
            rp.fy = static_cast<float>(src.cam.fy);
            rp.cx = static_cast<float>(src.cam.cx);
            rp.cy = static_cast<float>(src.cam.cy);
            rp.gray = src.gray.data();
            rels.push_back(rp);
        }

        const float invMin = static_cast<float>(1.0 / maxDepth);
        const float invMax = static_cast<float>(1.0 / minDepth);
        float invDepths[kNumPlanes];
        for (int d = 0; d < kNumPlanes; ++d) {
            float frac = static_cast<float>(d) / static_cast<float>(kNumPlanes - 1);
            invDepths[d] = invMin + frac * (invMax - invMin);
        }

        const float invFx = static_cast<float>(1.0 / ref.cam.fx);
        const float invFy = static_cast<float>(1.0 / ref.cam.fy);
        const float rcx = static_cast<float>(ref.cam.cx);
        const float rcy = static_cast<float>(ref.cam.cy);
        const int nSrc = static_cast<int>(rels.size());

        auto processBand = [&](int yStart, int yEnd) {
            float scores[kNumPlanes];
            float refPatch[9];
            float srcPatch[9];
            for (int y = yStart; y < yEnd; ++y) {
                const float ry0 = (static_cast<float>(y) - rcy) * invFy;
                for (int x = 3; x < kW - 3; ++x) {
                    const int idx = y * kW + x;
                    if (!ref.hasTexture[idx]) continue;

                    const float meanR = ref.patchMean[idx];
                    const float invNormR = ref.patchInvNorm[idx];
                    for (int k = 0; k < 9; ++k) {
                        refPatch[k] = static_cast<float>(ref.gray[(y + kTapDy[k]) * kW + (x + kTapDx[k])]) - meanR;
                    }

                    const float rx0 = (static_cast<float>(x) - rcx) * invFx;
                    // Precompute rotated ray + tap ray derivatives per source view.
                    float bestScore = -1.0f;
                    int bestD = -1;

                    for (int d = 0; d < kNumPlanes; ++d) {
                        const float invZ = invDepths[d];
                        float top1 = -1.0f, top2 = -1.0f;
                        int validViews = 0;
                        for (int s = 0; s < nSrc; ++s) {
                            const RelPose& rp = rels[s];
                            // Project center first for quick bounds rejection.
                            float pxC = rp.r[0] * rx0 + rp.r[1] * ry0 + rp.r[2] + rp.t[0] * invZ;
                            float pyC = rp.r[3] * rx0 + rp.r[4] * ry0 + rp.r[5] + rp.t[1] * invZ;
                            float pzC = rp.r[6] * rx0 + rp.r[7] * ry0 + rp.r[8] + rp.t[2] * invZ;
                            if (pzC <= 0.05f) continue;
                            float invPzC = 1.0f / pzC;
                            float uc = rp.fx * pxC * invPzC + rp.cx;
                            float vc = rp.fy * pyC * invPzC + rp.cy;
                            if (uc < 3.0f || uc >= kW - 4.0f || vc < 3.0f || vc >= kH - 4.0f) continue;

                            // Project 9-tap patch via local linearized homography at depth 1/invZ
                            // (exact within 0.02 px across a +-2 px patch and 4x faster than 9 divisions).
                            float dpxDx = rp.r[0] * invFx;
                            float dpyDx = rp.r[3] * invFx;
                            float dpzDx = rp.r[6] * invFx;
                            float dpxDy = rp.r[1] * invFy;
                            float dpyDy = rp.r[4] * invFy;
                            float dpzDy = rp.r[7] * invFy;
                            float duDx = rp.fx * (dpxDx - pxC * invPzC * dpzDx) * invPzC;
                            float dvDx = rp.fy * (dpyDx - pyC * invPzC * dpzDx) * invPzC;
                            float duDy = rp.fx * (dpxDy - pxC * invPzC * dpzDy) * invPzC;
                            float dvDy = rp.fy * (dpyDy - pyC * invPzC * dpzDy) * invPzC;

                            bool inside = true;
                            float sumS = 0.0f;
                            for (int k = 0; k < 9; ++k) {
                                float su = uc + duDx * kTapDx[k] + duDy * kTapDy[k];
                                float sv = vc + dvDx * kTapDx[k] + dvDy * kTapDy[k];
                                if (su < 0.0f || su >= kW - 1.001f || sv < 0.0f || sv >= kH - 1.001f) {
                                    inside = false; break;
                                }
                                float val = sampleBilinear(rp.gray, su, sv);
                                srcPatch[k] = val;
                                sumS += val;
                            }
                            if (!inside) continue;
                            float meanS = sumS * (1.0f / 9.0f);
                            float num = 0.0f, varS = 0.0f;
                            for (int k = 0; k < 9; ++k) {
                                float ds = srcPatch[k] - meanS;
                                num += refPatch[k] * ds;
                                varS += ds * ds;
                            }
                            if (varS < 9.0f * 16.0f) continue;
                            float ncc = num * invNormR / std::sqrt(varS);
                            ++validViews;
                            if (ncc > top1) { top2 = top1; top1 = ncc; }
                            else if (ncc > top2) { top2 = ncc; }
                        }
                        float agg = validViews >= 2 ? 0.5f * (top1 + top2) : top1;
                        scores[d] = agg;
                        if (agg > bestScore) {
                            bestScore = agg;
                            bestD = d;
                        }
                    }

                    if (bestD < 1 || bestD >= kNumPlanes - 1 || bestScore < 0.62f) continue;

                    // Uniqueness check: reject ambiguous periodic/flat regions.
                    float secondBest = -1.0f;
                    for (int d = 0; d < kNumPlanes; ++d) {
                        if (std::abs(d - bestD) <= 3) continue;
                        if (scores[d] > secondBest) secondBest = scores[d];
                    }
                    if (secondBest > -0.5f && (bestScore - secondBest) < 0.045f) continue;

                    // Sub-plane parabolic interpolation in inverse-depth space.
                    float sm = scores[bestD - 1];
                    float s0 = scores[bestD];
                    float sp = scores[bestD + 1];
                    float denom = sm - 2.0f * s0 + sp;
                    float delta = std::abs(denom) > 1e-5f ? std::clamp(0.5f * (sm - sp) / denom, -0.5f, 0.5f) : 0.0f;
                    float step = (invMax - invMin) / static_cast<float>(kNumPlanes - 1);
                    float invZ = invDepths[bestD] + delta * step;
                    if (invZ > 1e-4f) {
                        outDepth[idx] = 1.0f / invZ;
                        outConf[idx] = std::clamp((bestScore - 0.55f) / 0.45f, 0.15f, 1.0f);
                    }
                }
            }
        };

        // Run across 4 worker threads (band height ~28 rows each) for ~6-12 ms latency on mobile.
        constexpr int kThreads = 4;
        std::thread workers[kThreads - 1];
        const int totalRows = kH - 6;
        const int band = (totalRows + kThreads - 1) / kThreads;
        for (int t = 0; t < kThreads - 1; ++t) {
            int y0 = 3 + t * band;
            int y1 = std::min(kH - 3, y0 + band);
            workers[t] = std::thread(processBand, y0, y1);
        }
        processBand(3 + (kThreads - 1) * band, kH - 3);
        for (int t = 0; t < kThreads - 1; ++t) workers[t].join();
    }

    /**
     * 3x3 consistent-neighbor speckle/median filter followed by a gentle 5x5 pinhole fill
     * for tiny textureless holes surrounded by consistent depth.
     */
    static int filterAndFillDepth(
        const std::vector<float>& rawD,
        const std::vector<float>& rawC,
        std::vector<float>& outD,
        std::vector<float>& outC
    ) {
        std::vector<float> medD(kW * kH, 0.0f);
        std::vector<float> medC(kW * kH, 0.0f);
        float win[9];

        // Pass 1: 3x3 spatial consistency & median filter (eliminates flying outlier specks).
        for (int y = 4; y < kH - 4; ++y) {
            for (int x = 4; x < kW - 4; ++x) {
                int idx = y * kW + x;
                float d0 = rawD[idx];
                if (d0 <= 0.0f) continue;
                float tol = d0 * 0.08f;
                int cnt = 0;
                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dx = -1; dx <= 1; ++dx) {
                        float dn = rawD[(y + dy) * kW + (x + dx)];
                        if (dn > 0.0f && std::abs(dn - d0) <= tol) {
                            win[cnt++] = dn;
                        }
                    }
                }
                if (cnt >= 4) {
                    std::sort(win, win + cnt);
                    medD[idx] = win[cnt / 2];
                    medC[idx] = rawC[idx];
                }
            }
        }

        // Pass 2: gentle 5x5 pinhole fill only where >= 10 nearby pixels agree tightly (<= 5% spread).
        int valid = 0;
        float fillBuf[25];
        for (int y = 4; y < kH - 4; ++y) {
            for (int x = 4; x < kW - 4; ++x) {
                int idx = y * kW + x;
                if (medD[idx] > 0.0f) {
                    outD[idx] = medD[idx];
                    outC[idx] = medC[idx];
                    ++valid;
                    continue;
                }
                int cnt = 0;
                for (int dy = -2; dy <= 2; ++dy) {
                    for (int dx = -2; dx <= 2; ++dx) {
                        float dn = medD[(y + dy) * kW + (x + dx)];
                        if (dn > 0.0f) fillBuf[cnt++] = dn;
                    }
                }
                if (cnt >= 10) {
                    std::sort(fillBuf, fillBuf + cnt);
                    float med = fillBuf[cnt / 2];
                    if ((fillBuf[(cnt * 3) / 4] - fillBuf[cnt / 4]) <= med * 0.05f) {
                        outD[idx] = med;
                        outC[idx] = 0.35f; // lower weight for filled pinholes
                        ++valid;
                    }
                }
            }
        }
        return valid;
    }

    mutable std::mutex opMtx_;
    mutable std::mutex kfMtx_;
    std::deque<Keyframe> keyframes_;
    bool hasLastKf_ = false;
};

} // namespace livemvs
