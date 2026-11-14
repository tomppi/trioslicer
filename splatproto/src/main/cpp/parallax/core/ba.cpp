// Parallax: sparse Levenberg-Marquardt bundle adjustment with the Schur complement.
//
// Minimises sum_ij rho_huber(|pi(K_c, R_i X_j + t_i) - u_ij|) over camera poses, 3-D points and,
// optionally, per-camera focal scale and radial distortion (k1, k2). Points are eliminated with
// the Schur complement, and the reduced camera system is solved by Cholesky
// (Triggs et al., "Bundle Adjustment - A Modern Synthesis", 2000).
// SPDX-License-Identifier: GPL-3.0-or-later
#include <opencv2/calib3d.hpp>

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unordered_map>

#include "internal.hpp"

namespace parallax {
namespace {

struct ObsTerm {
    int point;       // index into the optimised point list
    int image;
    int keypoint;
    int nidx = 0;    // number of camera-side parameters
    int idx[9];      // global indices of those parameters (-1 never stored)
    double Jc[2][9];
    double Jp[2][3];
    double r[2];
    double w = 0;    // robust IRLS weight, 0 when the point is behind the camera
};

struct Params {
    std::vector<cv::Matx33d> R;
    std::vector<cv::Vec3d> t;
    std::vector<cv::Vec3d> X;
    std::vector<CameraModel> cam;
};

double huberCost(double e, double d) { return e <= d ? e * e : 2 * d * e - d * d; }

// Residual and Jacobians of one observation at camera-frame point p. Returns false when the
// point is behind the camera.
bool linearise(const CameraModel& c, const cv::Vec3d& p, const cv::Point2f& uv, double r[2],
               double Jx[2][3] /* d(u,v)/d(x_cam) */, double Ji[2][3] /* d(u,v)/d(fscale,k1,k2) */) {
    if (p[2] <= 1e-6) return false;
    const double iz = 1.0 / p[2];
    const double a = p[0] * iz, b = p[1] * iz;
    const double r2 = a * a + b * b;
    const double d = 1 + r2 * (c.k1 + r2 * (c.k2 + r2 * c.k3));
    const double ad = a * d + 2 * c.p1 * a * b + c.p2 * (r2 + 2 * a * a);
    const double bd = b * d + c.p1 * (r2 + 2 * b * b) + 2 * c.p2 * a * b;
    r[0] = c.fx * ad + c.cx - uv.x;
    r[1] = c.fy * bd + c.cy - uv.y;

    const double dd = c.k1 + 2 * c.k2 * r2 + 3 * c.k3 * r2 * r2;  // d(d)/d(r2)
    const double dad_da = d + a * dd * 2 * a + 2 * c.p1 * b + 6 * c.p2 * a;
    const double dad_db = a * dd * 2 * b + 2 * c.p1 * a + 2 * c.p2 * b;
    const double dbd_da = b * dd * 2 * a + 2 * c.p1 * a + 2 * c.p2 * b;
    const double dbd_db = d + b * dd * 2 * b + 6 * c.p1 * b + 2 * c.p2 * a;
    // d(a,b)/d(p) = [[iz, 0, -a iz], [0, iz, -b iz]]
    const double du_da = c.fx * dad_da, du_db = c.fx * dad_db;
    const double dv_da = c.fy * dbd_da, dv_db = c.fy * dbd_db;
    Jx[0][0] = du_da * iz; Jx[0][1] = du_db * iz; Jx[0][2] = -(du_da * a + du_db * b) * iz;
    Jx[1][0] = dv_da * iz; Jx[1][1] = dv_db * iz; Jx[1][2] = -(dv_da * a + dv_db * b) * iz;
    if (Ji) {
        Ji[0][0] = c.fx * ad;    Ji[1][0] = c.fy * bd;
        Ji[0][1] = c.fx * a * r2; Ji[1][1] = c.fy * b * r2;
        Ji[0][2] = c.fx * a * r2 * r2; Ji[1][2] = c.fy * b * r2 * r2;
    }
    return true;
}

cv::Matx33d expSO3(const cv::Vec3d& w) {
    cv::Matx33d R;
    cv::Rodrigues(w, R);
    return R;
}

}  // namespace

double bundleAdjust(State& s, const BAOptions& o) {
    // Variable poses.
    std::unordered_map<int, int> poseSlot;  // image -> slot
    for (int i : o.variableImages)
        if (i != o.fixedGaugeImage && s.images[i].registered && !poseSlot.count(i)) {
            const int slot = int(poseSlot.size());
            poseSlot[i] = slot;
        }
    // Variable intrinsics: cameras used by at least five variable images.
    std::unordered_map<int, int> intrSlot;  // camera index -> slot
    if (o.refineIntrinsics) {
        std::unordered_map<int, int> count;
        for (const auto& kv : poseSlot) count[s.images[kv.first].camera]++;
        for (const auto& kv : count)
            if (kv.second >= 5) {
                const int slot = int(intrSlot.size());
                intrSlot[kv.first] = slot;
            }
    }
    const int nPose = int(poseSlot.size());
    const int n = 6 * nPose + 3 * int(intrSlot.size());

    // Points touched by a variable image.
    std::vector<int> pts;
    for (int j = 0; j < int(s.points.size()); ++j) {
        const Point3D& p = s.points[j];
        if (!p.valid || p.obs.size() < 2) continue;
        for (const auto& ob : p.obs)
            if (poseSlot.count(ob.image)) { pts.push_back(j); break; }
    }
    if (pts.empty() || n == 0) return 0;

    Params cur;
    cur.R.resize(s.images.size());
    cur.t.resize(s.images.size());
    for (size_t i = 0; i < s.images.size(); ++i) { cur.R[i] = s.images[i].R; cur.t[i] = s.images[i].t; }
    cur.cam = s.cameras;
    cur.X.resize(pts.size());
    for (size_t k = 0; k < pts.size(); ++k) cur.X[k] = s.points[pts[k]].X;

    std::vector<ObsTerm> terms;
    for (size_t k = 0; k < pts.size(); ++k)
        for (const auto& ob : s.points[pts[k]].obs) {
            ObsTerm t{};
            t.point = int(k);
            t.image = ob.image;
            t.keypoint = ob.keypoint;
            terms.push_back(t);
        }

    auto evaluate = [&](const Params& P, bool withJacobians) {
        double cost = 0;
        for (auto& t : terms) {
            const Image& im = s.images[t.image];
            const CameraModel& c = P.cam[im.camera];
            double Jx[2][3], Ji[2][3];
            const auto is = intrSlot.find(im.camera);
            const bool intr = is != intrSlot.end();
            const cv::Matx33d& R = P.R[t.image];
            const cv::Point2f& obs = im.pixels[t.keypoint];
            // Rolling shutter: x = A y with y = R X + t + offset (identity for a global shutter).
            const RowMotion m = rowMotion(s, im, obs.y, R);
            const cv::Vec3d y = R * P.X[t.point] + P.t[t.image] + m.offset;
            if (!linearise(c, m.A * y, obs, t.r, Jx, intr ? Ji : nullptr)) {
                t.w = 0;
                cost += huberCost(50.0, o.huberPx);  // constant penalty, keeps the cost comparable
                continue;
            }
            const double e = std::sqrt(t.r[0] * t.r[0] + t.r[1] * t.r[1]);
            cost += huberCost(e, o.huberPx);
            if (!withJacobians) continue;
            t.w = e <= o.huberPx ? 1.0 : o.huberPx / e;
            // Chain through A: d(u,v)/dy = Jx A.
            double JA[2][3];
            for (int r = 0; r < 2; ++r)
                for (int q = 0; q < 3; ++q)
                    JA[r][q] = Jx[r][0] * m.A(0, q) + Jx[r][1] * m.A(1, q) + Jx[r][2] * m.A(2, q);
            std::memcpy(Jx, JA, sizeof JA);
            // dy/dX = R
            for (int r = 0; r < 2; ++r)
                for (int q = 0; q < 3; ++q)
                    t.Jp[r][q] = Jx[r][0] * R(0, q) + Jx[r][1] * R(1, q) + Jx[r][2] * R(2, q);
            t.nidx = 0;
            const auto ps = poseSlot.find(t.image);
            if (ps != poseSlot.end()) {
                // Left perturbation: y' = exp(w) y + dt  =>  dy/dw = -[y]x, dy/ddt = I
                // (the rolling-shutter offset R v is held fixed within a step).
                const cv::Vec3d x = y;
                for (int r = 0; r < 2; ++r) {
                    const double* j = Jx[r];
                    // j * (-[x]x)
                    t.Jc[r][0] = j[2] * x[1] - j[1] * x[2];
                    t.Jc[r][1] = j[0] * x[2] - j[2] * x[0];
                    t.Jc[r][2] = j[1] * x[0] - j[0] * x[1];
                    t.Jc[r][3] = j[0]; t.Jc[r][4] = j[1]; t.Jc[r][5] = j[2];
                }
                for (int q = 0; q < 6; ++q) t.idx[q] = 6 * ps->second + q;
                t.nidx = 6;
            }
            if (intr) {
                for (int q = 0; q < 3; ++q) {
                    t.Jc[0][t.nidx] = Ji[0][q];
                    t.Jc[1][t.nidx] = Ji[1][q];
                    t.idx[t.nidx++] = 6 * nPose + 3 * is->second + q;
                }
            }
        }
        return cost;
    };

    // Factory-calibrated lenses (LENS_INTRINSIC_CALIBRATION) get a Gaussian prior on focal scale
    // (1 %) and k1, k2 (0.02): weak against well-conditioned data, decisive when the geometry
    // leaves the intrinsics unconstrained.
    constexpr double kSigmaF = 0.01, kSigmaK = 0.02;
    auto priorOf = [&](int cam) -> const CameraModel* {
        return cam < int(s.priors.size()) && s.priors[cam].calibrated ? &s.priors[cam] : nullptr;
    };
    auto priorCost = [&](const Params& P) {
        double c = 0;
        for (const auto& kv : intrSlot)
            if (const CameraModel* p0 = priorOf(kv.first)) {
                const CameraModel& c1 = P.cam[kv.first];
                c += std::pow((c1.fx / p0->fx - 1) / kSigmaF, 2) + std::pow((c1.k1 - p0->k1) / kSigmaK, 2) +
                     std::pow((c1.k2 - p0->k2) / kSigmaK, 2);
            }
        return c;
    };

    double lambda = 1e-3;
    double cost = evaluate(cur, true) + priorCost(cur);
    const double cost0 = cost;
    int accepts = 0;
    const int np = int(pts.size());

    for (int iter = 0; iter < o.maxIterations; ++iter) {
        // Normal equations, split into camera (C), point (P) and coupling (E) blocks.
        cv::Mat C = cv::Mat::zeros(n, n, CV_64F);
        cv::Mat gc = cv::Mat::zeros(n, 1, CV_64F);
        std::vector<cv::Matx33d> Pb(np, cv::Matx33d::zeros());
        std::vector<cv::Vec3d> gp(np, cv::Vec3d(0, 0, 0));
        std::vector<int> active(np, 0);  // observations currently in front of their camera
        for (const auto& t : terms) {
            if (t.w == 0) continue;
            active[t.point]++;
            cv::Matx33d& Pj = Pb[t.point];
            for (int a = 0; a < 3; ++a) {
                gp[t.point][a] += t.w * (t.Jp[0][a] * t.r[0] + t.Jp[1][a] * t.r[1]);
                for (int b = 0; b < 3; ++b) Pj(a, b) += t.w * (t.Jp[0][a] * t.Jp[0][b] + t.Jp[1][a] * t.Jp[1][b]);
            }
            for (int a = 0; a < t.nidx; ++a) {
                gc.at<double>(t.idx[a]) += t.w * (t.Jc[0][a] * t.r[0] + t.Jc[1][a] * t.r[1]);
                double* row = C.ptr<double>(t.idx[a]);
                for (int b = 0; b < t.nidx; ++b)
                    row[t.idx[b]] += t.w * (t.Jc[0][a] * t.Jc[0][b] + t.Jc[1][a] * t.Jc[1][b]);
            }
        }
        for (const auto& kv : intrSlot)
            if (const CameraModel* p0 = priorOf(kv.first)) {
                const CameraModel& c1 = cur.cam[kv.first];
                const int b = 6 * nPose + 3 * kv.second;
                const double jf = c1.fx / p0->fx / kSigmaF, rf = (c1.fx / p0->fx - 1) / kSigmaF;
                C.at<double>(b, b) += jf * jf;
                gc.at<double>(b) += jf * rf;
                C.at<double>(b + 1, b + 1) += 1 / (kSigmaK * kSigmaK);
                gc.at<double>(b + 1) += (c1.k1 - p0->k1) / (kSigmaK * kSigmaK);
                C.at<double>(b + 2, b + 2) += 1 / (kSigmaK * kSigmaK);
                gc.at<double>(b + 2) += (c1.k2 - p0->k2) / (kSigmaK * kSigmaK);
            }
        // Per point: merged coupling rows E_j (camera index -> 3-vector).
        std::vector<std::vector<std::pair<int, cv::Vec3d>>> Ej(np);
        for (const auto& t : terms) {
            if (t.w == 0) continue;
            auto& list = Ej[t.point];
            for (int a = 0; a < t.nidx; ++a) {
                cv::Vec3d e;
                for (int b = 0; b < 3; ++b) e[b] = t.w * (t.Jc[0][a] * t.Jp[0][b] + t.Jc[1][a] * t.Jp[1][b]);
                bool merged = false;
                for (auto& kv : list)
                    if (kv.first == t.idx[a]) { kv.second += e; merged = true; break; }
                if (!merged) list.emplace_back(t.idx[a], e);
            }
        }

        bool accepted = false;
        for (int attempt = 0; attempt < 8 && !accepted; ++attempt) {
            cv::Mat S = C.clone();
            cv::Mat rhs = -gc;
            for (int i = 0; i < n; ++i) S.at<double>(i, i) += lambda * C.at<double>(i, i) + 1e-9;
            std::vector<cv::Matx33d> Pinv(np);
            for (int j = 0; j < np; ++j) {
                if (active[j] < 2) {
                    // Rank-deficient block (one view, or none in front): hold the point this step.
                    Pinv[j] = cv::Matx33d::zeros();
                    continue;
                }
                cv::Matx33d Pd = Pb[j];
                for (int a = 0; a < 3; ++a) Pd(a, a) += lambda * Pb[j](a, a) + 1e-9;
                Pinv[j] = Pd.inv(cv::DECOMP_CHOLESKY);
                const auto& list = Ej[j];
                std::vector<cv::Vec3d> F(list.size());
                for (size_t a = 0; a < list.size(); ++a) F[a] = Pinv[j] * list[a].second;  // P^-1 E^T
                for (size_t a = 0; a < list.size(); ++a) {
                    double* row = S.ptr<double>(list[a].first);
                    for (size_t b = 0; b < list.size(); ++b) row[list[b].first] -= list[a].second.dot(F[b]);
                    rhs.at<double>(list[a].first) += F[a].dot(gp[j]);
                }
            }
            // Scale gauge: pin one translation component of a second image.
            const auto gs = poseSlot.find(o.scaleGaugeImage);
            if (gs != poseSlot.end()) {
                const int g = 6 * gs->second + 3 + o.scaleGaugeAxis;
                S.row(g).setTo(0);
                S.col(g).setTo(0);
                S.at<double>(g, g) = 1;
                rhs.at<double>(g) = 0;
            }
            cv::Mat dc;
            if (!cv::solve(S, rhs, dc, cv::DECOMP_CHOLESKY)) {
                lambda *= 10;
                continue;
            }
            // Back-substitute points and apply the step to a candidate parameter set.
            Params cand = cur;
            for (int j = 0; j < np; ++j) {
                cv::Vec3d b = -gp[j];
                for (const auto& kv : Ej[j]) b -= kv.second * dc.at<double>(kv.first);
                cand.X[j] += Pinv[j] * b;
            }
            for (const auto& kv : poseSlot) {
                const double* d = dc.ptr<double>(6 * kv.second);
                const cv::Matx33d dR = expSO3({d[0], d[1], d[2]});
                cand.R[kv.first] = dR * cur.R[kv.first];
                cand.t[kv.first] = dR * cur.t[kv.first] + cv::Vec3d(d[3], d[4], d[5]);
            }
            for (const auto& kv : intrSlot) {
                const double* d = dc.ptr<double>(6 * nPose + 3 * kv.second);
                CameraModel& c = cand.cam[kv.first];
                const double sc = std::max(0.5, std::min(2.0, 1.0 + d[0]));
                c.fx *= sc;
                c.fy *= sc;
                c.k1 += d[1];
                c.k2 += d[2];
            }
            const double newCost = evaluate(cand, false) + priorCost(cand);
            if (newCost < cost) {
                const double rel = (cost - newCost) / std::max(cost, 1e-12);
                cur = std::move(cand);
                cost = evaluate(cur, true) + priorCost(cur);
                lambda = std::max(1e-7, lambda * 0.3);
                accepted = true;
                ++accepts;
                if (rel < 1e-6) iter = o.maxIterations;  // converged
            } else {
                lambda *= 5;
            }
        }
        if (!accepted) break;
    }

    // Write back.
    for (const auto& kv : poseSlot) { s.images[kv.first].R = cur.R[kv.first]; s.images[kv.first].t = cur.t[kv.first]; }
    for (const auto& kv : intrSlot) s.cameras[kv.first] = cur.cam[kv.first];
    for (size_t k = 0; k < pts.size(); ++k) s.points[pts[k]].X = cur.X[k];

    if (std::getenv("PARALLAX_DEBUG"))
        std::fprintf(stderr, "BA poses=%d intr=%zu points=%d obs=%zu cost %.4g -> %.4g accepted=%d lambda=%.2g\n", nPose,
                     intrSlot.size(), np, terms.size(), cost0, cost, accepts, lambda);
    evaluate(cur, true);  // a rejected trial step leaves its residuals in `terms`
    double se = 0;
    int cnt = 0;
    for (const auto& t : terms) {
        if (t.w == 0) continue;
        se += t.r[0] * t.r[0] + t.r[1] * t.r[1];
        ++cnt;
    }
    return cnt ? std::sqrt(se / cnt) : 0;
}

}  // namespace parallax
