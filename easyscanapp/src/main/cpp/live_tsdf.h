#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "live_tsdf_mc_tables.h"

namespace livetsdf {

static constexpr int kDa3Size = 518;
static constexpr int kBlockDim = 8;
static constexpr int kBlockVoxels = kBlockDim * kBlockDim * kBlockDim; // 512
static constexpr float kMinCornerWeight = 0.25f;
static constexpr float kMaxVoxelWeight = 120.0f;

/** Interleaved 40-byte vertex matching LiveMeshView's GLES30 VBO layout directly. */
struct GpuVertex {
    float x, y, z;
    float nx, ny, nz;
    float r, g, b;
    float refined;
};
static_assert(sizeof(GpuVertex) == 40, "GpuVertex must match 40-byte GL stride");

struct Camera {
    double fx = 441.0, fy = 435.0, cx = 320.0, cy = 240.0;
    int width = 640, height = 480;
    double k1 = 0.0, k2 = 0.0, p1 = 0.0, p2 = 0.0, k3 = 0.0;
    double maxMonotonicR = 2.0;
    double maxInvertibleRd = 2.0;

    void initLimits() {
        if (k1 == 0.0 && k2 == 0.0 && k3 == 0.0) {
            maxMonotonicR = 2.0; maxInvertibleRd = 2.0; return;
        }
        double best = 0.1;
        for (double r = 0.01; r <= 2.0; r += 0.01) {
            double r2 = r * r;
            double drd = 1.0 + r2 * (3.0 * k1 + r2 * (5.0 * k2 + r2 * 7.0 * k3));
            if (drd <= 0.15) break;
            best = r;
        }
        maxMonotonicR = best;
        double r2 = best * best;
        maxInvertibleRd = best * (1.0 + r2 * (k1 + r2 * (k2 + r2 * k3)));
    }

    inline bool project(double xc, double yc, double zc, double& u, double& v) const {
        if (!std::isfinite(xc) || !std::isfinite(yc) || !std::isfinite(zc) || zc <= 1e-6) return false;
        double x = xc / zc, y = yc / zc;
        double r2 = x * x + y * y;
        if (!std::isfinite(r2) || r2 > 4.0) return false;
        double rad = 1.0 + r2 * (k1 + r2 * (k2 + r2 * k3));
        double xd = x * rad + 2.0 * p1 * x * y + p2 * (r2 + 2.0 * x * x);
        double yd = y * rad + p1 * (r2 + 2.0 * y * y) + 2.0 * p2 * x * y;
        u = fx * xd + cx;
        v = fy * yd + cy;
        return std::isfinite(u) && std::isfinite(v);
    }

    inline void undistort(double u, double v, double& rx, double& ry) const {
        double x0 = (u - cx) / fx;
        double y0 = (v - cy) / fy;
        if (k1 == 0.0 && k2 == 0.0 && p1 == 0.0 && p2 == 0.0 && k3 == 0.0) {
            rx = x0; ry = y0; return;
        }
        double r0 = std::hypot(x0, y0);
        if (r0 > maxInvertibleRd && maxInvertibleRd > 1e-6) {
            double r2 = maxMonotonicR * maxMonotonicR;
            double slope = std::max(0.15, 1.0 + r2 * (3.0 * k1 + r2 * (5.0 * k2 + r2 * 7.0 * k3)));
            double rOut = maxMonotonicR + (r0 - maxInvertibleRd) / slope;
            double s = rOut / r0;
            rx = x0 * s; ry = y0 * s; return;
        }
        double x = x0, y = y0;
        for (int it = 0; it < 12; ++it) {
            double r2 = x * x + y * y;
            double rad = 1.0 + r2 * (k1 + r2 * (k2 + r2 * k3));
            double dr = k1 + r2 * (2.0 * k2 + r2 * 3.0 * k3);
            double fxv = x * rad + 2.0 * p1 * x * y + p2 * (r2 + 2.0 * x * x) - x0;
            double fyv = y * rad + p1 * (r2 + 2.0 * y * y) + 2.0 * p2 * x * y - y0;
            if (fxv * fxv + fyv * fyv < 1e-22) break;
            double j00 = rad + 2.0 * x * x * dr + 2.0 * p1 * y + 6.0 * p2 * x;
            double j01 = 2.0 * x * y * dr + 2.0 * p1 * x + 2.0 * p2 * y;
            double j11 = rad + 2.0 * y * y * dr + 2.0 * p2 * x + 6.0 * p1 * y;
            double det = j00 * j11 - j01 * j01;
            if (std::abs(det) < 1e-12 || !std::isfinite(det)) break;
            double nx = x - (j11 * fxv - j01 * fyv) / det;
            double ny = y - (-j01 * fxv + j00 * fyv) / det;
            double nr = std::hypot(nx, ny);
            if (nr > maxMonotonicR) { nx *= maxMonotonicR / nr; ny *= maxMonotonicR / nr; }
            x = nx; y = ny;
        }
        rx = x; ry = y;
    }
};

struct Pose {
    double r[9] = {1,0,0, 0,1,0, 0,0,1};
    double t[3] = {0,0,0};

    inline void cameraToWorld(double xc, double yc, double zc, double& wx, double& wy, double& wz) const {
        double dx = xc - t[0], dy = yc - t[1], dz = zc - t[2];
        wx = r[0] * dx + r[3] * dy + r[6] * dz;
        wy = r[1] * dx + r[4] * dy + r[7] * dz;
        wz = r[2] * dx + r[5] * dy + r[8] * dz;
    }

    inline void worldToCamera(double wx, double wy, double wz, double& xc, double& yc, double& zc) const {
        xc = r[0] * wx + r[1] * wy + r[2] * wz + t[0];
        yc = r[3] * wx + r[4] * wy + r[5] * wz + t[1];
        zc = r[6] * wx + r[7] * wy + r[8] * wz + t[2];
    }

    inline void cameraCentre(double& cx, double& cy, double& cz) const {
        cameraToWorld(0.0, 0.0, 0.0, cx, cy, cz);
    }
};

struct BlockMesh {
    std::vector<GpuVertex> verts;
    std::vector<uint64_t> edgeKeys;
    std::vector<uint32_t> indices;
    int laserVoxels = 0;
};

struct VoxelBlock {
    int bx, by, bz;
    float tsdf[kBlockVoxels];
    float weight[kBlockVoxels];
    float laserWeight[kBlockVoxels];
    uint8_t r[kBlockVoxels];
    uint8_t g[kBlockVoxels];
    uint8_t b[kBlockVoxels];
    uint8_t hasColor[kBlockVoxels];
    bool dirty = true;
    bool hasLaser = false;
    std::unique_ptr<BlockMesh> cached;

    VoxelBlock(int x, int y, int z) : bx(x), by(y), bz(z) {
        for (int i = 0; i < kBlockVoxels; ++i) {
            tsdf[i] = 1.0f; weight[i] = 0.0f; laserWeight[i] = 0.0f;
            r[i] = 170; g[i] = 175; b[i] = 180; hasColor[i] = 0;
        }
    }
};

struct VolumeStats {
    int activeBlocks = 0;
    int laserBlocks = 0;
    int totalLaserHits = 0;
    int integratedPatches = 0;
    int lastDirtyRebuilt = 0;
};

struct ExtractedMesh {
    std::vector<GpuVertex> verts;
    std::vector<uint32_t> indices;
    float boundsMin[3] = {0, 0, 0};
    float boundsMax[3] = {0, 0, 0};
    int laserBlocks = 0;
    int activeBlocks = 0;
    int laserCells = 0;
    int totalLaserHits = 0;
};

inline int floorDiv8(int v) { return v >= 0 ? (v >> 3) : -(((-v) + 7) >> 3); }
inline int floorMod8(int v) { return v - (floorDiv8(v) << 3); }
inline int localIdx(int lx, int ly, int lz) { return (lz << 6) | (ly << 3) | lx; }

struct BlockNormals {
    float nx[kBlockVoxels] = {};
    float ny[kBlockVoxels] = {};
    float nz[kBlockVoxels] = {};
    float nw[kBlockVoxels] = {};
};

inline uint64_t packCellKey(int gx, int gy, int gz) {
    return (static_cast<uint64_t>(gx) & 0x1fffffULL) |
           ((static_cast<uint64_t>(gy) & 0x1fffffULL) << 21) |
           ((static_cast<uint64_t>(gz) & 0x1fffffULL) << 42);
}

inline uint64_t packBlockKey(int bx, int by, int bz) {
    return (static_cast<uint64_t>(bx) & 0x1fffffULL) |
           ((static_cast<uint64_t>(by) & 0x1fffffULL) << 21) |
           ((static_cast<uint64_t>(bz) & 0x1fffffULL) << 42);
}

inline uint64_t packEdgeKey(int gx, int gy, int gz, int axis) {
    return (static_cast<uint64_t>(gx) & 0xfffffULL) |
           ((static_cast<uint64_t>(gy) & 0xfffffULL) << 20) |
           ((static_cast<uint64_t>(gz) & 0xfffffULL) << 40) |
           ((static_cast<uint64_t>(axis) & 0x3ULL) << 60);
}

inline float sampleDepthBilinear(const float* depth, double u, double v, const Camera& cam) {
    double gx = u * kDa3Size / cam.width - 0.5;
    double gy = v * kDa3Size / cam.height - 0.5;
    int x0 = std::clamp(static_cast<int>(std::floor(gx)), 0, kDa3Size - 1);
    int y0 = std::clamp(static_cast<int>(std::floor(gy)), 0, kDa3Size - 1);
    int x1 = std::min(x0 + 1, kDa3Size - 1);
    int y1 = std::min(y0 + 1, kDa3Size - 1);
    double fx = std::clamp(gx - x0, 0.0, 1.0);
    double fy = std::clamp(gy - y0, 0.0, 1.0);
    double sum = 0.0, wSum = 0.0;
    auto acc = [&](int ix, int iy, double w) {
        float d = depth[iy * kDa3Size + ix];
        if (d > 0.0f && std::isfinite(d)) { sum += d * w; wSum += w; }
    };
    acc(x0, y0, (1.0 - fx) * (1.0 - fy));
    acc(x1, y0, fx * (1.0 - fy));
    acc(x0, y1, (1.0 - fx) * fy);
    acc(x1, y1, fx * fy);
    return wSum > 0.0 ? static_cast<float>(sum / wSum) : std::numeric_limits<float>::quiet_NaN();
}

class NativeTsdfVolume {
public:
    mutable std::mutex mtx;
    NativeTsdfVolume(float voxelSizeM = 0.003f, float truncationM = 0.012f,
                     int maxBlocks = 40000, double maxDepthM = 2.0)
        : voxelSize_(voxelSizeM), trunc_(truncationM), invVoxel_(1.0 / voxelSizeM),
          maxBlocks_(maxBlocks), maxDepth_(maxDepthM) {}

