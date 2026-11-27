#include <cassert>
#include <cmath>
#include <iostream>
#include <string>

#include "supervins_config.h"
#include "supervins_frontend.h"

namespace {

bool near(double a, double b, double eps = 1e-6) {
  return std::fabs(a - b) <= eps;
}

void testKalibrImucamInversion() {
  const std::string yaml = R"(%YAML:1.0

cam0:
  T_cam_imu:
    - [-0.142869925, -0.978647950, 0.147771356, 0.010]
    - [-0.989709847, 0.142457611, -0.013425626, -0.020]
    - [-0.007912193, -0.148168884, -0.988930422, 0.005]
    - [0.0, 0.0, 0.0, 1.0]
  camera_model: pinhole
  distortion_coeffs: [0.100007, -0.121012, -0.005155, -0.001849]
  distortion_model: radtan
  intrinsics: [432.179802389, 431.795649509, 321.333984375, 242.097886030]
  resolution: [640, 480]
  timeshift_cam_imu: 0.014000
)";

  supervins::SuperVinsConfig cfg;
  assert(supervins::parseKalibrImucamYaml(yaml, cfg));
  assert(cfg.has_extrinsic);
  assert(near(cfg.fx, 432.179802389, 1e-6));
  assert(near(cfg.fy, 431.795649509, 1e-6));
  assert(near(cfg.cx, 321.333984375, 1e-6));
  assert(near(cfg.cy, 242.097886030, 1e-6));
  assert(near(cfg.k1, 0.100007, 1e-6));
  assert(near(cfg.k2, -0.121012, 1e-6));
  assert(near(cfg.p1, -0.005155, 1e-6));
  assert(near(cfg.p2, -0.001849, 1e-6));
  assert(near(cfg.td, 0.014, 1e-9));
  assert(cfg.width == 640 && cfg.height == 480);

  // Verify T_IC is the exact inverse of T_CI: R_CI * R_IC = I, R_CI * t_IC + t_CI = 0
  Eigen::Matrix3d R_CI;
  R_CI << -0.142869925, -0.978647950, 0.147771356,
          -0.989709847,  0.142457611, -0.013425626,
          -0.007912193, -0.148168884, -0.988930422;
  Eigen::Vector3d t_CI(0.010, -0.020, 0.005);

  assert((cfg.R_IC * cfg.R_IC.transpose() - Eigen::Matrix3d::Identity()).cwiseAbs().maxCoeff() < 1e-12);
  assert(near(cfg.R_IC.determinant(), 1.0, 1e-12));
  assert((R_CI * cfg.R_IC - Eigen::Matrix3d::Identity()).cwiseAbs().maxCoeff() < 1e-4);
  assert((R_CI * cfg.t_IC + t_CI).norm() < 1e-5);
}

void testImuNoiseConversion() {
  const std::string imu_yaml = R"(%YAML:1.0
imu0:
  accelerometer_noise_density: 6.0000e-4
  accelerometer_random_walk: 1.0000e-3
  gyroscope_noise_density: 1.4000e-04
  gyroscope_random_walk: 1.0000e-05
  update_rate: 480.0
)";
  supervins::SuperVinsConfig cfg;
  assert(supervins::parseKalibrImuYaml(imu_yaml, cfg));
  assert(cfg.acc_n >= 0.06 && cfg.acc_n <= 1.0);
  assert(cfg.gyr_n >= 0.003 && cfg.gyr_n <= 0.1);
  assert(near(cfg.acc_w, 0.001, 1e-9));
  assert(cfg.gyr_w >= 0.00005);
}

void testCameraPoseConversionMatchesEasyScanConvention() {
  // Body rotated 90 deg around Z, translated to (1, 2, 3) in world.
  Eigen::Matrix3d R_WI;
  R_WI << 0, -1, 0,
          1,  0, 0,
          0,  0, 1;
  Eigen::Vector3d p_IinW(1.0, 2.0, 3.0);

  // Camera rotated 180 deg around X relative to IMU, offset by (0.05, 0, 0) in IMU frame.
  Eigen::Matrix3d R_IC;
  R_IC << 1,  0,  0,
          0, -1,  0,
          0,  0, -1;
  Eigen::Vector3d t_IC(0.05, 0.0, 0.0);

  Eigen::Vector3d p_CinW;
  Eigen::Vector4d q_wxyz;
  supervins::superVinsBodyToEasyScanCamera(R_WI, p_IinW, R_IC, t_IC, p_CinW, q_wxyz);

  // Expected camera center in world: p_IinW + R_WI * t_IC = (1, 2.05, 3)
  assert((p_CinW - Eigen::Vector3d(1.0, 2.05, 3.0)).norm() < 1e-12);

  // Reconstruct R_WC from Hamilton quaternion [w, x, y, z] and check a test point.
  Eigen::Quaterniond q(q_wxyz(0), q_wxyz(1), q_wxyz(2), q_wxyz(3));
  Eigen::Matrix3d R_WC = q.toRotationMatrix();
  assert((R_WC - R_WI * R_IC).cwiseAbs().maxCoeff() < 1e-12);

  // Check world->cam transform matches VioRunner.cameraPoseAtFrame:
  // p_cam = R_WC^T * (p_world - p_CinW)
  Eigen::Vector3d p_world(2.0, 5.0, 1.0);
  Eigen::Vector3d p_cam_via_easyscan = R_WC.transpose() * (p_world - p_CinW);
  Eigen::Vector3d p_cam_via_chain = R_IC.transpose() * (R_WI.transpose() * (p_world - p_IinW) - t_IC);
  assert((p_cam_via_easyscan - p_cam_via_chain).norm() < 1e-12);
}

void testRadtanUndistortProjectRoundTrip() {
  supervins::CameraModel cam;
  cam.fx = 432.18; cam.fy = 431.80; cam.cx = 321.33; cam.cy = 242.10;
  cam.k1 = 0.100007; cam.k2 = -0.121012; cam.p1 = -0.005155; cam.p2 = -0.001849;

  for (double u : {40.0, 180.0, 320.0, 500.0, 600.0}) {
    for (double v : {40.0, 140.0, 240.0, 360.0, 440.0}) {
      Eigen::Vector2d un = cam.undistort(u, v);
      cv::Point2f reproj;
      assert(cam.project(Eigen::Vector3d(un.x(), un.y(), 1.0), reproj));
      assert(near(reproj.x, u, 1e-3));
      assert(near(reproj.y, v, 1e-3));
    }
  }
}

}  // namespace

int main() {
  testKalibrImucamInversion();
  testImuNoiseConversion();
  testCameraPoseConversionMatchesEasyScanConvention();
  testRadtanUndistortProjectRoundTrip();
  std::cout << "supervins_config_test: ALL PASSED\n";
  return 0;
}
