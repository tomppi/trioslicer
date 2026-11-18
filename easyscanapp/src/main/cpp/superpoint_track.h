#ifndef OV_CORE_TRACK_SUPERPOINT_H
#define OV_CORE_TRACK_SUPERPOINT_H

#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#include <Eigen/Core>
#include <opencv2/core/core.hpp>

#include "track/TrackBase.h"
#include "utils/sensor_data.h"

namespace ov_core {

/// One externally supplied SuperPoint detection set (per camera, per timestamp).
struct SuperPointFrame {
  double ts = -1.0;
  std::vector<cv::Point2f> kp;
  std::vector<std::vector<float>> desc;
};

/// Submit SuperPoint features for the next camera frame (called from JNI).
void superpoint_submit(int cam_id, double ts, const float *kp_xy, const float *desc, int n);

/**
 * @brief OpenVINS tracker whose detected points come from the NPU SuperPoint front-end.
 *
 * SuperPoint supplies detections; KLT optical flow carries the IDs from frame to
 * frame (a hard requirement for MSCKF). New SuperPoint points are added when they
 * do not overlap existing tracks.
 */
class TrackSuperPoint : public TrackBase {
public:
  TrackSuperPoint(std::unordered_map<size_t, std::shared_ptr<CamBase>> cameras, int numfeats, int numaruco,
                  bool stereo, HistogramMethod histmethod)
      : TrackBase(cameras, numfeats, numaruco, stereo, histmethod) {}
  ~TrackSuperPoint() override = default;

  void feed_new_camera(const CameraData &message) override;

  void display_active(cv::Mat &img_out, int r1, int g1, int b1, int r2, int g2, int b2,
                      std::string overlay = "") override;
  void display_history(cv::Mat &img_out, int r1, int g1, int b1, int r2, int g2, int b2,
                       std::vector<size_t> highlighted = {}, std::string overlay = "") override;

protected:
  void feed_monocular(const CameraData &message, size_t msg_id);

  std::unordered_map<size_t, cv::Mat> prev_gray;
  std::unordered_map<size_t, std::vector<cv::Point2f>> prev_kp;
  std::unordered_map<size_t, std::vector<size_t>> prev_ids;
  std::unordered_map<size_t, std::vector<int>> track_age;
  std::unordered_map<size_t, std::vector<int>> track_miss;
};

} // namespace ov_core

// Runtime caps for the flood experiment (defaults: 200 tracks, 10 px spacing).
void setTrackCaps(int maxTracks, float minNewDist);
void setReacqEnabled(int enabled);

#endif // OV_CORE_TRACK_SUPERPOINT_H
