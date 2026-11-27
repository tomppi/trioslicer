#pragma once

#include <cmath>
#include <cstddef>

namespace ov_msckf {

enum class CameraImuCoverage { Ready, NeedNewerImu, MissingHistory, InvalidTime };

// OpenVINS needs a sample strictly after the target to interpolate the last interval.
inline CameraImuCoverage camera_imu_coverage(double start, double target, std::size_t count,
                                             double oldest, double newest) {
  if (!std::isfinite(start) || !std::isfinite(target) || target < start)
    return CameraImuCoverage::InvalidTime;
  if (count > 0 && oldest > start) return CameraImuCoverage::MissingHistory;
  if (count < 2 || newest <= target) return CameraImuCoverage::NeedNewerImu;
  return CameraImuCoverage::Ready;
}

// Camera and filter state use the same camera-clock value, not an IMU timestamp.
inline bool pose_matches_camera_frame(double requested, double submitted, double state) {
  return requested > 0.0 && std::isfinite(requested) && requested == submitted && requested == state;
}

} // namespace ov_msckf
