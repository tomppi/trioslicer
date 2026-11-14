// Parallax: dense multi-view stereo.
//
// For each reference image the two best neighbours are chosen from the sparse model
// (shared points, triangulation angle near 10 degrees, sideways baseline). Each pair is
// rectified (Fusiello/Bouguet, cv::stereoRectify) and matched with Semi-Global Matching
// (Hirschmueller 2008, cv::StereoSGBM) over a disparity range bounded by the sparse points.
// The two depth maps are cross-checked, normals come from the depth-map gradient, and all
// reference views are fused in a voxel grid that keeps surfaces seen from two or more views.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <opencv2/calib3d.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <unordered_map>

#include "internal.hpp"

namespace parallax {
namespace {

struct View {
    cv::Matx33d K;  // dense-resolution, undistorted
    cv::Size size;
    double scale;
};

View denseView(const CameraModel& c, int maxSide) {
    View v;
    v.scale = std::min(1.0, maxSide / double(std::max(c.width, c.height)));
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

// Depth map of the reference view (undistorted, dense resolution) from one rectified pair.
cv::Mat pairDepth(const State& s, int ri, int ni, const std::vector<int>& shared, const cv::Mat& refImg,
                  const cv::Mat& nbImg, const View& rv) {
    const Image& A = s.images[ri];
    const Image& B = s.images[ni];
    const CameraModel& ca = s.cameras[A.camera];
    const CameraModel& cb = s.cameras[B.camera];
    const double sb = std::min(1.0, s.opt.denseMaxSide / double(std::max(cb.width, cb.height)));
    const cv::Mat Ka = scaledK(ca, rv.scale), Kb = scaledK(cb, sb);
    const cv::Matx33d Rrel = B.R * A.R.t();
    const cv::Vec3d trel = B.t - Rrel * A.t;
    cv::Mat R1, R2, P1, P2, Q;
    // alpha = 1 keeps every source pixel (alpha = 0 can zoom in many times on rotated pairs);
    // the rectified grid is 25 % larger to keep the resolution that zooming out costs.
    const cv::Size rs(int(rv.size.width * 1.25), int(rv.size.height * 1.25));
    cv::stereoRectify(Ka, distortionVector(ca), Kb, distortionVector(cb), rv.size, cv::Mat(Rrel), cv::Mat(trel), R1, R2,
                      P1, P2, Q, cv::CALIB_ZERO_DISPARITY, 1.0, rs);
    const double f = P1.at<double>(0, 0), cx = P1.at<double>(0, 2), cy = P1.at<double>(1, 2);
    const bool vertical = std::abs(P2.at<double>(1, 3)) > std::abs(P2.at<double>(0, 3));
    const double Tf = vertical ? P2.at<double>(1, 3) : P2.at<double>(0, 3);
    const double baseline = std::abs(Tf) / f;
    if (baseline < 1e-9) return {};

    // Disparity range from the sparse points both views share.
    const cv::Matx33d R1m(R1);
    std::vector<double> disp;
    for (int pid : shared) {
        const cv::Vec3d pr = R1m * (A.R * s.points[pid].X + A.t);
        if (pr[2] > 1e-9) disp.push_back(f * baseline / pr[2]);
    }
    if (disp.size() < 15) return {};
    const double dmin = std::max(0.0, percentile(disp, 0.02) * 0.75 - 2);
    const double dmax = percentile(disp, 0.98) * 1.3 + 2;
    int minD = int(std::floor(dmin));
    int numD = ((int(std::ceil(dmax)) - minD + 15) / 16) * 16;
    if (numD > 320) {  // keep the far half of the range bounded; very near points are rare
        minD = std::max(0, int(dmax) - 320);
        numD = 320;
    }
    numD = std::max(16, numD);
    if (std::getenv("PARALLAX_DEBUG"))
        std::fprintf(stderr, "pair %d-%d %s%s B=%.4f f=%.1f minD=%d numD=%d d2=%.1f d98=%.1f\n", ri, ni,
                     vertical ? "V" : "H", Tf > 0 ? " flip" : "", baseline, f, minD, numD, percentile(disp, 0.02),
                     percentile(disp, 0.98));

    cv::Mat m1x, m1y, m2x, m2y, r1, r2;
    cv::initUndistortRectifyMap(Ka, distortionVector(ca), R1, P1, rs, CV_32FC1, m1x, m1y);
    cv::initUndistortRectifyMap(Kb, distortionVector(cb), R2, P2, rs, CV_32FC1, m2x, m2y);
    cv::remap(refImg, r1, m1x, m1y, cv::INTER_LINEAR, cv::BORDER_CONSTANT);
    cv::remap(nbImg, r2, m2x, m2y, cv::INTER_LINEAR, cv::BORDER_CONSTANT);
    if (vertical) { cv::transpose(r1, r1); cv::transpose(r2, r2); }
    const bool flip = Tf > 0;  // the neighbour sits left of (or above) the reference
    if (flip) { cv::flip(r1, r1, 1); cv::flip(r2, r2, 1); }

    if (numD + 32 >= r1.cols) return {};
    // Shift the second image right by minD so SGM searches [0, numD) and needs no wide margin;
    // OpenCV's SGBM otherwise discards minDisparity + numDisparities columns.
    if (minD > 0) {
        cv::Mat shifted(r2.size(), r2.type(), cv::Scalar(0));
        if (minD < r2.cols) r2.colRange(0, r2.cols - minD).copyTo(shifted.colRange(minD, r2.cols));
        r2 = shifted;
    }
    const int bs = 5;
    auto sgbm = cv::StereoSGBM::create(0, numD, bs, 8 * bs * bs, 32 * bs * bs, 1, 31, 10, 120, 2,
                                       cv::StereoSGBM::MODE_SGBM_3WAY);
    cv::Mat d16;
    sgbm->compute(r1, r2, d16);
    // Undo the shift; invalid pixels (negative) stay negative.
    for (int v = 0; v < d16.rows; ++v) {
        short* row = d16.ptr<short>(v);
        for (int u = 0; u < d16.cols; ++u)
            if (row[u] >= 0) row[u] = short(row[u] + 16 * minD);
    }
    if (flip) cv::flip(d16, d16, 1);
    if (vertical) cv::transpose(d16, d16);

    // For every pixel of the reference view's undistorted grid, look up the disparity at its
    // rectified position (bilinear over valid neighbours) and convert it to depth along the ray.
    cv::Mat depth(rv.size, CV_32F, cv::Scalar(0));
    const double fx = rv.K(0, 0), fy = rv.K(1, 1), ux = rv.K(0, 2), uy = rv.K(1, 2);
    for (int y = 0; y < rv.size.height; ++y) {
        float* drow = depth.ptr<float>(y);
        for (int x = 0; x < rv.size.width; ++x) {
            const cv::Vec3d ray((x - ux) / fx, (y - uy) / fy, 1.0);
            const cv::Vec3d pr = R1m * ray;  // rectified-frame direction
            if (pr[2] <= 1e-9) continue;
            const double u = f * pr[0] / pr[2] + cx, v = f * pr[1] / pr[2] + cy;
            const int u0 = int(std::floor(u)), v0 = int(std::floor(v));
            if (u0 < 0 || v0 < 0 || u0 + 1 >= d16.cols || v0 + 1 >= d16.rows) continue;
            const double au = u - u0, av = v - v0;
            double sum = 0, wsum = 0, dmin = 1e9, dmax = -1e9;
            for (int k = 0; k < 4; ++k) {
                const int uu = u0 + (k & 1), vv = v0 + (k >> 1);
                const short dv = d16.at<short>(vv, uu);
                if (dv <= 0) continue;
                const double w = ((k & 1) ? au : 1 - au) * ((k >> 1) ? av : 1 - av);
                const double dd = dv / 16.0;
                sum += w * dd; wsum += w;
                dmin = std::min(dmin, dd); dmax = std::max(dmax, dd);
            }
            if (wsum < 0.5 || dmax - dmin > 1.0) continue;  // hole or depth edge
            const double zr = f * baseline / (sum / wsum);  // depth in the rectified frame
            drow[x] = float(zr / pr[2]);                    // ray has z = 1 in the reference frame
        }
    }
    if (std::getenv("PARALLAX_DEBUG")) {
        // Agreement with the sparse model: median |depth - sparse depth| / sparse depth.
        std::vector<double> rel;
        for (int pid : shared) {
            const cv::Vec3d pc = A.R * s.points[pid].X + A.t;
            if (pc[2] <= 0) continue;
            const int x = int(std::lround(fx * pc[0] / pc[2] + ux)), y = int(std::lround(fy * pc[1] / pc[2] + uy));
            if (x < 0 || y < 0 || x >= depth.cols || y >= depth.rows || depth.at<float>(y, x) <= 0) continue;
            rel.push_back(std::abs(depth.at<float>(y, x) - pc[2]) / pc[2]);
        }
        std::fprintf(stderr, "  check %d-%d %s%s n=%zu medRel=%.4f\n", ri, ni, vertical ? "V" : "H", flip ? " flip" : "",
                     rel.size(), rel.empty() ? -1.0 : percentile(rel, 0.5));
    }
    return depth;
}

struct Voxel {
    float X[3] = {0, 0, 0}, n[3] = {0, 0, 0}, c[3] = {0, 0, 0};
    int count = 0, views = 0, lastView = -1;
};

}  // namespace

std::vector<DensePoint> denseReconstruct(const State& s, const ProgressFn& progress, float p0, float p1) {
    std::vector<int> reg;
    for (size_t i = 0; i < s.images.size(); ++i)
        if (s.images[i].registered) reg.push_back(int(i));
    if (reg.size() < 2) return {};

    // Points seen by each image, sorted, for covisibility queries.
    std::vector<std::vector<int>> seen(s.images.size());
    for (size_t j = 0; j < s.points.size(); ++j)
        if (s.points[j].valid)
            for (const auto& o : s.points[j].obs) seen[o.image].push_back(int(j));
    for (auto& v : seen) { std::sort(v.begin(), v.end()); v.erase(std::unique(v.begin(), v.end()), v.end()); }

    std::vector<int> refs;
    const int maxRefs = std::max(2, s.opt.maxDenseImages);
    for (int k = 0; k < int(reg.size()); ++k)
        if (int(reg.size()) <= maxRefs || (k * maxRefs) / int(reg.size()) != ((k - 1) * maxRefs) / int(reg.size()))
            refs.push_back(reg[k]);

    // Bounding radius of the scene from the sparse points (the model is centred on them).
    std::vector<double> radii;
    for (const auto& p : s.points) if (p.valid) radii.push_back(cv::norm(p.X));
    const double maxRadius = 3.0 * percentile(radii, 0.95);

    std::unordered_map<uint64_t, int> index;
    std::vector<Voxel> voxels;
    // Voxel size: about 1.5 dense pixels at the median depth of the first reference depth map,
    // then fixed for the whole scene. (The median distance to the sparse points is not a good
    // substitute: background features put it about twice as far as the surface being matched.)
    double voxel = 0;

    for (size_t r = 0; r < refs.size(); ++r) {
        if (progress && !progress("Dense matching", p0 + (p1 - p0) * float(r) / refs.size())) return {};
        const int ri = refs[r];
        const Image& A = s.images[ri];
        const cv::Vec3d Ca = A.centre();
        const cv::Vec3d axis(A.R(2, 0), A.R(2, 1), A.R(2, 2));

        // Neighbour selection.
        std::vector<std::pair<double, int>> cand;
        for (int nj : reg) {
            if (nj == ri) continue;
            std::vector<int> sh;
            std::set_intersection(seen[ri].begin(), seen[ri].end(), seen[nj].begin(), seen[nj].end(), std::back_inserter(sh));
            if (sh.size() < 30) continue;
            const cv::Vec3d Cb = s.images[nj].centre();
            const cv::Vec3d b = Cb - Ca;
            const double bl = cv::norm(b);
            if (bl < 1e-12) continue;
            const double lateral = std::sqrt(std::max(0.0, 1.0 - std::pow(b.dot(axis) / bl, 2)));
            if (lateral < 0.5) continue;
            std::vector<double> ang;
            for (size_t q = 0; q < sh.size(); q += std::max<size_t>(1, sh.size() / 200))
                ang.push_back(triangulationAngleDeg(s.points[sh[q]].X, Ca, Cb));
            const double a = percentile(ang, 0.5);
            if (a < 2.0 || a > 40.0) continue;
            cand.emplace_back(double(sh.size()) * std::exp(-std::pow((a - 10.0) / 8.0, 2)), nj);
        }
        std::sort(cand.rbegin(), cand.rend());
        if (cand.empty()) continue;

        const CameraModel& ca = s.cameras[A.camera];
        const View rv = denseView(ca, s.opt.denseMaxSide);
        cv::Mat refFull = cv::imread(A.input.path, cv::IMREAD_COLOR | cv::IMREAD_IGNORE_ORIENTATION);
        if (refFull.empty()) continue;
        cv::Mat refImg, refGray;
        cv::resize(refFull, refImg, rv.size, 0, 0, cv::INTER_AREA);
        cv::cvtColor(refImg, refGray, cv::COLOR_BGR2GRAY);
        cv::Mat refColor;  // undistorted colour for point colours
        cv::undistort(refImg, refColor, scaledK(ca, rv.scale), distortionVector(ca), cv::Mat(rv.K));

        std::vector<cv::Mat> depths;
        for (size_t c = 0; c < cand.size() && depths.size() < 2; ++c) {
            const int nj = cand[c].second;
            const CameraModel& cb = s.cameras[s.images[nj].camera];
            cv::Mat nbFull = cv::imread(s.images[nj].input.path, cv::IMREAD_GRAYSCALE | cv::IMREAD_IGNORE_ORIENTATION);
            if (nbFull.empty()) continue;
            const double sb = std::min(1.0, s.opt.denseMaxSide / double(std::max(cb.width, cb.height)));
            cv::Mat nb;
            cv::resize(nbFull, nb, cv::Size(), sb, sb, cv::INTER_AREA);
            std::vector<int> sh;
            std::set_intersection(seen[ri].begin(), seen[ri].end(), seen[nj].begin(), seen[nj].end(), std::back_inserter(sh));
            cv::Mat d = pairDepth(s, ri, nj, sh, refGray, nb, rv);
            if (!d.empty()) depths.push_back(d);
        }
        if (depths.empty()) continue;

        // Cross-check the two neighbours; with one neighbour, keep its depth.
        cv::Mat depth = depths[0].clone();
        if (depths.size() == 2 && std::getenv("PARALLAX_DEBUG")) {
            std::vector<double> rel;
            int both = 0;
            for (int y = 0; y < depth.rows; y += 2)
                for (int x = 0; x < depth.cols; x += 2) {
                    const float a = depths[0].at<float>(y, x), b = depths[1].at<float>(y, x);
                    if (a > 0 && b > 0) { rel.push_back(std::abs(a - b) / a); ++both; }
                }
            std::fprintf(stderr, "  xcheck ref %d: both=%d rel p10=%.4f p50=%.4f p90=%.4f\n", ri, both,
                         percentile(rel, 0.1), percentile(rel, 0.5), percentile(rel, 0.9));
        }
        if (depths.size() == 2) {
            for (int y = 0; y < depth.rows; ++y) {
                float* d0 = depth.ptr<float>(y);
                const float* d1 = depths[1].ptr<float>(y);
                for (int x = 0; x < depth.cols; ++x) {
                    if (d0[x] > 0 && d1[x] > 0 && std::abs(d0[x] - d1[x]) < 0.01f * d0[x]) d0[x] = 0.5f * (d0[x] + d1[x]);
                    else d0[x] = 0;
                }
            }
        }
        cv::medianBlur(depth, depth, 3);
        if (std::getenv("PARALLAX_DEBUG"))
            std::fprintf(stderr, "ref %d: maps %zu valid %d/%d final %d\n", ri, depths.size(), cv::countNonZero(depths[0]),
                         depths.size() > 1 ? cv::countNonZero(depths[1]) : 0, cv::countNonZero(depth));

        if (voxel == 0) {
            std::vector<double> z;
            for (int y = 0; y < depth.rows; y += 4)
                for (int x = 0; x < depth.cols; x += 4)
                    if (depth.at<float>(y, x) > 0) z.push_back(depth.at<float>(y, x));
            if (z.empty()) continue;
            voxel = 1.5 * percentile(z, 0.5) / rv.K(0, 0);
        }

        const double fx = rv.K(0, 0), fy = rv.K(1, 1), ux = rv.K(0, 2), uy = rv.K(1, 2);
        auto backproject = [&](int x, int y) {
            const double z = depth.at<float>(y, x);
            return cv::Vec3d((x - ux) * z / fx, (y - uy) * z / fy, z);
        };
        const cv::Matx33d Rt = A.R.t();
        for (int y = 1; y + 1 < depth.rows; ++y)
            for (int x = 1; x + 1 < depth.cols; ++x) {
                if (depth.at<float>(y, x) <= 0 || depth.at<float>(y, x - 1) <= 0 || depth.at<float>(y, x + 1) <= 0 ||
                    depth.at<float>(y - 1, x) <= 0 || depth.at<float>(y + 1, x) <= 0)
                    continue;
                // No normals across depth discontinuities (silhouettes): they would be grazing.
                const float zc = depth.at<float>(y, x);
                if (std::abs(depth.at<float>(y, x + 1) - depth.at<float>(y, x - 1)) > 0.05f * zc ||
                    std::abs(depth.at<float>(y + 1, x) - depth.at<float>(y - 1, x)) > 0.05f * zc)
                    continue;
                const cv::Vec3d P = backproject(x, y);
                cv::Vec3d nrm = (backproject(x + 1, y) - backproject(x - 1, y)).cross(backproject(x, y + 1) - backproject(x, y - 1));
                const double nn = cv::norm(nrm);
                if (nn < 1e-15) continue;
                nrm /= nn;
                if (nrm.dot(P) > 0) nrm = -nrm;
                if (-nrm.dot(P) / cv::norm(P) < 0.15) continue;  // grazing angle: unreliable
                const cv::Vec3d Xw = Rt * (P - A.t);
                if (cv::norm(Xw) > maxRadius) continue;
                const cv::Vec3d nw = Rt * nrm;
                const int64_t ix = int64_t(std::floor(Xw[0] / voxel)), iy = int64_t(std::floor(Xw[1] / voxel)),
                              iz = int64_t(std::floor(Xw[2] / voxel));
                const uint64_t key = (uint64_t(ix & 0x1FFFFF) << 42) | (uint64_t(iy & 0x1FFFFF) << 21) | uint64_t(iz & 0x1FFFFF);
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
                const cv::Vec3b c = refColor.at<cv::Vec3b>(y, x);
                for (int q = 0; q < 3; ++q) {
                    vx.X[q] += float(Xw[q]);
                    vx.n[q] += float(nw[q]);
                }
                vx.c[0] += c[2]; vx.c[1] += c[1]; vx.c[2] += c[0];
                vx.count++;
                if (vx.lastView != int(r)) { vx.views++; vx.lastView = int(r); }
            }
    }

    const int minViews = refs.size() >= 3 ? 2 : 1;
    std::vector<DensePoint> out;
    out.reserve(voxels.size());
    for (const auto& v : voxels) {
        if (v.views < minViews) continue;
        const float inv = 1.f / v.count;
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
