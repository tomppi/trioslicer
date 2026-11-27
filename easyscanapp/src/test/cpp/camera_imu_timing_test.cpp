#include "CameraImuTiming.h"

#include <cassert>
#include <limits>

int main() {
  using ov_msckf::CameraImuCoverage;
  using ov_msckf::camera_imu_coverage;
  using ov_msckf::pose_matches_camera_frame;

  const double previous_frame = 100.0;
  const double current_frame = 100.033;
  const double next_frame = 100.066;
  const double previous_end = previous_frame + 0.006;
  const double target = current_frame + 0.010;

  // Wait for a sample strictly past the target; the latest sample at the target is not enough.
  assert(camera_imu_coverage(previous_end, target, 0, 0., 0.) == CameraImuCoverage::NeedNewerImu);
  assert(camera_imu_coverage(previous_end, target, 1, 99.9, 99.9) == CameraImuCoverage::NeedNewerImu);
  assert(camera_imu_coverage(previous_end, target, 20, 99.9, target) == CameraImuCoverage::NeedNewerImu);
  assert(camera_imu_coverage(previous_end, target, 21, 99.9, target + 0.002) == CameraImuCoverage::Ready);
  assert(camera_imu_coverage(previous_end, target, 20, previous_end + 0.001, target + 0.002) ==
         CameraImuCoverage::MissingHistory);

  // A live offset change changes the right endpoint without shifting the previous state time.
  assert(camera_imu_coverage(previous_end, current_frame + 0.018, 20, 99.9, target + 0.002) ==
         CameraImuCoverage::NeedNewerImu);
  assert(camera_imu_coverage(previous_end, current_frame - 0.012, 20, 99.9, current_frame - 0.010) ==
         CameraImuCoverage::Ready);
  assert(camera_imu_coverage(target, previous_end, 20, 99.9, target + 0.002) == CameraImuCoverage::InvalidTime);
  assert(camera_imu_coverage(target, target, 2, target, target + 0.002) == CameraImuCoverage::Ready);

  // A previous, unadvanced or unavailable estimator state must not be attached to this image.
  assert(!pose_matches_camera_frame(current_frame, previous_frame, previous_frame));
  assert(!pose_matches_camera_frame(current_frame, current_frame, previous_frame));
  assert(!pose_matches_camera_frame(current_frame, current_frame, next_frame));
  assert(!pose_matches_camera_frame(0.0, 0.0, 0.0));
  assert(!pose_matches_camera_frame(std::numeric_limits<double>::quiet_NaN(), current_frame, current_frame));
  assert(pose_matches_camera_frame(current_frame, current_frame, current_frame));
  assert(pose_matches_camera_frame(next_frame, next_frame, next_frame));
}
