// Parallax: Laser-Guided Multi-View PatchMatch Stereo (MVS).
//
// Replaces 2-view rectified StereoSGBM with direct unrectified multi-view PatchMatch MVS
// (Bleyer et al. 2011; Schoenberger et al. 2016; Xu & Tao 2019 ACMH):
//   - Jointly estimates per-pixel depth z and 3-D surface normal n in the reference camera frame
//     across up to 5 covisible neighbor views using plane-induced homographies (no stereo
//     rectification distortion, handles arbitrary handheld camera tilts and slanted walls).
//   - Scores hypotheses with bilaterally weighted zero-mean NCC over a 13-tap 9x9 checkerboard
//     patch, averaging the top-K unoccluded source views.
//   - Seeds depth hypotheses from sparse SfM tracks AND (when present in hybrid/hybrid.json)
//     calibrated metric laser frames so blank/featureless surfaces stay locked to true depth.
//   - Filters via multi-view geometric depth + normal consistency and fuses into a voxel grid
//     carrying true PatchMatch surface normals (feeding Dual Contouring sharp-edge extraction).
// SPDX-License-Identifier: GPL-3.0-or-later
#include <opencv2/calib3d.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "internal.hpp"

namespace parallax {
namespace {

constexpr int kPatchTaps = 13;
constexpr int kPatchDx[kPatchTaps] = {0, -2, 2, 0, 0, -2, 2, -2, 2, -4, 4, 0, 0};
constexpr int kPatchDy[kPatchTaps] = {0, 0, 0, -2, 2, -2, -2, 2, 2, 0, 0, -4, 4};
constexpr int kPatchMargin = 5;

struct View {
    cv::Matx33d K;
    cv::Size size;
    double scale = 1.0;
};

View denseView(const CameraModel& c, int maxSide) {
    View v;
    const int capped = std::min(std::max(160, maxSide), 720);
    v.scale = std::min(1.0, capped / double(std::max(c.width, c.height)));
    v.size = cv::Size(int(std::lround(c.width * v.scale)), int(std::lround(c.height * v.scale)));
    v.K = {c.fx * v.scale, 0, (c.cx + 0.5) * v.scale - 0.5, 0, c.fy * v.scale, (c.cy + 0.5) * v.scale - 0.5, 0, 0, 1};
    return v;
}

cv::Mat scaledK(const CameraModel& c, double s) {
    return cv::Mat(cv::Matx33d(c.fx * s, 0, (c.cx + 0.5) * s - 0.5, 0, c.fy * s, (c.cy + 0.5) * s - 0.5, 0, 0, 1));
}

double percentile(std::vector<double> v, double q) {
    if (v.empty()) return 0;
    const size_t k = std::min(v.size() - 1, size_t(q * (v.size() - 1)));
    std::nth_element(v.begin(), v.begin() + k, v.end());
    return v[k];
}

struct XorShift32 {
    uint32_t s;
    explicit XorShift32(uint32_t seed) : s(seed ? seed : 2463534242u) {}
    inline uint32_t next() {
        uint32_t x = s;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        return s = x;
    }
    inline float uniform() { return (next() >> 8) * (1.0f / 16777216.0f); }
    inline float signedUniform() { return uniform() * 2.0f - 1.0f; }
};

struct SrcView {
    const float* gray = nullptr;
    int w = 0, h = 0;
    float R[9];     // R_src * R_ref^T
    float t[3];     // t_src - R * t_ref
    float fx = 1, fy = 1, cx = 0, cy = 0;
};

struct PlaneHyp {
    float z = 0.0f;
    float nx = 0.0f, ny = 0.0f, nz = -1.0f;
    float cost = 2.0f;
};

struct RefPatch {
    float val[kPatchTaps];
    float w[kPatchTaps];
    float mean = 0.0f;
    float invStd = 0.0f;
    float wSum = 0.0f;
    bool textured = false;
};

inline void buildRefPatch(const float* gray, int w, int x, int y, RefPatch& out) {
    const float c = gray[y * w + x];
    float wSum = 0.0f, sum = 0.0f;
    for (int k = 0; k < kPatchTaps; ++k) {
        const float v = gray[(y + kPatchDy[k]) * w + (x + kPatchDx[k])];
        const float wt = std::exp(-std::abs(v - c) * (1.0f / 14.0f));
        out.val[k] = v;
        out.w[k] = wt;
        wSum += wt;
        sum += wt * v;
    }
    const float mean = sum / std::max(wSum, 1e-6f);
    float var = 0.0f;
    for (int k = 0; k < kPatchTaps; ++k) {
        const float d = out.val[k] - mean;
        var += out.w[k] * d * d;
    }
    out.mean = mean;
    out.wSum = wSum;
    const float avgVar = var / std::max(wSum, 1e-6f);
    out.textured = (avgVar > 3.5f);
    out.invStd = var > 1e-4f ? 1.0f / std::sqrt(var) : 0.0f;
}

inline float sampleBilinear(const float* img, int w, int h, float u, float v) {
    const int x0 = int(std::floor(u));
    const int y0 = int(std::floor(v));
    if (x0 < 0 || y0 < 0 || x0 + 1 >= w || y0 + 1 >= h) return -1.0f;
    const float fx = u - x0;
    const float fy = v - y0;
    const float* r0 = img + y0 * w + x0;
    const float* r1 = r0 + w;
    return (r0[0] * (1.0f - fx) + r0[1] * fx) * (1.0f - fy) + (r1[0] * (1.0f - fx) + r1[1] * fx) * fy;
}

inline float evalHypothesis(
    float rx, float ry, float invFx, float invFy,
    float z, float nx, float ny, float nz,
    const RefPatch& rp,
    const std::vector<SrcView>& srcs,
    float laserSeedZ
) {
    if (!(z > 0.0f) || !std::isfinite(z)) return 2.0f;
    // Reference 3-D point X = z * (rx, ry, 1). Plane equation: n . X = d.
    const float d = z * (nx * rx + ny * ry + nz);
    if (d >= -1e-6f) return 2.0f;  // Plane must face camera (n . X < 0)
    const float invD = 1.0f / d;

    float costs[8];
    int validViews = 0;

    for (size_t s = 0; s < srcs.size() && validViews < 8; ++s) {
        const SrcView& sv = srcs[s];
        // M = R_rel + (t_rel * n^T) / d maps normalized ref ray r -> src camera vector.
        const float tx = sv.t[0] * invD, ty = sv.t[1] * invD, tz = sv.t[2] * invD;
        const float m00 = sv.R[0] + tx * nx, m01 = sv.R[1] + tx * ny, m02 = sv.R[2] + tx * nz;
        const float m10 = sv.R[3] + ty * nx, m11 = sv.R[4] + ty * ny, m12 = sv.R[5] + ty * nz;
        const float m20 = sv.R[6] + tz * nx, m21 = sv.R[7] + tz * ny, m22 = sv.R[8] + tz * nz;

        // Premultiply by K_src so q = A * r gives homogeneous pixel coordinates in source view.
        const float a00 = sv.fx * m00 + sv.cx * m20;
        const float a01 = sv.fx * m01 + sv.cx * m21;
        const float a02 = sv.fx * m02 + sv.cx * m22;
        const float a10 = sv.fy * m10 + sv.cy * m20;
        const float a11 = sv.fy * m11 + sv.cy * m21;
        const float a12 = sv.fy * m12 + sv.cy * m22;

        const float q0x = a00 * rx + a01 * ry + a02;
        const float q0y = a10 * rx + a11 * ry + a12;
        const float q0z = m20 * rx + m21 * ry + m22;
        if (q0z <= 1e-5f) continue;

        float svVal[kPatchTaps];
        float sSum = 0.0f;
        bool ok = true;
        for (int k = 0; k < kPatchTaps; ++k) {
            const float du = kPatchDx[k] * invFx;
            const float dv = kPatchDy[k] * invFy;
            const float qz = q0z + m20 * du + m21 * dv;
            if (qz <= 1e-5f) { ok = false; break; }
            const float invQz = 1.0f / qz;
            const float su = (q0x + a00 * du + a01 * dv) * invQz;
            const float svp = (q0y + a10 * du + a11 * dv) * invQz;
            const float g = sampleBilinear(sv.gray, sv.w, sv.h, su, svp);
            if (g < 0.0f) { ok = false; break; }
            svVal[k] = g;
            sSum += rp.w[k] * g;
        }
        if (!ok) continue;

        float viewCost;
        if (rp.textured) {
            const float sMean = sSum / std::max(rp.wSum, 1e-6f);
            float sVar = 0.0f, cov = 0.0f;
            for (int k = 0; k < kPatchTaps; ++k) {
                const float dr = rp.val[k] - rp.mean;
                const float ds = svVal[k] - sMean;
                const float wt = rp.w[k];
                sVar += wt * ds * ds;
                cov += wt * dr * ds;
            }
            if (sVar <= 1e-4f) {
                viewCost = 1.0f;
            } else {
                const float ncc = cov * rp.invStd / std::sqrt(sVar);
                viewCost = std::clamp(1.0f - ncc, 0.0f, 2.0f);
            }
        } else {
            // Weakly textured patch: soft SAD fallback (still lets laser-seeded planes win cleanly).
            float sad = 0.0f;
            for (int k = 0; k < kPatchTaps; ++k) sad += rp.w[k] * std::abs(svVal[k] - rp.val[k]);
            viewCost = std::min(1.5f, (sad / std::max(rp.wSum, 1e-6f)) * (1.0f / 25.0f) + 0.35f);
        }
        costs[validViews++] = viewCost;
    }

    if (validViews == 0) return 2.0f;
    // Top-K occlusion-aware aggregation (average best 2 views when >= 2 see this patch).
    std::sort(costs, costs + validViews);
    float agg = validViews >= 2 ? 0.5f * (costs[0] + costs[1]) : (costs[0] + 0.12f);

    // Gentle metric laser-consistency prior wherever a calibrated laser stripe hit this pixel.
    if (laserSeedZ > 0.0f) {
        const float rel = std::abs(z - laserSeedZ) / std::max(laserSeedZ, 1e-4f);
        agg += 0.35f * std::min(1.0f, rel * 35.0f);
    }
    return agg;
}

inline bool projectNeighborPlane(
    float rx, float ry, float nbx, float nby, const PlaneHyp& nb,
    float zMin, float zMax, float& zOut
) {
    if (nb.z <= 0.0f) return false;
    const float d = nb.z * (nb.nx * nbx + nb.ny * nby + nb.nz);
    const float denom = nb.nx * rx + nb.ny * ry + nb.nz;
    if (denom >= -0.12f || d >= -1e-6f) return false;
    const float zp = d / denom;
    if (zp < zMin || zp > zMax || !std::isfinite(zp)) return false;
    zOut = zp;
    return true;
}

inline void normalizeFrontNormal(float& nx, float& ny, float& nz, float rx, float ry) {
    float len = std::sqrt(nx * nx + ny * ny + nz * nz);
    if (len < 1e-6f) { nx = 0.0f; ny = 0.0f; nz = -1.0f; return; }
    nx /= len; ny /= len; nz /= len;
    if (nx * rx + ny * ry + nz > -0.15f) {
        // Flip or pull gently toward camera view ray so plane never turns grazing/back-facing.
        if (nz > 0.0f) { nx = -nx; ny = -ny; nz = -nz; }
        if (nx * rx + ny * ry + nz > -0.15f) {
            nz -= 0.45f;
            len = std::sqrt(nx * nx + ny * ny + nz * nz);
            nx /= len; ny /= len; nz /= len;
        }
    }
}

// Parses outputDir/hybrid/hybrid.json to map image filename ("img_00012.jpg") -> relative laser path.
std::unordered_map<std::string, std::string> parseHybridLaserMap(const std::string& outputDir) {
    std::unordered_map<std::string, std::string> out;
    if (outputDir.empty()) return out;
    std::ifstream in(outputDir + "/hybrid/hybrid.json");
    if (!in) return out;
    std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    auto findStrField = [](const std::string& obj, const char* key) -> std::string {
        const std::string pat = std::string("\"") + key + "\"";
        size_t pos = obj.find(pat);
        if (pos == std::string::npos) return {};
        pos = obj.find(':', pos + pat.size());
        if (pos == std::string::npos) return {};
        pos = obj.find('"', pos + 1);
        if (pos == std::string::npos) return {};
        size_t end = obj.find('"', pos + 1);
        if (end == std::string::npos) return {};
        return obj.substr(pos + 1, end - pos - 1);
    };
    size_t pos = text.find("\"images\"");
    if (pos == std::string::npos) return out;
    size_t arrStart = text.find('[', pos);
    if (arrStart == std::string::npos) return out;
    size_t cur = arrStart + 1;
    while (cur < text.size()) {
        size_t ob = text.find('{', cur);
        size_t cb = text.find(']', cur);
        if (ob == std::string::npos || (cb != std::string::npos && cb < ob)) break;
        size_t oe = text.find('}', ob);
        if (oe == std::string::npos) break;
        const std::string obj = text.substr(ob, oe - ob + 1);
        const std::string file = findStrField(obj, "file");
        const std::string laser = findStrField(obj, "laser");
        if (!file.empty() && !laser.empty()) out[file] = outputDir + "/" + laser;
        cur = oe + 1;
    }
    return out;
}

std::string basenameOf(const std::string& p) {
    size_t s = p.find_last_of("/\\");
    return s == std::string::npos ? p : p.substr(s + 1);
}

std::vector<cv::Vec3f> loadLaserBinMetres(const std::string& path) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) return {};
    const std::streamsize bytes = in.tellg();
    if (bytes < 12 || bytes > 12 * 200000) return {};
    in.seekg(0, std::ios::beg);
    const size_t n = size_t(bytes) / 12;
    std::vector<float> raw(n * 3);
    in.read(reinterpret_cast<char*>(raw.data()), std::streamsize(n * 12));
    std::vector<cv::Vec3f> pts;
    pts.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        const float x = raw[i * 3] * 0.001f;
        const float y = raw[i * 3 + 1] * 0.001f;
        const float z = raw[i * 3 + 2] * 0.001f;
        if (std::isfinite(x) && std::isfinite(y) && std::isfinite(z) && z > 0.03f && z < 4.5f)
            pts.emplace_back(x, y, z);
    }
    return pts;
}