    void clear() {
        std::lock_guard<std::mutex> g(mu_);
        blocks_.clear();
        blockNormals_.clear();
        totalLaserHits_ = 0;
        integratedPatches_ = 0;
        lastDirtyRebuilt_ = 0;
        dirtyVersion_++;
        weldedVersion_ = 0;
        lastMesh_.verts.clear();
        lastMesh_.indices.clear();
    }

    VolumeStats stats() const {
        std::lock_guard<std::mutex> g(mu_);
        int lb = 0;
        for (const auto& kv : blocks_) if (kv.second->hasLaser) ++lb;
        return { static_cast<int>(blocks_.size()), lb, totalLaserHits_, integratedPatches_, lastDirtyRebuilt_ };
    }

    void setVoxel(int gx, int gy, int gz, float tsdfVal, float wVal, float lwVal, uint8_t cr, uint8_t cg, uint8_t cb) {
        std::lock_guard<std::mutex> g(mu_);
        VoxelBlock* blk = getOrCreate(floorDiv8(gx), floorDiv8(gy), floorDiv8(gz));
        if (!blk) return;
        int lx = floorMod8(gx), ly = floorMod8(gy), lz = floorMod8(gz);
        int idx = localIdx(lx, ly, lz);
        blk->tsdf[idx] = std::clamp(tsdfVal, -1.0f, 1.0f);
        blk->weight[idx] = wVal;
        blk->laserWeight[idx] = lwVal;
        blk->r[idx] = cr; blk->g[idx] = cg; blk->b[idx] = cb; blk->hasColor[idx] = 1;
        if (lwVal > 0.0f) blk->hasLaser = true;
        markDirtyAround(blk, lx, ly, lz);
    }

    /**
     * Estimates the fraction [0, 1] of valid foreground DA3 pixels in this view that fall on
     * genuinely UNCOVERED space (not already occupied by an existing front-facing mesh piece).
     */
    float estimateNoveltyFraction(
        const Pose& pose, const Camera& cam, const float* rawDepth,
        double scale, double offset, const uint8_t* validMask518
    ) {
        std::lock_guard<std::mutex> g(mu_);
        if (integratedPatches_ == 0 || blocks_.empty()) return 1.0f;
        std::vector<uint8_t> occ;
        buildPuzzleOccupancyLocked(pose, cam, rawDepth, scale, offset, occ);
        int total = 0, novel = 0;
        for (int y = 0; y < kDa3Size; y += 4) {
            int row = y * kDa3Size;
            int gy = y / kOccCell;
            for (int x = 0; x < kDa3Size; x += 4) {
                int idx = row + x;
                if (validMask518 && validMask518[idx] == 0) continue;
                float d = rawDepth[idx];
                if (!(d > 0.0f) || !std::isfinite(d)) continue;
                double z = d * scale + offset;
                if (z <= 0.08 || z > maxDepth_) continue;
                ++total;
                if (occ[gy * kOccDim + (x / kOccCell)] == 0) ++novel;
            }
        }
        return total > 0 ? static_cast<float>(novel) / static_cast<float>(total) : 0.0f;
    }

    void integrateDepthMap(
        const Pose& pose, const Camera& cam, const float* rawDepth,
        double scale, double offset, const uint8_t* rgb518, const uint8_t* validMask518,
        int stride = 2, float da3Weight = 0.5f
    ) {
        std::lock_guard<std::mutex> g(mu_);
        const bool puzzleMode = (integratedPatches_ > 0 && !blocks_.empty());
        std::vector<uint8_t> occ;
        if (puzzleMode) {
            buildPuzzleOccupancyLocked(pose, cam, rawDepth, scale, offset, occ);
        }
        const double trunc = trunc_;
        const int allocStride = std::max(2, stride);
        std::unordered_set<VoxelBlock*> touched;
        touched.reserve(1024);
        for (int y = 0; y < kDa3Size; y += allocStride) {
            double vf = (y + 0.5) * cam.height / kDa3Size;
            int row = y * kDa3Size;
            int gy = y / kOccCell;
            for (int x = 0; x < kDa3Size; x += allocStride) {
                if (puzzleMode && occ[gy * kOccDim + (x / kOccCell)] == 1) continue;
                int idx = row + x;
                if (validMask518 && validMask518[idx] == 0) continue;
                float d = rawDepth[idx];
                if (!(d > 0.0f) || !std::isfinite(d)) continue;
                double z = d * scale + offset;
                if (z <= 0.08 || z > maxDepth_) continue;
                double uf = (x + 0.5) * cam.width / kDa3Size;
                double rx, ry;
                cam.undistort(uf, vf, rx, ry);
                double wx, wy, wz;
                pose.cameraToWorld(rx * z, ry * z, z, wx, wy, wz);
                int gx = static_cast<int>(std::floor(wx * invVoxel_));
                int gyv = static_cast<int>(std::floor(wy * invVoxel_));
                int gz = static_cast<int>(std::floor(wz * invVoxel_));
                int bx = floorDiv8(gx), by = floorDiv8(gyv), bz = floorDiv8(gz);
                int lx = floorMod8(gx), ly = floorMod8(gyv), lz = floorMod8(gz);
                int dx0 = lx <= 2 ? -1 : 0, dx1 = lx >= kBlockDim - 3 ? 1 : 0;
                int dy0 = ly <= 2 ? -1 : 0, dy1 = ly >= kBlockDim - 3 ? 1 : 0;
                int dz0 = lz <= 2 ? -1 : 0, dz1 = lz >= kBlockDim - 3 ? 1 : 0;
                for (int dz = dz0; dz <= dz1; ++dz)
                    for (int dy = dy0; dy <= dy1; ++dy)
                        for (int dx = dx0; dx <= dx1; ++dx) {
                            if (VoxelBlock* b = getOrCreate(bx + dx, by + dy, bz + dz)) touched.insert(b);
                        }
            }
        }

        for (VoxelBlock* blk : touched) {
            int gx0 = blk->bx * kBlockDim, gy0 = blk->by * kBlockDim, gz0 = blk->bz * kBlockDim;
            for (int lz = 0; lz < kBlockDim; ++lz) {
                double wz = (gz0 + lz + 0.5) * voxelSize_;
                for (int ly = 0; ly < kBlockDim; ++ly) {
                    double wy = (gy0 + ly + 0.5) * voxelSize_;
                    for (int lx = 0; lx < kBlockDim; ++lx) {
                        double wx = (gx0 + lx + 0.5) * voxelSize_;
                        double xc, yc, zc;
                        pose.worldToCamera(wx, wy, wz, xc, yc, zc);
                        if (zc <= 0.08 || zc > maxDepth_ + trunc) continue;
                        double u, v;
                        if (!cam.project(xc, yc, zc, u, v)) continue;
                        if (u < 0.0 || u >= cam.width || v < 0.0 || v >= cam.height) continue;
                        int sx = std::clamp(static_cast<int>(u * kDa3Size / cam.width), 0, kDa3Size - 1);
                        int sy = std::clamp(static_cast<int>(v * kDa3Size / cam.height), 0, kDa3Size - 1);
                        uint8_t cellState = puzzleMode ? occ[(sy / kOccCell) * kOccDim + (sx / kOccCell)] : 0;
                        // Puzzle rule: never overwrite or double-layer an already-covered interior surface.
                        if (cellState == 1) continue;
                        int sIdx = sy * kDa3Size + sx;
                        if (validMask518 && validMask518[sIdx] == 0) continue;
                        float dRaw = sampleDepthBilinear(rawDepth, u, v, cam);
                        if (!(dRaw > 0.0f) || !std::isfinite(dRaw)) continue;
                        double dMetric = dRaw * scale + offset;
                        if (dMetric > maxDepth_) continue;
                        double sdf = dMetric - zc;
                        if (sdf < -trunc || sdf > trunc) continue;
                        float tsdfSample = std::clamp(static_cast<float>(sdf / trunc), -1.0f, 1.0f);
                        int vIdx = localIdx(lx, ly, lz);
                        float prevLW = blk->laserWeight[vIdx];
                        float prevW = blk->weight[vIdx];
                        float effW = da3Weight;
                        if (puzzleMode) {
                            if (prevLW > 1.0f) continue; // never let coarse DA3 blur metric laser
                            if (cellState == 2) {
                                // Seam strip: weld gently only if consistent with the existing edge.
                                if (prevW > 0.0f && std::abs(tsdfSample - blk->tsdf[vIdx]) > 0.65f) continue;
                                effW = da3Weight * 0.25f;
                            } else if (prevW > 0.0f) {
                                // Adjacent voxel already occupied by an earlier piece: only weld if consistent.
                                if (std::abs(tsdfSample - blk->tsdf[vIdx]) > 0.70f) continue;
                                effW = da3Weight * 0.20f;
                            }
                        } else if (prevLW > 4.0f) {
                            effW = da3Weight * 0.15f;
                        }
                        blk->tsdf[vIdx] = prevW <= 0.0f ? tsdfSample : (blk->tsdf[vIdx] * prevW + tsdfSample * effW) / (prevW + effW);
                        blk->weight[vIdx] = std::min(kMaxVoxelWeight, prevW + effW);
                        uint8_t nr = rgb518[sIdx * 3], ng = rgb518[sIdx * 3 + 1], nb = rgb518[sIdx * 3 + 2];
                        if (!blk->hasColor[vIdx]) {
                            blk->r[vIdx] = nr; blk->g[vIdx] = ng; blk->b[vIdx] = nb; blk->hasColor[vIdx] = 1;
                        } else {
                            float a = std::clamp(effW / (prevW + effW), 0.05f, 0.5f);
                            blk->r[vIdx] = static_cast<uint8_t>(std::clamp(blk->r[vIdx] * (1.0f - a) + nr * a, 0.0f, 255.0f));
                            blk->g[vIdx] = static_cast<uint8_t>(std::clamp(blk->g[vIdx] * (1.0f - a) + ng * a, 0.0f, 255.0f));
                            blk->b[vIdx] = static_cast<uint8_t>(std::clamp(blk->b[vIdx] * (1.0f - a) + nb * a, 0.0f, 255.0f));
                        }
                        markDirtyAround(blk, lx, ly, lz);
                    }
                }
            }
        }
        integratedPatches_++;
    }

