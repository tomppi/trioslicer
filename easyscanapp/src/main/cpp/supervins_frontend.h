#pragma once

#include <atomic>
#include <cmath>
#include <map>
#include <mutex>
#include <unordered_map>
#include <utility>
#include <vector>

#include <Eigen/Dense>
#include <opencv2/core/core.hpp>

namespace supervins {

/// Calibrated pinhole + Brown-Conrady (radtan) camera model for 640x480 VIO frames.
struct CameraModel {
  double fx = 432.0;
  double fy = 432.0;
  double cx = 320.0;
  double cy = 240.0;
  double k1 = 0.0;
  double k2 = 0.0;
  double p1 = 0.0;
  double p2 = 0.0;
  int width = 640;
  int height = 480;

  /// Iterative Brown-Conrady undistortion: distorted pixel (u, v) -> normalized plane (x, y).
  Eigen::Vector2d undistort(double u, double v) const {
    const double x0 = (u - cx) / fx;
    const double y0 = (v - cy) / fy;
    if (k1 == 0.0 && k2 == 0.0 && p1 == 0.0 && p2 == 0.0) {
      return Eigen::Vector2d(x0, y0);
    }
    double x = x0;
    double y = y0;
    for (int iter = 0; iter < 12; ++iter) {
      const double r2 = x * x + y * y;
      if (r2 > 9.0) break;
      const double rad = 1.0 + r2 * (k1 + r2 * k2);
      const double dRad = k1 + 2.0 * r2 * k2;
      const double fxv = x * rad + 2.0 * p1 * x * y + p2 * (r2 + 2.0 * x * x) - x0;
      const double fyv = y * rad + p1 * (r2 + 2.0 * y * y) + 2.0 * p2 * x * y - y0;
      if (fxv * fxv + fyv * fyv < 1e-20) break;
      const double j00 = rad + 2.0 * x * x * dRad + 2.0 * p1 * y + 6.0 * p2 * x;
      const double j01 = 2.0 * x * y * dRad + 2.0 * p1 * x + 2.0 * p2 * y;
      const double j11 = rad + 2.0 * y * y * dRad + 2.0 * p2 * x + 6.0 * p1 * y;
      const double det = j00 * j11 - j01 * j01;
      if (std::fabs(det) < 1e-12 || !std::isfinite(det)) break;
      x -= (j11 * fxv - j01 * fyv) / det;
      y -= (-j01 * fxv + j00 * fyv) / det;
    }
    return Eigen::Vector2d(x, y);
  }

  /// Forward Brown-Conrady projection: camera-frame 3D point -> distorted pixel (u, v).
  bool project(const Eigen::Vector3d &p_cam, cv::Point2f &out) const {
    if (!p_cam.allFinite() || p_cam.z() <= 0.05) return false;
    const double x = p_cam.x() / p_cam.z();
    const double y = p_cam.y() / p_cam.z();
    const double r2 = x * x + y * y;
    if (!std::isfinite(r2) || r2 > 4.0) return false;
    const double rad = 1.0 + r2 * (k1 + r2 * k2);
    const double xd = x * rad + 2.0 * p1 * x * y + p2 * (r2 + 2.0 * x * x);
    const double yd = y * rad + p1 * (r2 + 2.0 * y * y) + 2.0 * p2 * x * y;
    const double u = fx * xd + cx;
    const double v = fy * yd + cy;
    if (!std::isfinite(u) || !std::isfinite(v)) return false;
    out = cv::Point2f((float)u, (float)v);
    return true;
  }
};

using FeaturePacket = std::map<int, std::vector<std::pair<int, Eigen::Matrix<double, 7, 1>>>>;

/// SuperVINS front-end tracker:
///  - Spatially spaced SuperPoint detections (min_dist px, laser-band masked)
///  - LightGlue NPU frame-to-frame matching (with optional KLT fallback)
///  - SuperVINS 3D landmark prediction gating
///  - Sub-pixel PyrLK refinement (preserving LightGlue matches when PyrLK rejects)
///  - Adaptive Fundamental-matrix RANSAC (3 px base)
///  - Exact Brown-Conrady undistortion + normalized feature velocity for estimate_td
class SuperVinsFrontend {
public:
  void reset(const CameraModel &cam);

  /// Process one camera frame and emit SuperVINS feature observations with normalized velocities.
  FeaturePacket trackFrame(
      double timestamp,
      const cv::Mat &gray,
      const float *kp_xy,
      const float *desc,
      int n_det,
      const std::map<int, Eigen::Vector3d> &predicted_pts_cam);

  int lastTrackedCount() const { return last_tracked_; }
  int lastTotalCount() const { return last_total_; }

private:
  CameraModel cam_;
  cv::Mat prev_gray_;
  double prev_ts_ = -1.0;
  int next_id_ = 1;
  float inlier_hist_ = 1.0f;
  int log_counter_ = 0;
  int last_tracked_ = 0;
  int last_total_ = 0;

  std::vector<cv::Point2f> prev_kp_;
  std::vector<Eigen::Vector2d> prev_un_;
  std::vector<int> prev_ids_;
  std::vector<int> prev_age_;
  std::vector<std::vector<float>> prev_desc_;
};

void setLaserBand(float cx, float cy, float nx, float ny, float half_width, int on);
void setTrackCaps(int maxTracks, float minNewDist);
void setLightGlueEnabled(int enabled);
void setKltFallbackEnabled(int enabled);
void setRansacThresholdPx(float px);
void setReacqEnabled(int enabled);

}  // namespace supervins
