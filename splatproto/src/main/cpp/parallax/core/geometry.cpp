// Parallax: camera model, two-view verification and multi-view triangulation.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <opencv2/calib3d.hpp>

#include "internal.hpp"

namespace parallax {

cv::Matx33d cameraMatrix(const CameraModel& c) {
    return {c.fx, 0, c.cx, 0, c.fy, c.cy, 0, 0, 1};
}

cv::Mat distortionVector(const CameraModel& c) {
    return cv::Mat(cv::Matx<double, 1, 5>(c.k1, c.k2, c.p1, c.p2, c.k3));
}

cv::Point2d projectPoint(const CameraModel& c, const cv::Matx33d& R, const cv::Vec3d& t,
                         const cv::Vec3d& X, bool* inFront) {
    return camToPixel(c, R * X + t, inFront);
}

RowMotion rowMotion(const State& s, const Image& im, double row, const cv::Matx33d& R) {
    RowMotion m;
    if (!im.rs) return m;
    const CameraModel& c = s.cameras[im.camera];
    const double dt = im.input.rollingShutterSkew * (row / std::max(1, c.height - 1) - 0.5);
    const cv::Vec3d w = im.omega * dt;
    m.A = cv::Matx33d(1, w[2], -w[1], -w[2], 1, w[0], w[1], -w[0], 1);  // I - [w]x
    m.offset = -dt * (R * im.velocity);
    return m;
}

cv::Point2d camToPixel(const CameraModel& c, const cv::Vec3d& p, bool* inFront) {
    if (inFront) *inFront = p[2] > 1e-9;
    const double z = std::abs(p[2]) > 1e-12 ? p[2] : 1e-12;
    const double a = p[0] / z, b = p[1] / z;
    const double r2 = a * a + b * b;
    const double d = 1 + r2 * (c.k1 + r2 * (c.k2 + r2 * c.k3));
    const double ad = a * d + 2 * c.p1 * a * b + c.p2 * (r2 + 2 * a * a);
    const double bd = b * d + c.p1 * (r2 + 2 * b * b) + 2 * c.p2 * a * b;
    return {c.fx * ad + c.cx, c.fy * bd + c.cy};
}

void undistortKeypoints(const CameraModel& c, const std::vector<cv::Point2f>& px,
                        std::vector<cv::Point2d>& normalized) {
    normalized.clear();
    if (px.empty()) return;
    std::vector<cv::Point2f> out;
    // More iterations than the default 5: ultra-wide lenses have strong barrel distortion.
    cv::undistortPoints(px, out, cameraMatrix(c), distortionVector(c), cv::noArray(), cv::noArray(),
                        cv::TermCriteria(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 30, 1e-10));
    normalized.resize(out.size());
    for (size_t i = 0; i < out.size(); ++i) normalized[i] = {out[i].x, out[i].y};
}

bool verifyPair(const State& s, int a, int b, const std::vector<std::pair<int, int>>& matches,
                PairGeometry& out) {
    if (matches.size() < 30) return false;
    const Image& A = s.images[a];
    const Image& B = s.images[b];
    std::vector<cv::Point2d> pa, pb;
    pa.reserve(matches.size());
    pb.reserve(matches.size());
    for (const auto& m : matches) {
        pa.push_back(A.normalized[m.first]);
        pb.push_back(B.normalized[m.second]);
    }
    const double f = 0.5 * (s.cameras[A.camera].fx + s.cameras[B.camera].fx);
    const double thr = 1.5 / f;  // 1.5 px in normalized units
    cv::Mat mask;
    // Nister's five-point solver inside RANSAC on calibrated (normalized) coordinates.
    cv::Mat E = cv::findEssentialMat(pa, pb, cv::Mat::eye(3, 3, CV_64F), cv::RANSAC, 0.9999, thr, 4000, mask);
    if (E.empty() || E.rows < 3) return false;
    if (E.rows > 3) E = E.rowRange(0, 3).clone();

    out = PairGeometry{};
    out.a = a;
    out.b = b;
    for (size_t i = 0; i < matches.size(); ++i)
        if (mask.at<uchar>(int(i))) out.inliers.push_back(matches[i]);
    if (out.inliers.size() < 25) return false;

    cv::Mat R, t, poseMask = mask.clone();
    cv::recoverPose(E, pa, pb, cv::Mat::eye(3, 3, CV_64F), R, t, poseMask);
    out.R = cv::Matx33d(R);
    out.t = cv::Vec3d(t);

    cv::Mat hmask;
    cv::Mat H = cv::findHomography(pa, pb, cv::RANSAC, 4.0 / f, hmask, 2000, 0.999);
    out.homographyInliers = H.empty() ? 0 : cv::countNonZero(hmask);
    return true;
}

double triangulationAngleDeg(const cv::Vec3d& X, const cv::Vec3d& c1, const cv::Vec3d& c2) {
    const cv::Vec3d r1 = X - c1, r2 = X - c2;
    const double n = cv::norm(r1) * cv::norm(r2);
    if (n < 1e-15) return 0;
    const double c = std::max(-1.0, std::min(1.0, r1.dot(r2) / n));
    return std::acos(c) * 180.0 / kPi;
}

double reprojectionError(const State& s, const Point3D& p, const Observation& o) {
    const Image& im = s.images[o.image];
    bool front = false;
    const cv::Point2f& obs = im.pixels[o.keypoint];
    const RowMotion m = rowMotion(s, im, obs.y, im.R);
    const cv::Point2d uv = camToPixel(s.cameras[im.camera], m.A * (im.R * p.X + im.t + m.offset), &front);
    if (!front) return 1e9;
    return cv::norm(uv - cv::Point2d(im.pixels[o.keypoint]));
}

// Linear (DLT) triangulation from all registered views, followed by cheirality, reprojection
// and triangulation-angle checks (Hartley & Zisserman, ch. 12).
bool triangulateTrack(State& s, int trackId) {
    Track& tr = s.tracks[trackId];
    if (tr.point >= 0) return false;
    std::vector<Observation> obs;
    for (const auto& e : tr.elements)
        if (s.images[e.image].registered) obs.push_back(e);
    if (obs.size() < 2) return false;

    cv::Mat A(int(obs.size()) * 2, 4, CV_64F);
    for (size_t i = 0; i < obs.size(); ++i) {
        const Image& im = s.images[obs[i].image];
        const cv::Point2d& x = im.normalized[obs[i].keypoint];
        for (int c = 0; c < 4; ++c) {
            const double p0 = c < 3 ? im.R(0, c) : im.t[0];
            const double p1 = c < 3 ? im.R(1, c) : im.t[1];
            const double p2 = c < 3 ? im.R(2, c) : im.t[2];
            A.at<double>(int(2 * i), c) = x.x * p2 - p0;
            A.at<double>(int(2 * i + 1), c) = x.y * p2 - p1;
        }
    }
    cv::Mat w, u, vt;
    cv::SVD::compute(A, w, u, vt, cv::SVD::MODIFY_A);
    const double hw = vt.at<double>(3, 3);
    if (std::abs(hw) < 1e-12) return false;
    Point3D p;
    p.X = {vt.at<double>(3, 0) / hw, vt.at<double>(3, 1) / hw, vt.at<double>(3, 2) / hw};

    std::vector<Observation> good;
    for (const auto& o : obs)
        if (reprojectionError(s, p, o) < s.opt.maxReprojError) good.push_back(o);
    if (good.size() < 2) return false;

    double maxAngle = 0;
    for (size_t i = 0; i < good.size(); ++i)
        for (size_t j = i + 1; j < good.size(); ++j)
            maxAngle = std::max(maxAngle, triangulationAngleDeg(p.X, s.images[good[i].image].centre(),
                                                                s.images[good[j].image].centre()));
    if (maxAngle < s.opt.minTriAngleDeg) return false;

    p.obs = good;
    p.track = trackId;
    tr.point = int(s.points.size());
    s.points.push_back(p);
    return true;
}

}  // namespace parallax