    /**
     * Integrates a metric multi-view stereo (MVS) depth map (e.g. 160x120 from LiveMvsEngine)
     * directly into the sparse volumetric TSDF. Because MVS depth is already in true metric
     * world coordinates (zero monocular scale/shift warp), multiple views fuse smoothly by
     * confidence-weighted averaging — while never overwriting metric laser-carved voxels.
     */
    int integrateMetricDepth(
        const Pose& pose, const Camera& cam, const float* depthM, const float* conf,
        const uint8_t* rgb, int w, int h, float baseWeight = 0.65f
    ) {
        std::lock_guard<std::mutex> g(mu_);
        if (!depthM || w <= 0 || h <= 0) return 0;
        const double trunc = trunc_;
        std::unordered_set<VoxelBlock*> touched;
        touched.reserve(512);
        int validPx = 0;

        // Stage 1: allocate surface-shell blocks around valid metric depth pixels.
        for (int y = 0; y < h; ++y) {
            int row = y * w;
            for (int x = 0; x < w; ++x) {
                float z = depthM[row + x];
                if (!(z > 0.08f) || !std::isfinite(z) || z > maxDepth_) continue;
                ++validPx;
                double rx = (x - cam.cx) / cam.fx;
                double ry = (y - cam.cy) / cam.fy;
                double wx, wy, wz;
                pose.cameraToWorld(rx * z, ry * z, z, wx, wy, wz);
                int gx = static_cast<int>(std::floor(wx * invVoxel_));
                int gy = static_cast<int>(std::floor(wy * invVoxel_));
                int gz = static_cast<int>(std::floor(wz * invVoxel_));
                int bx = floorDiv8(gx), by = floorDiv8(gy), bz = floorDiv8(gz);
                int lx = floorMod8(gx), ly = floorMod8(gy), lz = floorMod8(gz);
                int dx0 = lx <= 2 ? -1 : 0, dx1 = lx >= kBlockDim - 3 ? 1 : 0;
                int dy0 = ly <= 2 ? -1 : 0, dy1 = ly >= kBlockDim - 3 ? 1 : 0;
                int dz0 = lz <= 2 ? -1 : 0, dz1 = lz >= kBlockDim - 3 ? 1 : 0;
                for (int dz = dz0; dz <= dz1; ++dz)
                    for (int dy = dy0; dy <= dy1; ++dy)
                        for (int dx = dx0; dx <= dx1; ++dx) {
                            if (VoxelBlock* b = getOrCreate(bx + dx, by + dy, bz + dz)) touched.insert(b);
                        }
            }
        }
        if (touched.empty()) return 0;

        // Stage 2: projective metric TSDF fusion over all voxels in touched blocks.
        for (VoxelBlock* blk : touched) {
            int gx0 = blk->bx * kBlockDim, gy0 = blk->by * kBlockDim, gz0 = blk->bz * kBlockDim;
            for (int lz = 0; lz < kBlockDim; ++lz) {
                double wz = (gz0 + lz + 0.5) * voxelSize_;
                for (int ly = 0; ly < kBlockDim; ++ly) {
                    double wy = (gy0 + ly + 0.5) * voxelSize_;
                    for (int lx = 0; lx < kBlockDim; ++lx) {
                        double wx = (gx0 + lx + 0.5) * voxelSize_;
                        double xc, yc, zc;
                        pose.worldToCamera(wx, wy, wz, xc, yc, zc);
                        if (zc <= 0.08 || zc > maxDepth_ + trunc) continue;
                        double u = cam.fx * (xc / zc) + cam.cx;
                        double v = cam.fy * (yc / zc) + cam.cy;
                        int px = static_cast<int>(std::lround(u));
                        int py = static_cast<int>(std::lround(v));
                        if (px < 0 || px >= w || py < 0 || py >= h) continue;
                        int pIdx = py * w + px;
                        float dMetric = depthM[pIdx];
                        if (!(dMetric > 0.08f) || dMetric > maxDepth_) continue;
                        double sdf = dMetric - zc;
                        if (sdf < -trunc || sdf > trunc) continue;
                        int vIdx = localIdx(lx, ly, lz);
                        // Laser authority: never let coarse MVS overwrite laser-carved voxels.
                        if (blk->laserWeight[vIdx] > 1.0f) continue;
                        float c = conf ? std::clamp(conf[pIdx], 0.1f, 1.0f) : 1.0f;
                        float effW = baseWeight * c;
                        float prevW = blk->weight[vIdx];
                        float tsdfSample = std::clamp(static_cast<float>(sdf / trunc), -1.0f, 1.0f);
                        blk->tsdf[vIdx] = prevW <= 0.0f ? tsdfSample
                            : (blk->tsdf[vIdx] * prevW + tsdfSample * effW) / (prevW + effW);
                        blk->weight[vIdx] = std::min(kMaxVoxelWeight, prevW + effW);
                        if (rgb) {
                            uint8_t nr = rgb[pIdx * 3], ng = rgb[pIdx * 3 + 1], nb = rgb[pIdx * 3 + 2];
                            if (!blk->hasColor[vIdx]) {
                                blk->r[vIdx] = nr; blk->g[vIdx] = ng; blk->b[vIdx] = nb; blk->hasColor[vIdx] = 1;
                            } else {
                                float a = std::clamp(effW / (prevW + effW), 0.05f, 0.45f);
                                blk->r[vIdx] = static_cast<uint8_t>(std::clamp(blk->r[vIdx] * (1.0f - a) + nr * a, 0.0f, 255.0f));
                                blk->g[vIdx] = static_cast<uint8_t>(std::clamp(blk->g[vIdx] * (1.0f - a) + ng * a, 0.0f, 255.0f));
                                blk->b[vIdx] = static_cast<uint8_t>(std::clamp(blk->b[vIdx] * (1.0f - a) + nb * a, 0.0f, 255.0f));
                            }
                        }
                        markDirtyAround(blk, lx, ly, lz);
                    }
                }
            }
        }
        integratedPatches_++;
        return validPx;
    }

    int integrateLaserFrame(const Pose& pose, const float* laserCamM, int numPoints, float laserWeightVal = 8.0f) {
        std::lock_guard<std::mutex> g(mu_);
        double cx, cy, cz;
        pose.cameraCentre(cx, cy, cz);
        const double trunc = trunc_;
        const double stepM = voxelSize_ * 0.6;
        int integrated = 0;
        for (int i = 0; i < numPoints; ++i) {
            double lx = laserCamM[i * 3], ly = laserCamM[i * 3 + 1], lz = laserCamM[i * 3 + 2];
            if (!std::isfinite(lx) || !std::isfinite(ly) || !std::isfinite(lz) || lz <= 0.04 || lz > maxDepth_) continue;
            double px, py, pz;
            pose.cameraToWorld(lx, ly, lz, px, py, pz);
            double vx = px - cx, vy = py - cy, vz = pz - cz;
            double dist = std::sqrt(vx * vx + vy * vy + vz * vz);
            if (!std::isfinite(dist) || dist <= 0.04 || dist > maxDepth_ + 0.5) continue;
            double invD = 1.0 / dist;
            double dx = vx * invD, dy = vy * invD, dz = vz * invD;
            double tStart = std::max(0.02, dist - trunc), tEnd = dist + trunc;
            int prevGx = INT32_MIN, prevGy = INT32_MIN, prevGz = INT32_MIN;
            for (double t = tStart; t <= tEnd; t += stepM) {
                double fx = (cx + dx * t) * invVoxel_ - 0.5;
                double fy = (cy + dy * t) * invVoxel_ - 0.5;
                double fz = (cz + dz * t) * invVoxel_ - 0.5;
                int gx0 = static_cast<int>(std::floor(fx));
                int gy0 = static_cast<int>(std::floor(fy));
                int gz0 = static_cast<int>(std::floor(fz));
                if (gx0 == prevGx && gy0 == prevGy && gz0 == prevGz) continue;
                prevGx = gx0; prevGy = gy0; prevGz = gz0;
                double ux = std::clamp(fx - gx0, 0.0, 1.0);
                double uy = std::clamp(fy - gy0, 0.0, 1.0);
                double uz = std::clamp(fz - gz0, 0.0, 1.0);
                for (int oz = 0; oz <= 1; ++oz) {
                    double wzf = oz == 0 ? (1.0 - uz) : uz;
                    if (wzf < 0.05) continue;
                    int gz = gz0 + oz;
                    double vcz = (gz + 0.5) * voxelSize_;
                    for (int oy = 0; oy <= 1; ++oy) {
                        double wyf = oy == 0 ? (1.0 - uy) : uy;
                        if (wyf < 0.05) continue;
                        int gy = gy0 + oy;
                        double vcy = (gy + 0.5) * voxelSize_;
                        for (int ox = 0; ox <= 1; ++ox) {
                            double wxf = ox == 0 ? (1.0 - ux) : ux;
                            float triW = static_cast<float>(wxf * wyf * wzf);
                            if (triW < 0.05f) continue;
                            int gx = gx0 + ox;
                            double vcx = (gx + 0.5) * voxelSize_;
                            double rayDepth = (vcx - cx) * dx + (vcy - cy) * dy + (vcz - cz) * dz;
                            double sdf = dist - rayDepth;
                            if (sdf < -trunc || sdf > trunc) continue;
                            float tsdfSample = std::clamp(static_cast<float>(sdf / trunc), -1.0f, 1.0f);
                            VoxelBlock* blk = getOrCreate(floorDiv8(gx), floorDiv8(gy), floorDiv8(gz));
                            if (!blk) continue;
                            int blx = floorMod8(gx), bly = floorMod8(gy), blz = floorMod8(gz);
                            int vIdx = localIdx(blx, bly, blz);
                            float effW = laserWeightVal * triW;
                            float prevW = blk->weight[vIdx];
                            blk->tsdf[vIdx] = prevW <= 0.0f ? tsdfSample : (blk->tsdf[vIdx] * prevW + tsdfSample * effW) / (prevW + effW);
                            blk->weight[vIdx] = std::min(kMaxVoxelWeight, prevW + effW);
                            blk->laserWeight[vIdx] = std::min(kMaxVoxelWeight, blk->laserWeight[vIdx] + effW);
                            blk->hasLaser = true;
                            markDirtyAround(blk, blx, bly, blz);
                        }
                    }
                }
            }
            ++integrated;
        }
        totalLaserHits_ += integrated;
        return integrated;
    }