struct CachedGray {
    View view;
    cv::Mat gray32f;  // undistorted CV_32F
    cv::Mat color8u;  // undistorted CV_8UC3 (populated for reference views)
};

struct RefResult {
    int imageIdx = -1;
    View view;
    cv::Mat depth;      // CV_32F, 0 = invalid
    cv::Mat normal;     // CV_8SC3 (signed unit normal * 127 in camera frame)
    cv::Mat cost;       // CV_32F
    cv::Mat laserMask;  // CV_8U (1 where laser-seeded)
    cv::Mat color8u;    // CV_8UC3 undistorted
    std::vector<int> neighbors;  // indices into refs[]
};

struct Voxel {
    float X[3] = {0, 0, 0}, n[3] = {0, 0, 0}, c[3] = {0, 0, 0};
    int count = 0, views = 0, lastView = -1;
};

}  // namespace

std::vector<DensePoint> denseReconstruct(const State& s, const ProgressFn& progress, float p0, float p1,
                                         const std::string& outputDir) {
    std::vector<int> reg;
    for (size_t i = 0; i < s.images.size(); ++i)
        if (s.images[i].registered) reg.push_back(int(i));
    if (reg.size() < 2) return {};

    // Covisibility table from valid sparse tracks.
    std::vector<std::vector<int>> seen(s.images.size());
    for (size_t j = 0; j < s.points.size(); ++j)
        if (s.points[j].valid)
            for (const auto& o : s.points[j].obs) seen[o.image].push_back(int(j));
    for (auto& v : seen) {
        std::sort(v.begin(), v.end());
        v.erase(std::unique(v.begin(), v.end()), v.end());
    }

    std::vector<int> refs;
    const int maxRefs = std::max(2, s.opt.maxDenseImages);
    for (int k = 0; k < int(reg.size()); ++k)
        if (int(reg.size()) <= maxRefs || (k * maxRefs) / int(reg.size()) != ((k - 1) * maxRefs) / int(reg.size()))
            refs.push_back(reg[k]);

    std::unordered_map<int, int> imgToRef;
    for (int r = 0; r < int(refs.size()); ++r) imgToRef[refs[r]] = r;

    std::vector<double> radii;
    for (const auto& p : s.points) if (p.valid) radii.push_back(cv::norm(p.X));
    const double maxRadius = 3.0 * std::max(0.1, percentile(radii, 0.95));

    // Optional laser depth seeds from hybrid/hybrid.json + global BA-units-per-metre ratio.
    const auto laserPaths = parseHybridLaserMap(outputDir);
    std::unordered_map<int, std::vector<cv::Vec3f>> laserCamPts;
    double baPerMetre = 0.0;
    if (!laserPaths.empty()) {
        std::vector<double> ratios;
        for (int ri : refs) {
            const auto it = laserPaths.find(basenameOf(s.images[ri].input.path));
            if (it == laserPaths.end()) continue;
            auto pts = loadLaserBinMetres(it->second);
            if (pts.empty()) continue;
            const Image& A = s.images[ri];
            const CameraModel& ca = s.cameras[A.camera];
            const View rv = denseView(ca, s.opt.denseMaxSide);
            const double fx = rv.K(0, 0), fy = rv.K(1, 1), ux = rv.K(0, 2), uy = rv.K(1, 2);
            // Bin laser depths onto a coarse 8-px grid to estimate BA-units per metre robustly.
            const int gw = (rv.size.width + 7) / 8, gh = (rv.size.height + 7) / 8;
            std::vector<float> lzBin(size_t(gw) * gh, 0.0f);
            for (const auto& lp : pts) {
                const int px = int(std::lround(fx * lp[0] / lp[2] + ux));
                const int py = int(std::lround(fy * lp[1] / lp[2] + uy));
                if (px >= 0 && px < rv.size.width && py >= 0 && py < rv.size.height)
                    lzBin[size_t(py / 8) * gw + (px / 8)] = lp[2];
            }
            for (int pid : seen[ri]) {
                const cv::Vec3d pc = A.R * s.points[pid].X + A.t;
                if (pc[2] <= 1e-4) continue;
                const int px = int(std::lround(fx * pc[0] / pc[2] + ux));
                const int py = int(std::lround(fy * pc[1] / pc[2] + uy));
                if (px < 0 || px >= rv.size.width || py < 0 || py >= rv.size.height) continue;
                const float lz = lzBin[size_t(py / 8) * gw + (px / 8)];
                if (lz > 0.05f) ratios.push_back(pc[2] / double(lz));
            }
            laserCamPts.emplace(ri, std::move(pts));
        }
        if (ratios.size() >= 15) baPerMetre = percentile(ratios, 0.5);
    }

    // Undistorted grayscale image cache (kept compact by capping to 24 most recent images).
    std::unordered_map<int, CachedGray> grayCache;
    std::vector<int> cacheOrder;
    auto getUndistorted = [&](int idx, bool needColor) -> const CachedGray* {
        auto it = grayCache.find(idx);
        if (it != grayCache.end() && (!needColor || !it->second.color8u.empty())) return &it->second;
        const Image& im = s.images[idx];
        const CameraModel& cm = s.cameras[im.camera];
        const View vw = denseView(cm, s.opt.denseMaxSide);
        cv::Mat full = cv::imread(im.input.path,
                                  (needColor ? cv::IMREAD_COLOR : cv::IMREAD_GRAYSCALE) | cv::IMREAD_IGNORE_ORIENTATION);
        if (full.empty()) return nullptr;
        cv::Mat resized;
        cv::resize(full, resized, vw.size, 0, 0, cv::INTER_AREA);
        cv::Mat undist, gray8, gray32;
        cv::undistort(resized, undist, scaledK(cm, vw.scale), distortionVector(cm), cv::Mat(vw.K));
        CachedGray entry;
        entry.view = vw;
        if (needColor) {
            entry.color8u = undist;
            cv::cvtColor(undist, gray8, cv::COLOR_BGR2GRAY);
        } else {
            gray8 = undist;
        }
        gray8.convertTo(gray32, CV_32F);
        entry.gray32f = gray32;
        if (grayCache.size() >= 24 && !cacheOrder.empty()) {
            grayCache.erase(cacheOrder.front());
            cacheOrder.erase(cacheOrder.begin());
        }
        auto res = grayCache.emplace(idx, std::move(entry));
        if (!res.second) res.first->second = std::move(entry);
        else cacheOrder.push_back(idx);
        return &res.first->second;
    };

    const float pMatchEnd = p0 + 0.80f * (p1 - p0);
    std::vector<RefResult> refResults(refs.size());

    // ── Stage 1: Multi-view PatchMatch Stereo per reference view ──
    for (size_t r = 0; r < refs.size(); ++r) {
        if (progress && !progress("PatchMatch MVS", p0 + (pMatchEnd - p0) * float(r) / refs.size())) return {};
        const int ri = refs[r];
        const Image& A = s.images[ri];
        const cv::Vec3d Ca = A.centre();
        const cv::Vec3d axis(A.R(2, 0), A.R(2, 1), A.R(2, 2));

        // Rank covisible neighbors by shared tracks and ideal ~12 deg triangulation angle.
        struct Cand { double score; int idx; cv::Vec3d dir; };
        std::vector<Cand> cands;
        for (int nj : reg) {
            if (nj == ri) continue;
            std::vector<int> sh;
            std::set_intersection(seen[ri].begin(), seen[ri].end(), seen[nj].begin(), seen[nj].end(),
                                  std::back_inserter(sh));
            if (sh.size() < 24) continue;
            const cv::Vec3d Cb = s.images[nj].centre();
            const cv::Vec3d b = Cb - Ca;
            const double bl = cv::norm(b);
            if (bl < 1e-12) continue;
            const double lateral = std::sqrt(std::max(0.0, 1.0 - std::pow(b.dot(axis) / bl, 2)));
            if (lateral < 0.35) continue;
            std::vector<double> ang;
            for (size_t q = 0; q < sh.size(); q += std::max<size_t>(1, sh.size() / 150))
                ang.push_back(triangulationAngleDeg(s.points[sh[q]].X, Ca, Cb));
            const double a = percentile(ang, 0.5);
            if (a < 2.0 || a > 45.0) continue;
            const double sc = double(sh.size()) * std::exp(-std::pow((a - 12.0) / 10.0, 2));
            cands.push_back({sc, nj, b / bl});
        }
        std::sort(cands.begin(), cands.end(), [](const Cand& a, const Cand& b) { return a.score > b.score; });
        if (cands.empty()) continue;

        // Pick up to 5 neighbors with baseline diversity (prefer both sides / different viewpoints).
        std::vector<int> chosen;
        std::vector<cv::Vec3d> chosenDirs;
        for (const auto& c : cands) {
            if (chosen.size() >= 5) break;
            bool redundant = false;
            if (chosen.size() >= 2) {
                for (const auto& d : chosenDirs) {
                    if (d.dot(c.dir) > 0.96) { redundant = true; break; }
                }
            }
            if (redundant) continue;
            chosen.push_back(c.idx);
            chosenDirs.push_back(c.dir);
        }
        for (const auto& c : cands) {
            if (chosen.size() >= 5) break;
            if (std::find(chosen.begin(), chosen.end(), c.idx) == chosen.end()) chosen.push_back(c.idx);
        }
        if (chosen.empty()) continue;

        const CachedGray* refData = getUndistorted(ri, true);
        if (!refData) continue;
        const View rv = refData->view;
        const int W = rv.size.width, H = rv.size.height;
        const float fx = float(rv.K(0, 0)), fy = float(rv.K(1, 1)), ux = float(rv.K(0, 2)), uy = float(rv.K(1, 2));
        const float invFx = 1.0f / fx, invFy = 1.0f / fy;
        cv::Mat refGray = refData->gray32f.clone();
        cv::Mat refColor = refData->color8u.clone();

        std::vector<cv::Mat> srcMats;
        std::vector<SrcView> srcs;
        srcMats.reserve(chosen.size());
        srcs.reserve(chosen.size());
        std::vector<int> neighborRefs;
        for (int nj : chosen) {
            const CachedGray* sd = getUndistorted(nj, false);
            if (!sd) continue;
            srcMats.push_back(sd->gray32f.clone());
            const Image& B = s.images[nj];
            const cv::Matx33d Rrel = B.R * A.R.t();
            const cv::Vec3d trel = B.t - Rrel * A.t;
            SrcView sv;
            sv.gray = srcMats.back().ptr<float>(0);
            sv.w = sd->view.size.width;
            sv.h = sd->view.size.height;
            for (int i = 0; i < 9; ++i) sv.R[i] = float(Rrel.val[i]);
            for (int i = 0; i < 3; ++i) sv.t[i] = float(trel[i]);
            sv.fx = float(sd->view.K(0, 0));
            sv.fy = float(sd->view.K(1, 1));
            sv.cx = float(sd->view.K(0, 2));
            sv.cy = float(sd->view.K(1, 2));
            srcs.push_back(sv);
            auto itRef = imgToRef.find(nj);
            if (itRef != imgToRef.end()) neighborRefs.push_back(itRef->second);
        }
        // Also ensure geometric consistency has up to 6 covisible reference views even when reg.size() > maxRefs.
        for (const auto& c : cands) {
            if (neighborRefs.size() >= 6) break;
            auto itRef = imgToRef.find(c.idx);
            if (itRef != imgToRef.end() &&
                std::find(neighborRefs.begin(), neighborRefs.end(), itRef->second) == neighborRefs.end()) {
                neighborRefs.push_back(itRef->second);
            }
        }
        if (srcs.empty()) continue;

        // Depth bounds from sparse observations in reference frame.
        std::vector<double> sparseZ;
        sparseZ.reserve(seen[ri].size());
        for (int pid : seen[ri]) {
            const cv::Vec3d pc = A.R * s.points[pid].X + A.t;
            if (pc[2] > 1e-4) sparseZ.push_back(pc[2]);
        }
        if (sparseZ.size() < 12) continue;
        const float zMin = float(std::max(1e-3, percentile(sparseZ, 0.02) * 0.60));
        const float zMax = float(percentile(sparseZ, 0.98) * 1.45);
        if (!(zMax > zMin * 1.05f)) continue;

        // Precompute reference patch descriptors (parallel across rows).
        const size_t nPix = size_t(W) * H;
        std::vector<RefPatch> patches(nPix);
        const float* refPtr = refGray.ptr<float>(0);
        cv::parallel_for_(cv::Range(kPatchMargin, H - kPatchMargin), [&](const cv::Range& range) {
            for (int y = range.start; y < range.end; ++y) {
                for (int x = kPatchMargin; x < W - kPatchMargin; ++x)
                    buildRefPatch(refPtr, W, x, y, patches[size_t(y) * W + x]);
            }
        });

        // Build optional laser depth seed map for this reference view.
        cv::Mat laserSeed(H, W, CV_32F, cv::Scalar(0));
        cv::Mat laserMask(H, W, CV_8U, cv::Scalar(0));
        if (baPerMetre > 0.0) {
            auto itL = laserCamPts.find(ri);
            if (itL != laserCamPts.end()) {
                for (const auto& lp : itL->second) {
                    const float zBa = float(double(lp[2]) * baPerMetre);
                    if (zBa < zMin || zBa > zMax) continue;
                    const int px = int(std::lround(fx * lp[0] / lp[2] + ux));
                    const int py = int(std::lround(fy * lp[1] / lp[2] + uy));
                    for (int dy = -2; dy <= 2; ++dy) {
                        const int yy = py + dy;
                        if (yy < kPatchMargin || yy >= H - kPatchMargin) continue;
                        for (int dx = -2; dx <= 2; ++dx) {
                            const int xx = px + dx;
                            if (xx < kPatchMargin || xx >= W - kPatchMargin) continue;
                            laserSeed.at<float>(yy, xx) = zBa;
                            laserMask.at<uint8_t>(yy, xx) = 1;
                        }
                    }
                }
            }
        }
        const float* laserSeedPtr = laserSeed.ptr<float>(0);

        // Initialize plane hypotheses randomly (front-facing hemisphere) + sparse & laser seeds.
        std::vector<PlaneHyp> hyps(nPix);
        cv::parallel_for_(cv::Range(kPatchMargin, H - kPatchMargin), [&](const cv::Range& range) {
            for (int y = range.start; y < range.end; ++y) {
                XorShift32 rng(uint32_t((ri + 1) * 73856093u ^ (y + 1) * 19349663u));
                const float ry = (y - uy) * invFy;
                for (int x = kPatchMargin; x < W - kPatchMargin; ++x) {
                    const float rx = (x - ux) * invFx;
                    const size_t idx = size_t(y) * W + x;
                    PlaneHyp best;
                    const float lsz = laserSeedPtr[idx];
                    // If laser-seeded, test the laser depth first with a front-facing normal.
                    if (lsz > 0.0f) {
                        float nx = 0.0f, ny = 0.0f, nz = -1.0f;
                        normalizeFrontNormal(nx, ny, nz, rx, ry);
                        const float c = evalHypothesis(rx, ry, invFx, invFy, lsz, nx, ny, nz, patches[idx], srcs, lsz);
                        best = {lsz, nx, ny, nz, c};
                    }
                    // Two stratified random initial plane hypotheses across [zMin, zMax].
                    for (int k = 0; k < 2; ++k) {
                        const float u = (k + rng.uniform()) * 0.5f;
                        const float z = 1.0f / ((1.0f - u) / zMin + u / zMax);
                        float nx = rng.signedUniform() * 0.55f;
                        float ny = rng.signedUniform() * 0.55f;
                        float nz = -1.0f;
                        normalizeFrontNormal(nx, ny, nz, rx, ry);
                        const float c = evalHypothesis(rx, ry, invFx, invFy, z, nx, ny, nz, patches[idx], srcs, lsz);
                        if (c < best.cost) best = {z, nx, ny, nz, c};
                    }
                    hyps[idx] = best;
                }
            }
        });

        // Splat sparse SfM 3-D points as exact initial depth seeds (5x5 window).
        for (int pid : seen[ri]) {
            const cv::Vec3d pc = A.R * s.points[pid].X + A.t;
            const float z = float(pc[2]);
            if (z < zMin || z > zMax) continue;
            const int px = int(std::lround(fx * pc[0] / pc[2] + ux));
            const int py = int(std::lround(fy * pc[1] / pc[2] + uy));
            for (int dy = -2; dy <= 2; ++dy) {
                const int yy = py + dy;
                if (yy < kPatchMargin || yy >= H - kPatchMargin) continue;
                const float ry = (yy - uy) * invFy;
                for (int dx = -2; dx <= 2; ++dx) {
                    const int xx = px + dx;
                    if (xx < kPatchMargin || xx >= W - kPatchMargin) continue;
                    const float rx = (xx - ux) * invFx;
                    const size_t idx = size_t(yy) * W + xx;
                    float nx = 0.0f, ny = 0.0f, nz = -1.0f;
                    normalizeFrontNormal(nx, ny, nz, rx, ry);
                    const float c = evalHypothesis(rx, ry, invFx, invFy, z, nx, ny, nz, patches[idx], srcs, laserSeedPtr[idx]);
                    if (c < hyps[idx].cost) hyps[idx] = {z, nx, ny, nz, c};
                }
            }
        }

        // 3 Red-Black checkerboard PatchMatch iterations (ACMH 8-neighbor propagation + refinement).
        constexpr int kPropDx[8] = {-1, 1, 0, 0, -5, 5, 0, 0};
        constexpr int kPropDy[8] = {0, 0, -1, 1, 0, 0, -5, 5};
        const float dzFrac[3] = {0.14f, 0.045f, 0.015f};
        const float dnSpan[3] = {0.45f, 0.18f, 0.06f};

        for (int iter = 0; iter < 3; ++iter) {
            for (int parity = 0; parity < 2; ++parity) {
                cv::parallel_for_(cv::Range(kPatchMargin, H - kPatchMargin), [&](const cv::Range& range) {
                    for (int y = range.start; y < range.end; ++y) {
                        XorShift32 rng(uint32_t((ri + 1) * 2654435761u ^ (iter * 2 + parity + 1) * 2246822519u ^
                                                (y + 1) * 3266489917u));
                        const float ry = (y - uy) * invFy;
                        int xStart = kPatchMargin;
                        if (((xStart + y) & 1) != parity) ++xStart;
                        for (int x = xStart; x < W - kPatchMargin; x += 2) {
                            const float rx = (x - ux) * invFx;
                            const size_t idx = size_t(y) * W + x;
                            PlaneHyp best = hyps[idx];
                            const RefPatch& rp = patches[idx];
                            const float lsz = laserSeedPtr[idx];

                            // Spatial plane propagation from 4-connected + long-range 5-px neighbors.
                            for (int p = 0; p < 8; ++p) {
                                const int nxPix = x + kPropDx[p];
                                const int nyPix = y + kPropDy[p];
                                if (nxPix < kPatchMargin || nxPix >= W - kPatchMargin ||
                                    nyPix < kPatchMargin || nyPix >= H - kPatchMargin) continue;
                                const PlaneHyp& nb = hyps[size_t(nyPix) * W + nxPix];
                                if (nb.cost >= 1.85f) continue;
                                float zProp = 0.0f;
                                if (!projectNeighborPlane(rx, ry, (nxPix - ux) * invFx, (nyPix - uy) * invFy, nb,
                                                          zMin, zMax, zProp)) continue;
                                const float c = evalHypothesis(rx, ry, invFx, invFy, zProp, nb.nx, nb.ny, nb.nz,
                                                               rp, srcs, lsz);
                                if (c < best.cost) best = {zProp, nb.nx, nb.ny, nb.nz, c};
                            }

                            // Local random perturbation of (z, n) around current best.
                            for (int t = 0; t < 2; ++t) {
                                const float zCand = best.z * (1.0f + rng.signedUniform() * dzFrac[iter]);
                                if (zCand < zMin || zCand > zMax) continue;
                                float nx = best.nx + rng.signedUniform() * dnSpan[iter];
                                float ny = best.ny + rng.signedUniform() * dnSpan[iter];
                                float nz = best.nz + rng.signedUniform() * dnSpan[iter];
                                normalizeFrontNormal(nx, ny, nz, rx, ry);
                                const float c = evalHypothesis(rx, ry, invFx, invFy, zCand, nx, ny, nz, rp, srcs, lsz);
                                if (c < best.cost) best = {zCand, nx, ny, nz, c};
                            }
                            hyps[idx] = best;
                        }
                    }
                });
            }
        }

        // Pack depth, quantized PatchMatch normals, and costs for geometric consistency fusion.
        RefResult rr;
        rr.imageIdx = ri;
        rr.view = rv;
        rr.depth = cv::Mat(H, W, CV_32F, cv::Scalar(0));
        rr.normal = cv::Mat(H, W, CV_8SC3, cv::Scalar(0, 0, -127));
        rr.cost = cv::Mat(H, W, CV_32F, cv::Scalar(2.0f));
        rr.laserMask = laserMask;
        rr.color8u = refColor;
        rr.neighbors = neighborRefs;

        for (int y = kPatchMargin; y < H - kPatchMargin; ++y) {
            float* dRow = rr.depth.ptr<float>(y);
            float* cRow = rr.cost.ptr<float>(y);
            cv::Vec3b* nRow = rr.normal.ptr<cv::Vec3b>(y);
            for (int x = kPatchMargin; x < W - kPatchMargin; ++x) {
                const PlaneHyp& h = hyps[size_t(y) * W + x];
                const bool isLaser = laserMask.at<uint8_t>(y, x) != 0;
                const float maxCost = isLaser ? 0.72f : 0.48f;
                if (h.z > zMin && h.z < zMax && h.cost < maxCost) {
                    dRow[x] = h.z;
                    cRow[x] = h.cost;
                    nRow[x] = cv::Vec3b(
                        static_cast<uint8_t>(static_cast<int8_t>(std::clamp(int(std::lround(h.nx * 127.0f)), -127, 127))),
                        static_cast<uint8_t>(static_cast<int8_t>(std::clamp(int(std::lround(h.ny * 127.0f)), -127, 127))),
                        static_cast<uint8_t>(static_cast<int8_t>(std::clamp(int(std::lround(h.nz * 127.0f)), -127, 127))));
                }
            }
        }
        cv::medianBlur(rr.depth, rr.depth, 3);
        refResults[r] = std::move(rr);
    }

    // ── Stage 2: Multi-view geometric depth + normal consistency fusion ──
    if (progress && !progress("Geometric fusion", pMatchEnd)) return {};

    // Determine voxel size from median depth (~1.3 dense pixels).
    double voxel = 0.0;
    {
        std::vector<double> zSamples;
        for (const auto& rr : refResults) {
            if (rr.depth.empty()) continue;
            for (int y = kPatchMargin; y < rr.depth.rows - kPatchMargin; y += 6)
                for (int x = kPatchMargin; x < rr.depth.cols - kPatchMargin; x += 6) {
                    const float z = rr.depth.at<float>(y, x);
                    if (z > 0.0f) zSamples.push_back(double(z) / rr.view.K(0, 0));
                }
            if (zSamples.size() > 2000) break;
        }
        if (zSamples.empty()) return {};
        voxel = 1.3 * percentile(zSamples, 0.5);
        if (voxel <= 1e-6) return {};
    }

    std::unordered_map<uint64_t, int> index;
    std::vector<Voxel> voxels;
    const int minConsistent = refs.size() >= 3 ? 2 : 1;

    for (size_t r = 0; r < refResults.size(); ++r) {
        if (progress && !progress("Geometric fusion", pMatchEnd + (p1 - pMatchEnd) * float(r) / refResults.size()))
            return {};
        const RefResult& rr = refResults[r];
        if (rr.depth.empty()) continue;
        const Image& A = s.images[rr.imageIdx];
        const cv::Matx33d Rt = A.R.t();
        const double fx = rr.view.K(0, 0), fy = rr.view.K(1, 1), ux = rr.view.K(0, 2), uy = rr.view.K(1, 2);

        for (int y = kPatchMargin; y < rr.depth.rows - kPatchMargin; ++y) {
            const float* dRow = rr.depth.ptr<float>(y);
            const cv::Vec3b* nRow = rr.normal.ptr<cv::Vec3b>(y);
            const uint8_t* lmRow = rr.laserMask.ptr<uint8_t>(y);
            const cv::Vec3b* cRow = rr.color8u.ptr<cv::Vec3b>(y);
            for (int x = kPatchMargin; x < rr.depth.cols - kPatchMargin; ++x) {
                const float z = dRow[x];
                if (z <= 0.0f) continue;

                const cv::Vec3d P((x - ux) * z / fx, (y - uy) * z / fy, z);
                const cv::Vec3b nq = nRow[x];
                cv::Vec3d nc(static_cast<int8_t>(nq[0]) / 127.0,
                             static_cast<int8_t>(nq[1]) / 127.0,
                             static_cast<int8_t>(nq[2]) / 127.0);
                const double nl = cv::norm(nc);
                if (nl < 0.25) continue;
                nc /= nl;
                if (nc.dot(P) > 0.0) nc = -nc;
                if (-nc.dot(P) / std::max(cv::norm(P), 1e-6) < 0.15) continue;  // grazing

                const cv::Vec3d Xw = Rt * (P - A.t);
                if (cv::norm(Xw) > maxRadius) continue;
                const cv::Vec3d nw = Rt * nc;

                // Cross-check 3-D point + normal against neighbor reference PatchMatch maps.
                int agree = 1;
                cv::Vec3d xAccum = Xw;
                cv::Vec3d nAccum = nw;
                for (int r2 : rr.neighbors) {
                    const RefResult& nb = refResults[r2];
                    if (nb.depth.empty()) continue;
                    const Image& B = s.images[nb.imageIdx];
                    const cv::Vec3d Pb = B.R * Xw + B.t;
                    if (Pb[2] <= 1e-4) continue;
                    const int bx = int(std::lround(nb.view.K(0, 0) * Pb[0] / Pb[2] + nb.view.K(0, 2)));
                    const int by = int(std::lround(nb.view.K(1, 1) * Pb[1] / Pb[2] + nb.view.K(1, 2)));
                    if (bx < kPatchMargin || bx >= nb.depth.cols - kPatchMargin ||
                        by < kPatchMargin || by >= nb.depth.rows - kPatchMargin) continue;
                    const float zb = nb.depth.at<float>(by, bx);
                    if (zb <= 0.0f) continue;
                    if (std::abs(zb - float(Pb[2])) > 0.018f * float(Pb[2])) continue;
                    const cv::Vec3b nbq = nb.normal.at<cv::Vec3b>(by, bx);
                    cv::Vec3d nbc(static_cast<int8_t>(nbq[0]) / 127.0,
                                  static_cast<int8_t>(nbq[1]) / 127.0,
                                  static_cast<int8_t>(nbq[2]) / 127.0);
                    const double nbl = cv::norm(nbc);
                    if (nbl < 0.25) continue;
                    const cv::Vec3d nbw = B.R.t() * (nbc / nbl);
                    if (nw.dot(nbw) < 0.65) continue;  // > ~50 deg normal disagreement
                    ++agree;
                    nAccum += nbw;
                }
                const int reqAgree = (lmRow[x] != 0) ? 1 : minConsistent;
                if (agree < reqAgree) continue;

                const double nLen = cv::norm(nAccum);
                if (nLen < 1e-6) continue;
                const cv::Vec3d nFinal = nAccum / nLen;
                const int64_t ix = int64_t(std::floor(xAccum[0] / voxel));
                const int64_t iy = int64_t(std::floor(xAccum[1] / voxel));
                const int64_t iz = int64_t(std::floor(xAccum[2] / voxel));
                const uint64_t key =
                    (uint64_t(ix & 0x1FFFFF) << 42) | (uint64_t(iy & 0x1FFFFF) << 21) | uint64_t(iz & 0x1FFFFF);
                auto it = index.find(key);
                int vi;
                if (it == index.end()) {
                    vi = int(voxels.size());
                    index.emplace(key, vi);
                    voxels.emplace_back();
                } else {
                    vi = it->second;
                }
                Voxel& vx = voxels[vi];
                const cv::Vec3b c = cRow[x];
                for (int q = 0; q < 3; ++q) {
                    vx.X[q] += float(xAccum[q]);
                    vx.n[q] += float(nFinal[q]);
                }
                vx.c[0] += c[2];
                vx.c[1] += c[1];
                vx.c[2] += c[0];
                vx.count++;
                if (vx.lastView != int(r)) { vx.views++; vx.lastView = int(r); }
            }
        }
    }

    const int minVoxelViews = refs.size() >= 3 ? 2 : 1;
    std::vector<DensePoint> out;
    out.reserve(voxels.size());
    for (const auto& v : voxels) {
        if (v.views < minVoxelViews) continue;
        const float inv = 1.0f / v.count;
        DensePoint p;
        p.X = cv::Vec3f(v.X[0] * inv, v.X[1] * inv, v.X[2] * inv);
        p.n = cv::normalize(cv::Vec3f(v.n[0], v.n[1], v.n[2]));
        p.rgb = cv::Vec3b(cv::saturate_cast<uchar>(v.c[0] * inv), cv::saturate_cast<uchar>(v.c[1] * inv),
                          cv::saturate_cast<uchar>(v.c[2] * inv));
        out.push_back(p);
    }
    return out;
}

}  // namespace parallax