    /**
     * Integrates an oriented colored point cloud (e.g. Parallax dense MVS `dense.ply`,
     * world-space metres, outward unit normals).
     *
     * Signed distance is evaluated along each point's outward normal (+ outside, - inside).
     * Wherever laser hits already own a voxel (laserWeight > 2.0), MVS weight is heavily
     * damped (8%) so laser remains the metric geometry authority while MVS fills gaps and
     * supplies surface color.
     */
    int integrateOrientedPointCloud(
        const float* xyzNxyz, const uint8_t* rgb, int numPoints, float pointWeight = 1.5f
    ) {
        if (!xyzNxyz || numPoints <= 0) return 0;
        std::lock_guard<std::mutex> g(mu_);
        const double trunc = trunc_;
        const double stepM = voxelSize_ * 0.6;
        int integrated = 0;
        for (int i = 0; i < numPoints; ++i) {
            const float* p = xyzNxyz + i * 6;
            double px = p[0], py = p[1], pz = p[2];
            double nx = p[3], ny = p[4], nz = p[5];
            if (!std::isfinite(px) || !std::isfinite(py) || !std::isfinite(pz)) continue;
            if (!std::isfinite(nx) || !std::isfinite(ny) || !std::isfinite(nz)) continue;
            if (std::abs(px) > maxDepth_ || std::abs(py) > maxDepth_ || std::abs(pz) > maxDepth_) continue;
            double nl = std::sqrt(nx * nx + ny * ny + nz * nz);
            if (nl < 1e-4) continue;
            nx /= nl; ny /= nl; nz /= nl;
            uint8_t cr = rgb ? rgb[i * 3] : 180;
            uint8_t cg = rgb ? rgb[i * 3 + 1] : 185;
            uint8_t cb = rgb ? rgb[i * 3 + 2] : 190;

            int prevGx = INT32_MIN, prevGy = INT32_MIN, prevGz = INT32_MIN;
            for (double s = -trunc; s <= trunc; s += stepM) {
                double sx = px + nx * s;
                double sy = py + ny * s;
                double sz = pz + nz * s;
                double fx = sx * invVoxel_ - 0.5;
                double fy = sy * invVoxel_ - 0.5;
                double fz = sz * invVoxel_ - 0.5;
                int gx0 = static_cast<int>(std::floor(fx));
                int gy0 = static_cast<int>(std::floor(fy));
                int gz0 = static_cast<int>(std::floor(fz));
                if (gx0 == prevGx && gy0 == prevGy && gz0 == prevGz) continue;
                prevGx = gx0; prevGy = gy0; prevGz = gz0;
                double ux = std::clamp(fx - gx0, 0.0, 1.0);
                double uy = std::clamp(fy - gy0, 0.0, 1.0);
                double uz = std::clamp(fz - gz0, 0.0, 1.0);
                for (int oz = 0; oz <= 1; ++oz) {
                    double wzf = oz == 0 ? (1.0 - uz) : uz;
                    if (wzf < 0.05) continue;
                    int gz = gz0 + oz;
                    double vcz = (gz + 0.5) * voxelSize_;
                    for (int oy = 0; oy <= 1; ++oy) {
                        double wyf = oy == 0 ? (1.0 - uy) : uy;
                        if (wyf < 0.05) continue;
                        int gy = gy0 + oy;
                        double vcy = (gy + 0.5) * voxelSize_;
                        for (int ox = 0; ox <= 1; ++ox) {
                            double wxf = ox == 0 ? (1.0 - ux) : ux;
                            float triW = static_cast<float>(wxf * wyf * wzf);
                            if (triW < 0.05f) continue;
                            int gx = gx0 + ox;
                            double vcx = (gx + 0.5) * voxelSize_;
                            double sdf = (vcx - px) * nx + (vcy - py) * ny + (vcz - pz) * nz;
                            if (sdf < -trunc || sdf > trunc) continue;
                            float tsdfSample = std::clamp(static_cast<float>(sdf / trunc), -1.0f, 1.0f);
                            VoxelBlock* blk = getOrCreate(floorDiv8(gx), floorDiv8(gy), floorDiv8(gz));
                            if (!blk) continue;
                            int blx = floorMod8(gx), bly = floorMod8(gy), blz = floorMod8(gz);
                            int vIdx = localIdx(blx, bly, blz);
                            float prevLW = blk->laserWeight[vIdx];
                            float effW = (prevLW > 2.0f ? pointWeight * 0.08f : pointWeight) * triW;
                            float prevW = blk->weight[vIdx];
                            blk->tsdf[vIdx] = prevW <= 0.0f ? tsdfSample : (blk->tsdf[vIdx] * prevW + tsdfSample * effW) / (prevW + effW);
                            blk->weight[vIdx] = std::min(kMaxVoxelWeight, prevW + effW);
                            {
                                uint64_t bkey = packBlockKey(blk->bx, blk->by, blk->bz);
                                auto& bn = blockNormals_[bkey];
                                if (!bn) bn = std::make_unique<BlockNormals>();
                                float nEffW = triW * pointWeight;
                                bn->nx[vIdx] += static_cast<float>(nx) * nEffW;
                                bn->ny[vIdx] += static_cast<float>(ny) * nEffW;
                                bn->nz[vIdx] += static_cast<float>(nz) * nEffW;
                                bn->nw[vIdx] += nEffW;
                            }
                            if (rgb) {
                                if (!blk->hasColor[vIdx]) {
                                    blk->r[vIdx] = cr; blk->g[vIdx] = cg; blk->b[vIdx] = cb; blk->hasColor[vIdx] = 1;
                                } else {
                                    float a = std::clamp(triW * 0.35f, 0.05f, 0.45f);
                                    blk->r[vIdx] = static_cast<uint8_t>(std::clamp(blk->r[vIdx] * (1.0f - a) + cr * a, 0.0f, 255.0f));
                                    blk->g[vIdx] = static_cast<uint8_t>(std::clamp(blk->g[vIdx] * (1.0f - a) + cg * a, 0.0f, 255.0f));
                                    blk->b[vIdx] = static_cast<uint8_t>(std::clamp(blk->b[vIdx] * (1.0f - a) + cb * a, 0.0f, 255.0f));
                                }
                            }
                            markDirtyAround(blk, blx, bly, blz);
                        }
                    }
                }
            }
            ++integrated;
        }
        integratedPatches_++;
        return integrated;
    }

    /** Extracts/updates dirty blocks and welds into [lastMesh_] in native RAM (0 B Java heap). */
    const ExtractedMesh& extractMesh() {
        std::lock_guard<std::mutex> g(mu_);
        int rebuilt = ensureDirtyBlocksCachedLocked();
        lastDirtyRebuilt_ = rebuilt;
        if (rebuilt == 0 && weldedVersion_ == dirtyVersion_ && !lastMesh_.indices.empty()) {
            return lastMesh_;
        }

        size_t estV = 0, estI = 0;
        int laserBlocks = 0, laserCells = 0;
        for (const auto& kv : blocks_) {
            if (kv.second->hasLaser) ++laserBlocks;
            if (kv.second->cached) {
                estV += kv.second->cached->verts.size();
                estI += kv.second->cached->indices.size();
                laserCells += kv.second->cached->laserVoxels;
            }
        }
        lastMesh_.verts.clear();
        lastMesh_.indices.clear();
        lastMesh_.laserBlocks = laserBlocks;
        lastMesh_.activeBlocks = static_cast<int>(blocks_.size());
        lastMesh_.laserCells = laserCells;
        lastMesh_.totalLaserHits = totalLaserHits_;
        if (estI == 0) {
            std::memset(lastMesh_.boundsMin, 0, sizeof(lastMesh_.boundsMin));
            std::memset(lastMesh_.boundsMax, 0, sizeof(lastMesh_.boundsMax));
            return lastMesh_;
        }

        lastMesh_.verts.reserve(estV);
        lastMesh_.indices.reserve(estI);
        std::unordered_map<uint64_t, uint32_t> welded;
        welded.reserve(estV);
        float minX = 1e30f, minY = 1e30f, minZ = 1e30f;
        float maxX = -1e30f, maxY = -1e30f, maxZ = -1e30f;
        std::vector<uint32_t> localToGlobal;
        for (const auto& kv : blocks_) {
            const BlockMesh* bm = kv.second->cached.get();
            if (!bm || bm->indices.empty()) continue;
            localToGlobal.resize(bm->verts.size());
            for (size_t k = 0; k < bm->verts.size(); ++k) {
                auto it = welded.find(bm->edgeKeys[k]);
                if (it != welded.end()) {
                    localToGlobal[k] = it->second;
                } else {
                    uint32_t gi = static_cast<uint32_t>(lastMesh_.verts.size());
                    welded.emplace(bm->edgeKeys[k], gi);
                    localToGlobal[k] = gi;
                    const GpuVertex& v = bm->verts[k];
                    lastMesh_.verts.push_back(v);
                    minX = std::min(minX, v.x); minY = std::min(minY, v.y); minZ = std::min(minZ, v.z);
                    maxX = std::max(maxX, v.x); maxY = std::max(maxY, v.y); maxZ = std::max(maxZ, v.z);
                }
            }
            for (uint32_t idx : bm->indices) lastMesh_.indices.push_back(localToGlobal[idx]);
        }
        lastMesh_.boundsMin[0] = minX; lastMesh_.boundsMin[1] = minY; lastMesh_.boundsMin[2] = minZ;
        lastMesh_.boundsMax[0] = maxX; lastMesh_.boundsMax[1] = maxY; lastMesh_.boundsMax[2] = maxZ;
        weldedVersion_ = dirtyVersion_;
        return lastMesh_;
    }

    /**
     * Fast raycast along the camera optical axis (+Z) to find the first front-facing TSDF
     * surface depth in metres (returns -1 if no surface lies along the center ray).
     */
    float raycastCenterDepthM(const Pose& pose) const {
        std::lock_guard<std::mutex> g(mu_);
        if (blocks_.empty()) return -1.0f;
        const double step = static_cast<double>(voxelSize_);
        double prevZ = 0.0;
        float prevVal = 0.0f;
        bool hasPrev = false;
        for (double z = 0.08; z <= maxDepth_; z += step) {
            double wx, wy, wz;
            pose.cameraToWorld(0.0, 0.0, z, wx, wy, wz);
            int gx = static_cast<int>(std::floor(wx * invVoxel_));
            int gy = static_cast<int>(std::floor(wy * invVoxel_));
            int gz = static_cast<int>(std::floor(wz * invVoxel_));
            auto it = blocks_.find(packBlockKey(floorDiv8(gx), floorDiv8(gy), floorDiv8(gz)));
            if (it == blocks_.end()) {
                hasPrev = false;
                continue;
            }
            int idx = localIdx(floorMod8(gx), floorMod8(gy), floorMod8(gz));
            if (it->second->weight[idx] < kMinCornerWeight) {
                hasPrev = false;
                continue;
            }
            float val = it->second->tsdf[idx];
            if (hasPrev && prevVal > 0.0f && val <= 0.0f) {
                float frac = prevVal / std::max(1e-6f, prevVal - val);
                return static_cast<float>(prevZ + (z - prevZ) * frac);
            }
            prevZ = z;
            prevVal = val;
            hasPrev = true;
        }
        return -1.0f;
    }

    /** Compact surface sample (up to maxPoints) for LaserIcp registration. */
    int sampleSurface(int maxPoints, float* outPos, float* outNorm) {
        const ExtractedMesh& m = extractMesh();
        const size_t n = m.verts.size();
        if (n == 0 || maxPoints <= 0) return 0;
        const size_t stride = std::max<size_t>(1, n / static_cast<size_t>(maxPoints));
        int w = 0;
        for (size_t i = 0; i < n && w < maxPoints; i += stride) {
            const GpuVertex& v = m.verts[i];
            outPos[w * 3] = v.x; outPos[w * 3 + 1] = v.y; outPos[w * 3 + 2] = v.z;
            outNorm[w * 3] = v.nx; outNorm[w * 3 + 1] = v.ny; outNorm[w * 3 + 2] = v.nz;
            ++w;
        }
        return w;
    }

    /**
     * Sharp-edge Dual Contouring (Ju et al. 2002) mesh extraction over the sparse TSDF.
     *
     * Solves a regularized 3x3 Quadratic Error Function (QEF) per sign-changing voxel cell
     * using TSDF gradients blended with integrated PatchMatch oriented normals, preserving
     * crisp 90-degree edges and flat mechanical faces instead of Marching Cubes' ~0.5-voxel bevel.
     */
    ExtractedMesh extractDualContouringMesh() {
        std::lock_guard<std::mutex> g(mu_);
        ExtractedMesh out;
        out.activeBlocks = static_cast<int>(blocks_.size());
        out.totalLaserHits = totalLaserHits_;
        if (blocks_.empty()) return out;

        std::unordered_map<uint64_t, uint32_t> cellVert;
        cellVert.reserve(blocks_.size() * 24);
        out.verts.reserve(blocks_.size() * 24);
        out.indices.reserve(blocks_.size() * 96);

        float bMin[3] = {1e9f, 1e9f, 1e9f};
        float bMax[3] = {-1e9f, -1e9f, -1e9f};
        CornerSample cs[8];

        auto orientedNormalAt = [&](int gx, int gy, int gz, float& onx, float& ony, float& onz) -> bool {
            auto it = blockNormals_.find(packBlockKey(floorDiv8(gx), floorDiv8(gy), floorDiv8(gz)));
            if (it == blockNormals_.end() || !it->second) return false;
            int idx = localIdx(floorMod8(gx), floorMod8(gy), floorMod8(gz));
            if (it->second->nw[idx] <= 1e-4f) return false;
            float x = it->second->nx[idx], y = it->second->ny[idx], z = it->second->nz[idx];
            float len = std::sqrt(x * x + y * y + z * z);
            if (len < 1e-6f) return false;
            onx = x / len; ony = y / len; onz = z / len;
            return true;
        };

        // Pass 1: Solve 3x3 QEF vertex per sign-changing cell.
        for (const auto& kv : blocks_) {
            const VoxelBlock* blk = kv.second.get();
            if (blk->hasLaser) out.laserBlocks++;
            int gx0 = blk->bx * kBlockDim, gy0 = blk->by * kBlockDim, gz0 = blk->bz * kBlockDim;
            for (int lz = 0; lz < kBlockDim; ++lz) {
                for (int ly = 0; ly < kBlockDim; ++ly) {
                    for (int lx = 0; lx < kBlockDim; ++lx) {
                        int selfIdx = localIdx(lx, ly, lz);
                        if (blk->laserWeight[selfIdx] > 0.0f) out.laserCells++;
                        if (blk->weight[selfIdx] < kMinCornerWeight) continue;
                        int mask = 0;
                        bool allOk = true;
                        for (int c = 0; c < 8; ++c) {
                            int cx = lx + kCornerDx[c], cy = ly + kCornerDy[c], cz = lz + kCornerDz[c];
                            if (!sampleCornerLocked(blk, gx0 + cx, gy0 + cy, gz0 + cz, cx, cy, cz, cs[c])) {
                                allOk = false;
                                break;
                            }
                            if (cs[c].tsdf < 0.0f) mask |= (1 << c);
                        }
                        if (!allOk || mask == 0 || mask == 255) continue;

                        double px[12], py[12], pz[12], nx[12], ny[12], nz[12];
                        int nCross = 0;
                        double mpX = 0.0, mpY = 0.0, mpZ = 0.0;
                        float avgR = 0.0f, avgG = 0.0f, avgB = 0.0f, avgLw = 0.0f;
                        double sumNx = 0.0, sumNy = 0.0, sumNz = 0.0;

                        for (int e = 0; e < 12; ++e) {
                            int ca = kEdgeA[e], cb = kEdgeB[e];
                            if (((mask >> ca) & 1) == ((mask >> cb) & 1)) continue;
                            float va = cs[ca].tsdf, vb = cs[cb].tsdf, den = vb - va;
                            float t = std::abs(den) > 1e-6f ? std::clamp(-va / den, 0.0f, 1.0f) : 0.5f;
                            double ex = kCornerDx[ca] + t * (kCornerDx[cb] - kCornerDx[ca]);
                            double ey = kCornerDy[ca] + t * (kCornerDy[cb] - kCornerDy[ca]);
                            double ez = kCornerDz[ca] + t * (kCornerDz[cb] - kCornerDz[ca]);

                            int gax = gx0 + lx + kCornerDx[ca], gay = gy0 + ly + kCornerDy[ca], gaz = gz0 + lz + kCornerDz[ca];
                            int gbx = gx0 + lx + kCornerDx[cb], gby = gy0 + ly + kCornerDy[cb], gbz = gz0 + lz + kCornerDz[cb];
                            float nax, nay, naz, nbx, nby, nbz;
                            gradientLocked(gax, gay, gaz, nax, nay, naz);
                            gradientLocked(gbx, gby, gbz, nbx, nby, nbz);
                            double gnx = nax * (1.0f - t) + nbx * t;
                            double gny = nay * (1.0f - t) + nby * t;
                            double gnz = naz * (1.0f - t) + nbz * t;
                            double gl = std::sqrt(gnx * gnx + gny * gny + gnz * gnz);
                            if (gl > 1e-6) { gnx /= gl; gny /= gl; gnz /= gl; }
                            else { gnx = 0.0; gny = 0.0; gnz = 1.0; }

                            // Blend PatchMatch oriented normal if available and consistent with TSDF gradient.
                            float oax, oay, oaz, obx, oby, obz;
                            bool ha = orientedNormalAt(gax, gay, gaz, oax, oay, oaz);
                            bool hb = orientedNormalAt(gbx, gby, gbz, obx, oby, obz);
                            if (ha || hb) {
                                double onx = (ha && hb) ? (oax * (1.0 - t) + obx * t) : (ha ? oax : obx);
                                double ony = (ha && hb) ? (oay * (1.0 - t) + oby * t) : (ha ? oay : oby);
                                double onz = (ha && hb) ? (oaz * (1.0 - t) + obz * t) : (ha ? oaz : obz);
                                double ol = std::sqrt(onx * onx + ony * ony + onz * onz);
                                if (ol > 1e-5) {
                                    onx /= ol; ony /= ol; onz /= ol;
                                    if (gnx * onx + gny * ony + gnz * onz > 0.5) {
                                        gnx = 0.65 * gnx + 0.35 * onx;
                                        gny = 0.65 * gny + 0.35 * ony;
                                        gnz = 0.65 * gnz + 0.35 * onz;
                                        gl = std::sqrt(gnx * gnx + gny * gny + gnz * gnz);
                                        if (gl > 1e-6) { gnx /= gl; gny /= gl; gnz /= gl; }
                                    }
                                }
                            }

                            px[nCross] = ex; py[nCross] = ey; pz[nCross] = ez;
                            nx[nCross] = gnx; ny[nCross] = gny; nz[nCross] = gnz;
                            mpX += ex; mpY += ey; mpZ += ez;
                            sumNx += gnx; sumNy += gny; sumNz += gnz;
                            avgR += cs[ca].r * (1.0f - t) + cs[cb].r * t;
                            avgG += cs[ca].g * (1.0f - t) + cs[cb].g * t;
                            avgB += cs[ca].b * (1.0f - t) + cs[cb].b * t;
                            avgLw += cs[ca].laserW * (1.0f - t) + cs[cb].laserW * t;
                            ++nCross;
                        }
                        if (nCross == 0) continue;
                        double invC = 1.0 / nCross;
                        mpX *= invC; mpY *= invC; mpZ *= invC;

                        // Assemble regularized 3x3 QEF: (sum n_k n_k^T + lambda I) * delta = sum n_k (n_k . (p_k - mp)).
                        constexpr double kReg = 0.04;
                        double a00 = kReg, a01 = 0.0, a02 = 0.0, a11 = kReg, a12 = 0.0, a22 = kReg;
                        double b0 = 0.0, b1 = 0.0, b2 = 0.0;
                        for (int k = 0; k < nCross; ++k) {
                            double rx = px[k] - mpX, ry = py[k] - mpY, rz = pz[k] - mpZ;
                            double d = nx[k] * rx + ny[k] * ry + nz[k] * rz;
                            a00 += nx[k] * nx[k]; a01 += nx[k] * ny[k]; a02 += nx[k] * nz[k];
                            a11 += ny[k] * ny[k]; a12 += ny[k] * nz[k]; a22 += nz[k] * nz[k];
                            b0 += nx[k] * d; b1 += ny[k] * d; b2 += nz[k] * d;
                        }
                        // Exact symmetric 3x3 linear solve (always SPD because kReg > 0).
                        double c00 = a11 * a22 - a12 * a12;
                        double c01 = a02 * a12 - a01 * a22;
                        double c02 = a01 * a12 - a02 * a11;
                        double c11 = a00 * a22 - a02 * a02;
                        double c12 = a01 * a02 - a00 * a12;
                        double c22 = a00 * a11 - a01 * a01;
                        double det = a00 * c00 + a01 * c01 + a02 * c02;
                        double dx = 0.0, dy = 0.0, dz = 0.0;
                        if (std::abs(det) > 1e-12) {
                            double invDet = 1.0 / det;
                            dx = (c00 * b0 + c01 * b1 + c02 * b2) * invDet;
                            dy = (c01 * b0 + c11 * b1 + c12 * b2) * invDet;
                            dz = (c02 * b0 + c12 * b1 + c22 * b2) * invDet;
                        }
                        double lxPos = std::clamp(mpX + dx, 0.02, 0.98);
                        double lyPos = std::clamp(mpY + dy, 0.02, 0.98);
                        double lzPos = std::clamp(mpZ + dz, 0.02, 0.98);

                        int gx = gx0 + lx, gy = gy0 + ly, gz = gz0 + lz;
                        float wx = static_cast<float>((gx + lxPos + 0.5) * voxelSize_);
                        float wy = static_cast<float>((gy + lyPos + 0.5) * voxelSize_);
                        float wz = static_cast<float>((gz + lzPos + 0.5) * voxelSize_);

                        double vnl = std::sqrt(sumNx * sumNx + sumNy * sumNy + sumNz * sumNz);
                        float vnx = vnl > 1e-6 ? static_cast<float>(sumNx / vnl) : 0.0f;
                        float vny = vnl > 1e-6 ? static_cast<float>(sumNy / vnl) : 0.0f;
                        float vnz = vnl > 1e-6 ? static_cast<float>(sumNz / vnl) : 1.0f;
                        float lw = static_cast<float>(avgLw * invC);
                        float rw = lw > 0.0f ? std::clamp(lw / (lw + 1.5f), 0.0f, 1.0f) : 0.0f;

                        uint32_t vi = static_cast<uint32_t>(out.verts.size());
                        cellVert.emplace(packCellKey(gx, gy, gz), vi);
                        out.verts.push_back({
                            wx, wy, wz, vnx, vny, vnz,
                            static_cast<float>(avgR * invC),
                            static_cast<float>(avgG * invC),
                            static_cast<float>(avgB * invC),
                            rw
                        });
                        if (wx < bMin[0]) bMin[0] = wx;
                        if (wy < bMin[1]) bMin[1] = wy;
                        if (wz < bMin[2]) bMin[2] = wz;
                        if (wx > bMax[0]) bMax[0] = wx;
                        if (wy > bMax[1]) bMax[1] = wy;
                        if (wz > bMax[2]) bMax[2] = wz;
                    }
                }
            }
        }

        auto emitQuad = [&](uint32_t v0, uint32_t v1, uint32_t v2, uint32_t v3, bool flip) {
            const GpuVertex& p0 = out.verts[v0];
            const GpuVertex& p1 = out.verts[v1];
            const GpuVertex& p2 = out.verts[v2];
            const GpuVertex& p3 = out.verts[v3];
            float d02 = (p0.x - p2.x) * (p0.x - p2.x) + (p0.y - p2.y) * (p0.y - p2.y) + (p0.z - p2.z) * (p0.z - p2.z);
            float d13 = (p1.x - p3.x) * (p1.x - p3.x) + (p1.y - p3.y) * (p1.y - p3.y) + (p1.z - p3.z) * (p1.z - p3.z);
            auto pushTri = [&](uint32_t a, uint32_t b, uint32_t c) {
                if (flip) {
                    out.indices.push_back(a); out.indices.push_back(c); out.indices.push_back(b);
                } else {
                    out.indices.push_back(a); out.indices.push_back(b); out.indices.push_back(c);
                }
            };
            if (d02 <= d13) {
                pushTri(v0, v1, v2);
                pushTri(v0, v2, v3);
            } else {
                pushTri(v0, v1, v3);
                pushTri(v1, v2, v3);
            }
        };

        auto lookup4 = [&](int x0, int y0, int z0, int x1, int y1, int z1,
                           int x2, int y2, int z2, int x3, int y3, int z3,
                           uint32_t& v0, uint32_t& v1, uint32_t& v2, uint32_t& v3) -> bool {
            auto i0 = cellVert.find(packCellKey(x0, y0, z0));
            if (i0 == cellVert.end()) return false;
            auto i1 = cellVert.find(packCellKey(x1, y1, z1));
            if (i1 == cellVert.end()) return false;
            auto i2 = cellVert.find(packCellKey(x2, y2, z2));
            if (i2 == cellVert.end()) return false;
            auto i3 = cellVert.find(packCellKey(x3, y3, z3));
            if (i3 == cellVert.end()) return false;
            v0 = i0->second; v1 = i1->second; v2 = i2->second; v3 = i3->second;
            return true;
        };

        // Pass 2: Emit quads across sign-changing interior X, Y, Z grid edges.
        for (const auto& kv : blocks_) {
            const VoxelBlock* blk = kv.second.get();
            int gx0 = blk->bx * kBlockDim, gy0 = blk->by * kBlockDim, gz0 = blk->bz * kBlockDim;
            for (int lz = 0; lz < kBlockDim; ++lz) {
                for (int ly = 0; ly < kBlockDim; ++ly) {
                    for (int lx = 0; lx < kBlockDim; ++lx) {
                        int idx = localIdx(lx, ly, lz);
                        if (blk->weight[idx] < kMinCornerWeight) continue;
                        bool s0 = blk->tsdf[idx] < 0.0f;
                        int gx = gx0 + lx, gy = gy0 + ly, gz = gz0 + lz;
                        CornerSample nb;
                        uint32_t v0, v1, v2, v3;

                        // X-edge: (gx, gy, gz) -> (gx+1, gy, gz)
                        if (sampleCornerLocked(blk, gx + 1, gy, gz, lx + 1, ly, lz, nb) && ((nb.tsdf < 0.0f) != s0)) {
                            if (lookup4(gx, gy - 1, gz - 1, gx, gy, gz - 1, gx, gy, gz, gx, gy - 1, gz, v0, v1, v2, v3)) {
                                emitQuad(v0, v1, v2, v3, !s0);
                            }
                        }
                        // Y-edge: (gx, gy, gz) -> (gx, gy+1, gz)
                        if (sampleCornerLocked(blk, gx, gy + 1, gz, lx, ly + 1, lz, nb) && ((nb.tsdf < 0.0f) != s0)) {
                            if (lookup4(gx - 1, gy, gz - 1, gx - 1, gy, gz, gx, gy, gz, gx, gy, gz - 1, v0, v1, v2, v3)) {
                                emitQuad(v0, v1, v2, v3, !s0);
                            }
                        }
                        // Z-edge: (gx, gy, gz) -> (gx, gy, gz+1)
                        if (sampleCornerLocked(blk, gx, gy, gz + 1, lx, ly, lz + 1, nb) && ((nb.tsdf < 0.0f) != s0)) {
                            if (lookup4(gx - 1, gy - 1, gz, gx, gy - 1, gz, gx, gy, gz, gx - 1, gy, gz, v0, v1, v2, v3)) {
                                emitQuad(v0, v1, v2, v3, !s0);
                            }
                        }
                    }
                }
            }
        }

        if (!out.verts.empty()) {
            for (int k = 0; k < 3; ++k) { out.boundsMin[k] = bMin[k]; out.boundsMax[k] = bMax[k]; }
        }
        return out;
    }

    int writeMeshPlyDualContouring(const std::string& path, bool toMillimetres) {
        ExtractedMesh m = extractDualContouringMesh();
        return writeMeshPlyFrom(m, path, toMillimetres);
    }

    int writeMeshStlDualContouring(const std::string& path, bool toMillimetres) {
        ExtractedMesh m = extractDualContouringMesh();
        return writeMeshStlFrom(m, path, toMillimetres);
    }

    /** Writes binary little-endian mesh PLY directly from native C++ memory (0 B Java heap). */
    int writeMeshPly(const std::string& path, bool toMillimetres) {
        return writeMeshPlyFrom(extractMesh(), path, toMillimetres);
    }

    int writeMeshPlyFrom(const ExtractedMesh& m, const std::string& path, bool toMillimetres) {
        const size_t vc = m.verts.size();
        const size_t fc = m.indices.size() / 3;
        if (vc == 0 || fc == 0) return 0;
        std::ofstream out(path, std::ios::binary | std::ios::trunc);
        if (!out) return -1;
        char hdr[512];
        int n = std::snprintf(
            hdr, sizeof(hdr),
            "ply\nformat binary_little_endian 1.0\n"
            "comment EasyScanner native TSDF mesh (%s, blocks=%d, laserBlocks=%d)\n"
            "element vertex %zu\n"
            "property float x\nproperty float y\nproperty float z\n"
            "property float nx\nproperty float ny\nproperty float nz\n"
            "property uchar red\nproperty uchar green\nproperty uchar blue\n"
            "element face %zu\n"
            "property list uchar int vertex_indices\nend_header\n",
            toMillimetres ? "mm" : "m", m.activeBlocks, m.laserBlocks, vc, fc);
        out.write(hdr, n);
        const float s = toMillimetres ? 1000.0f : 1.0f;
        std::vector<uint8_t> chunk;
        chunk.reserve(65536);
        auto flushChunk = [&]() {
            if (!chunk.empty()) {
                out.write(reinterpret_cast<const char*>(chunk.data()), static_cast<std::streamsize>(chunk.size()));
                chunk.clear();
            }
        };
        for (size_t i = 0; i < vc; ++i) {
            const GpuVertex& v = m.verts[i];
            float f6[6] = { v.x * s, v.y * s, v.z * s, v.nx, v.ny, v.nz };
            uint8_t c3[3] = {
                static_cast<uint8_t>(std::clamp(v.r * 255.0f, 0.0f, 255.0f)),
                static_cast<uint8_t>(std::clamp(v.g * 255.0f, 0.0f, 255.0f)),
                static_cast<uint8_t>(std::clamp(v.b * 255.0f, 0.0f, 255.0f)),
            };
            const uint8_t* fp = reinterpret_cast<const uint8_t*>(f6);
            chunk.insert(chunk.end(), fp, fp + 24);
            chunk.insert(chunk.end(), c3, c3 + 3);
            if (chunk.size() >= 64000) flushChunk();
        }
        flushChunk();
        for (size_t i = 0; i < fc; ++i) {
            uint8_t cnt = 3;
            int32_t tri[3] = {
                static_cast<int32_t>(m.indices[i * 3]),
                static_cast<int32_t>(m.indices[i * 3 + 1]),
                static_cast<int32_t>(m.indices[i * 3 + 2]),
            };
            chunk.push_back(cnt);
            const uint8_t* tp = reinterpret_cast<const uint8_t*>(tri);
            chunk.insert(chunk.end(), tp, tp + 12);
            if (chunk.size() >= 64000) flushChunk();
        }
        flushChunk();
        return out.good() ? static_cast<int>(fc) : -1;
    }

    /** Writes a binary little-endian STL directly from native C++ memory (0 B Java heap). */
    int writeMeshStl(const std::string& path, bool toMillimetres) {
        return writeMeshStlFrom(extractMesh(), path, toMillimetres);
    }

    int writeMeshStlFrom(const ExtractedMesh& m, const std::string& path, bool toMillimetres) {
        const size_t vc = m.verts.size();
        const size_t fc = m.indices.size() / 3;
        if (vc == 0 || fc == 0) return 0;
        std::ofstream out(path, std::ios::binary | std::ios::trunc);
        if (!out) return -1;
        char hdr[80] = {};
        std::snprintf(
            hdr, sizeof(hdr),
            "TrioSlicer Laser+MVS TSDF (%s, blocks=%d, laser=%d)",
            toMillimetres ? "mm" : "m", m.activeBlocks, m.laserBlocks);
        out.write(hdr, 80);
        uint32_t triCount = static_cast<uint32_t>(fc);
        out.write(reinterpret_cast<const char*>(&triCount), 4);
        const float s = toMillimetres ? 1000.0f : 1.0f;
        std::vector<uint8_t> chunk;
        chunk.reserve(65536);
        auto flushChunk = [&]() {
            if (!chunk.empty()) {
                out.write(reinterpret_cast<const char*>(chunk.data()), static_cast<std::streamsize>(chunk.size()));
                chunk.clear();
            }
        };
        for (size_t i = 0; i < fc; ++i) {
            const GpuVertex& a = m.verts[m.indices[i * 3]];
            const GpuVertex& b = m.verts[m.indices[i * 3 + 1]];
            const GpuVertex& c = m.verts[m.indices[i * 3 + 2]];
            float e1x = b.x - a.x, e1y = b.y - a.y, e1z = b.z - a.z;
            float e2x = c.x - a.x, e2y = c.y - a.y, e2z = c.z - a.z;
            float nx = e1y * e2z - e1z * e2y;
            float ny = e1z * e2x - e1x * e2z;
            float nz = e1x * e2y - e1y * e2x;
            float nl = std::sqrt(nx * nx + ny * ny + nz * nz);
            if (nl > 1e-12f) { nx /= nl; ny /= nl; nz /= nl; } else { nx = 0; ny = 0; nz = 1; }
            // Align triangle winding with outward TSDF vertex normals so slicers see positive volume.
            float vnx = a.nx + b.nx + c.nx, vny = a.ny + b.ny + c.ny, vnz = a.nz + b.nz + c.nz;
            const bool flip = (nx * vnx + ny * vny + nz * vnz) < 0.0f;
            if (flip) { nx = -nx; ny = -ny; nz = -nz; }
            const GpuVertex& v1 = flip ? c : b;
            const GpuVertex& v2 = flip ? b : c;
            float f12[12] = {
                nx, ny, nz,
                a.x * s, a.y * s, a.z * s,
                v1.x * s, v1.y * s, v1.z * s,
                v2.x * s, v2.y * s, v2.z * s,
            };
            const uint8_t* fp = reinterpret_cast<const uint8_t*>(f12);
            chunk.insert(chunk.end(), fp, fp + 48);
            chunk.push_back(0);
            chunk.push_back(0);
            if (chunk.size() >= 64000) flushChunk();
        }
        flushChunk();
        return out.good() ? static_cast<int>(fc) : -1;
    }

private:
    VoxelBlock* getOrCreate(int bx, int by, int bz) {
        uint64_t key = packBlockKey(bx, by, bz);
        auto it = blocks_.find(key);
        if (it != blocks_.end()) return it->second.get();
        if (static_cast<int>(blocks_.size()) >= maxBlocks_) return nullptr;
        auto ptr = std::make_unique<VoxelBlock>(bx, by, bz);
        VoxelBlock* raw = ptr.get();
        blocks_.emplace(key, std::move(ptr));
        return raw;
    }

    static constexpr int kOccCell = 4;
    static constexpr int kOccDim = (kDa3Size + kOccCell - 1) / kOccCell; // 130

    int ensureDirtyBlocksCachedLocked() {
        int rebuilt = 0;
        for (auto& kv : blocks_) {
            VoxelBlock* blk = kv.second.get();
            if (blk->dirty) {
                blk->cached = extractBlockLocked(blk);
                blk->dirty = false;
                ++rebuilt;
            }
        }
        return rebuilt;
    }

    /**
     * Projects the existing TSDF surface into the new camera's 130x130 puzzle occupancy map:
     *   0 = uncovered empty space (place new puzzle piece here)
     *   1 = already-covered interior (never paste or overwrite here)
     *   2 = narrow seam border adjacent to uncovered space (gentle watertight weld only)
     */
    void buildPuzzleOccupancyLocked(
        const Pose& pose, const Camera& cam, const float* rawDepth,
        double scale, double offset, std::vector<uint8_t>& occ
    ) {
        ensureDirtyBlocksCachedLocked();
        occ.assign(kOccDim * kOccDim, 0);
        double camX, camY, camZ;
        pose.cameraCentre(camX, camY, camZ);

        for (const auto& kv : blocks_) {
            const BlockMesh* bm = kv.second->cached.get();
            if (!bm || bm->verts.empty()) continue;
            for (const GpuVertex& v : bm->verts) {
                double xc, yc, zc;
                pose.worldToCamera(v.x, v.y, v.z, xc, yc, zc);
                if (zc <= 0.05 || zc > maxDepth_ + 0.15) continue;
                double u, vpx;
                if (!cam.project(xc, yc, zc, u, vpx)) continue;
                if (u < 0.0 || u >= cam.width || vpx < 0.0 || vpx >= cam.height) continue;
                int sx = std::clamp(static_cast<int>(u * kDa3Size / cam.width), 0, kDa3Size - 1);
                int sy = std::clamp(static_cast<int>(vpx * kDa3Size / cam.height), 0, kDa3Size - 1);
                float dNew = rawDepth[sy * kDa3Size + sx];
                if (!(dNew > 0.0f) || !std::isfinite(dNew)) continue;
                double zNew = dNew * scale + offset;
                double dz = std::abs(zc - zNew);
                double vx = camX - v.x, vy = camY - v.y, vz = camZ - v.z;
                double vlen = std::sqrt(vx * vx + vy * vy + vz * vz);
                double ndot = vlen > 1e-6 ? (v.nx * vx + v.ny * vy + v.nz * vz) / vlen : 0.0;
                double sameLayerTol = std::max(0.10, 0.25 * zc);
                // Block any front-facing existing piece within the same surface layer (even if DA3 warped
                // by several cm), while still letting a distinct back face / far foreground object scan.
                if ((ndot > 0.0 && dz < sameLayerTol) || (dz < 0.025)) {
                    int gx = sx / kOccCell;
                    int gy = sy / kOccCell;
                    int y0 = std::max(0, gy - 1), y1 = std::min(kOccDim - 1, gy + 1);
                    int x0 = std::max(0, gx - 1), x1 = std::min(kOccDim - 1, gx + 1);
                    for (int yy = y0; yy <= y1; ++yy)
                        for (int xx = x0; xx <= x1; ++xx)
                            occ[yy * kOccDim + xx] = 1;
                }
            }
        }

        // Promote a 2-cell (~8 px) seam border next to uncovered cells so new puzzle pieces weld cleanly.
        std::vector<uint8_t> seam = occ;
        for (int gy = 0; gy < kOccDim; ++gy) {
            int y0 = std::max(0, gy - 2), y1 = std::min(kOccDim - 1, gy + 2);
            for (int gx = 0; gx < kOccDim; ++gx) {
                int idx = gy * kOccDim + gx;
                if (occ[idx] != 1) continue;
                int x0 = std::max(0, gx - 2), x1 = std::min(kOccDim - 1, gx + 2);
                bool touchesEmpty = false;
                for (int yy = y0; yy <= y1 && !touchesEmpty; ++yy) {
                    for (int xx = x0; xx <= x1; ++xx) {
                        if (occ[yy * kOccDim + xx] == 0) { touchesEmpty = true; break; }
                    }
                }
                if (touchesEmpty) seam[idx] = 2;
            }
        }
        occ.swap(seam);
    }

    void markDirtyAround(VoxelBlock* blk, int lx, int ly, int lz) {
        blk->dirty = true;
        dirtyVersion_++;
        int mx = lx == 0 ? -1 : 0, my = ly == 0 ? -1 : 0, mz = lz == 0 ? -1 : 0;
        if (mx == 0 && my == 0 && mz == 0) return;
        for (int dz = mz; dz <= 0; ++dz)
            for (int dy = my; dy <= 0; ++dy)
                for (int dx = mx; dx <= 0; ++dx) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    auto it = blocks_.find(packBlockKey(blk->bx + dx, blk->by + dy, blk->bz + dz));
                    if (it != blocks_.end()) it->second->dirty = true;
                }
    }

    struct CornerSample { float tsdf, weight, laserW, r, g, b; };

    bool sampleCornerLocked(const VoxelBlock* blk, int gx, int gy, int gz, int lx, int ly, int lz, CornerSample& out) const {
        const VoxelBlock* target = nullptr;
        int idx = 0;
        if (lx >= 0 && lx < kBlockDim && ly >= 0 && ly < kBlockDim && lz >= 0 && lz < kBlockDim) {
            target = blk; idx = localIdx(lx, ly, lz);
        } else {
            auto it = blocks_.find(packBlockKey(floorDiv8(gx), floorDiv8(gy), floorDiv8(gz)));
            if (it == blocks_.end()) return false;
            target = it->second.get();
            idx = localIdx(floorMod8(gx), floorMod8(gy), floorMod8(gz));
        }
        float w = target->weight[idx];
        if (w < kMinCornerWeight) return false;
        out.tsdf = target->tsdf[idx];
        out.weight = w;
        out.laserW = target->laserWeight[idx];
        out.r = target->r[idx] / 255.0f;
        out.g = target->g[idx] / 255.0f;
        out.b = target->b[idx] / 255.0f;
        return true;
    }

    float tsdfAtLocked(int gx, int gy, int gz, float fallback) const {
        auto it = blocks_.find(packBlockKey(floorDiv8(gx), floorDiv8(gy), floorDiv8(gz)));
        if (it == blocks_.end()) return fallback;
        int idx = localIdx(floorMod8(gx), floorMod8(gy), floorMod8(gz));
        return it->second->weight[idx] >= kMinCornerWeight ? it->second->tsdf[idx] : fallback;
    }

    void gradientLocked(int gx, int gy, int gz, float& dx, float& dy, float& dz) const {
        float c = tsdfAtLocked(gx, gy, gz, 0.0f);
        dx = tsdfAtLocked(gx + 1, gy, gz, c) - tsdfAtLocked(gx - 1, gy, gz, c);
        dy = tsdfAtLocked(gx, gy + 1, gz, c) - tsdfAtLocked(gx, gy - 1, gz, c);
        dz = tsdfAtLocked(gx, gy, gz + 1, c) - tsdfAtLocked(gx, gy, gz - 1, c);
    }

    std::unique_ptr<BlockMesh> extractBlockLocked(const VoxelBlock* blk) const {
        int gx0 = blk->bx * kBlockDim, gy0 = blk->by * kBlockDim, gz0 = blk->bz * kBlockDim;
        int edgeCache[9 * 9 * 9 * 3];
        std::fill_n(edgeCache, 9 * 9 * 9 * 3, -1);
        auto bm = std::make_unique<BlockMesh>();
        bm->verts.reserve(128); bm->edgeKeys.reserve(128); bm->indices.reserve(384);
        CornerSample cs[8];
        uint32_t eVerts[12];

        for (int lz = 0; lz < kBlockDim; ++lz) {
            for (int ly = 0; ly < kBlockDim; ++ly) {
                for (int lx = 0; lx < kBlockDim; ++lx) {
                    int selfIdx = localIdx(lx, ly, lz);
                    if (blk->laserWeight[selfIdx] > 0.0f) bm->laserVoxels++;
                    if (blk->weight[selfIdx] < kMinCornerWeight) continue;
                    int mask = 0;
                    bool allOk = true;
                    for (int c = 0; c < 8; ++c) {
                        int cx = lx + kCornerDx[c], cy = ly + kCornerDy[c], cz = lz + kCornerDz[c];
                        if (!sampleCornerLocked(blk, gx0 + cx, gy0 + cy, gz0 + cz, cx, cy, cz, cs[c])) {
                            allOk = false; break;
                        }
                        if (cs[c].tsdf < 0.0f) mask |= (1 << c);
                    }
                    if (!allOk) continue;
                    uint16_t emask = kEdgeTable[mask];
                    if (emask == 0) continue;

                    for (int e = 0; e < 12; ++e) {
                        if ((emask & (1u << e)) == 0) continue;
                        int ca = kEdgeA[e], cb = kEdgeB[e];
                        int ax = lx + kCornerDx[ca], ay = ly + kCornerDy[ca], az = lz + kCornerDz[ca];
                        int bx = lx + kCornerDx[cb], by = ly + kCornerDy[cb], bz = lz + kCornerDz[cb];
                        int ox = std::min(ax, bx), oy = std::min(ay, by), oz = std::min(az, bz);
                        int axis = ax != bx ? 0 : (ay != by ? 1 : 2);
                        int slot = ((oz * 9 + oy) * 9 + ox) * 3 + axis;
                        if (edgeCache[slot] >= 0) {
                            eVerts[e] = static_cast<uint32_t>(edgeCache[slot]);
                            continue;
                        }
                        float va = cs[ca].tsdf, vb = cs[cb].tsdf, den = vb - va;
                        float t = std::abs(den) > 1e-6f ? std::clamp(-va / den, 0.0f, 1.0f) : 0.5f;
                        float wx = (gx0 + ax + t * (bx - ax) + 0.5f) * voxelSize_;
                        float wy = (gy0 + ay + t * (by - ay) + 0.5f) * voxelSize_;
                        float wz = (gz0 + az + t * (bz - az) + 0.5f) * voxelSize_;
                        float nax, nay, naz, nbx, nby, nbz;
                        gradientLocked(gx0 + ax, gy0 + ay, gz0 + az, nax, nay, naz);
                        gradientLocked(gx0 + bx, gy0 + by, gz0 + bz, nbx, nby, nbz);
                        float nx = nax * (1.0f - t) + nbx * t;
                        float ny = nay * (1.0f - t) + nby * t;
                        float nz = naz * (1.0f - t) + nbz * t;
                        float nl = std::sqrt(nx * nx + ny * ny + nz * nz);
                        if (nl > 1e-6f) { nx /= nl; ny /= nl; nz /= nl; } else { nx = 0; ny = 0; nz = 1; }
                        float rr = cs[ca].r * (1.0f - t) + cs[cb].r * t;
                        float gg = cs[ca].g * (1.0f - t) + cs[cb].g * t;
                        float bb = cs[ca].b * (1.0f - t) + cs[cb].b * t;
                        float lw = cs[ca].laserW * (1.0f - t) + cs[cb].laserW * t;
                        float rw = lw > 0.0f ? std::clamp(lw / (lw + 1.5f), 0.0f, 1.0f) : 0.0f;
                        uint32_t vi = static_cast<uint32_t>(bm->verts.size());
                        edgeCache[slot] = static_cast<int>(vi);
                        eVerts[e] = vi;
                        bm->verts.push_back({wx, wy, wz, nx, ny, nz, rr, gg, bb, rw});
                        bm->edgeKeys.push_back(packEdgeKey(gx0 + ox, gy0 + oy, gz0 + oz, axis));
                    }

                    const int8_t* trow = kTriTable[mask];
                    for (int k = 0; k < 15 && trow[k] >= 0; k += 3) {
                        bm->indices.push_back(eVerts[trow[k]]);
                        bm->indices.push_back(eVerts[trow[k + 1]]);
                        bm->indices.push_back(eVerts[trow[k + 2]]);
                    }
                }
            }
        }
        if (bm->indices.empty()) return nullptr;
        return bm;
    }

    mutable std::mutex mu_;
    float voxelSize_;
    float trunc_;
    double invVoxel_;
    int maxBlocks_;
    double maxDepth_;
    std::unordered_map<uint64_t, std::unique_ptr<VoxelBlock>> blocks_;
    std::unordered_map<uint64_t, std::unique_ptr<BlockNormals>> blockNormals_;
    int totalLaserHits_ = 0;
    int integratedPatches_ = 0;
    int lastDirtyRebuilt_ = 0;
    uint64_t dirtyVersion_ = 1;
    uint64_t weldedVersion_ = 0;
    ExtractedMesh lastMesh_;
};

} // namespace livetsdf
