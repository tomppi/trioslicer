#pragma once

#include <algorithm>
#include <cmath>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include <Eigen/Dense>

namespace supervins {

struct SuperVinsConfig {
  // Camera intrinsics & Brown-Conrady distortion (640x480)
  double fx = 432.18;
  double fy = 431.80;
  double cx = 321.33;
  double cy = 242.10;
  double k1 = 0.0;
  double k2 = 0.0;
  double p1 = 0.0;
  double p2 = 0.0;
  int width = 640;
  int height = 480;

  // Extrinsic: body_T_cam0 = T_imu_cam = inv(T_cam_imu)
  // p_imu = R_IC * p_cam + t_IC
  Eigen::Matrix3d R_IC = Eigen::Matrix3d::Identity();
  Eigen::Vector3d t_IC = Eigen::Vector3d::Zero();
  bool has_extrinsic = false;

  // Time offset: t_imu = t_cam + td (same sign as Kalibr timeshift_cam_imu)
  double td = 0.014;
  int estimate_td = 1;
  int estimate_extrinsic = 0;

  // IMU noise (VINS-Fusion discrete-time parameters for IntegrationBase)
  double acc_n = 0.08;
  double acc_w = 0.001;
  double gyr_n = 0.004;
  double gyr_w = 0.0001;
  double g_norm = 9.81;

  // Sliding-window solver & front-end parameters
  double max_solver_time = 0.04;
  int max_num_iterations = 8;
  double keyframe_parallax = 10.0;
  int max_cnt = 100;
  int min_dist = 20;
  double ransac_reproj_px = 3.0;
  float matcher_threshold = 0.15f;
};

namespace detail {

inline std::string trim(const std::string &s) {
  const auto a = s.find_first_not_of(" \t\r\n");
  if (a == std::string::npos) return "";
  const auto b = s.find_last_not_of(" \t\r\n");
  return s.substr(a, b - a + 1);
}

inline std::string stripComment(const std::string &s) {
  const auto pos = s.find('#');
  return pos == std::string::npos ? s : s.substr(0, pos);
}

inline std::vector<double> parseBracketList(const std::string &s) {
  std::vector<double> out;
  const auto lb = s.find('[');
  const auto rb = s.find(']', lb == std::string::npos ? 0 : lb);
  if (lb == std::string::npos || rb == std::string::npos || rb <= lb) return out;
  std::string body = s.substr(lb + 1, rb - lb - 1);
  std::replace(body.begin(), body.end(), ',', ' ');
  std::istringstream iss(body);
  double v = 0.0;
  while (iss >> v) out.push_back(v);
  return out;
}

inline bool parseScalarAfterColon(const std::string &line, const std::string &key, double &out) {
  const std::string clean = trim(stripComment(line));
  if (clean.rfind(key + ":", 0) != 0) return false;
  std::string val = trim(clean.substr(key.size() + 1));
  if (val.empty()) return false;
  try {
    size_t idx = 0;
    double v = std::stod(val, &idx);
    if (idx > 0 && std::isfinite(v)) {
      out = v;
      return true;
    }
  } catch (...) {
  }
  return false;
}

}  // namespace detail

/// Invert Kalibr T_cam_imu (IMU -> Cam) into SuperVINS body_T_cam0 (Cam -> IMU).
inline bool invertKalibrTcamImu(const Eigen::Matrix4d &T_cam_imu, Eigen::Matrix3d &R_IC, Eigen::Vector3d &t_IC) {
  const Eigen::Matrix3d R_CI = T_cam_imu.block<3, 3>(0, 0);
  const Eigen::Vector3d t_CI = T_cam_imu.block<3, 1>(0, 3);
  if (!R_CI.allFinite() || !t_CI.allFinite()) return false;
  const double err = (R_CI * R_CI.transpose() - Eigen::Matrix3d::Identity()).cwiseAbs().maxCoeff();
  const double det = R_CI.determinant();
  if (err > 0.05 || std::fabs(det - 1.0) > 0.05) return false;
  // Project to nearest SO(3) via SVD to eliminate YAML rounding drift.
  Eigen::JacobiSVD<Eigen::Matrix3d> svd(R_CI, Eigen::ComputeFullU | Eigen::ComputeFullV);
  Eigen::Matrix3d R_ortho = svd.matrixU() * svd.matrixV().transpose();
  if (R_ortho.determinant() < 0.0) {
    Eigen::Matrix3d U = svd.matrixU();
    U.col(2) *= -1.0;
    R_ortho = U * svd.matrixV().transpose();
  }
  R_IC = R_ortho.transpose();
  t_IC = -R_IC * t_CI;
  return true;
}

/// Convert Kalibr continuous-time Allan noise densities into VINS-Fusion IntegrationBase noise params.
/// IntegrationBase integrates discrete covariance as V * noise * V^T (scaling by dt^2 rather than dt),
/// so raw continuous density sigma_c [unit/sqrt(Hz)] must be scaled by sqrt(update_rate) plus
/// smartphone vibration/non-linearity margin.
inline void convertKalibrImuNoiseToSuperVins(
    double acc_nd, double acc_rw, double gyr_nd, double gyr_rw, double update_rate_hz,
    double &acc_n_out, double &acc_w_out, double &gyr_n_out, double &gyr_w_out) {
  const double rate = std::max(50.0, std::min(2000.0, update_rate_hz > 0.0 ? update_rate_hz : 400.0));
  const double root_f = std::sqrt(rate);
  // 3x margin over discrete white-noise floor prevents overconfident IMU weighting on handheld phones.
  acc_n_out = std::max(0.06, std::min(1.0, acc_nd * root_f * 3.0));
  gyr_n_out = std::max(0.003, std::min(0.1, gyr_nd * root_f * 2.0));
  acc_w_out = std::max(0.0004, std::min(0.05, acc_rw));
  gyr_w_out = std::max(0.00005, std::min(0.01, gyr_rw));
}

/// Parse kalibr_imucam_chain.yaml text into SuperVinsConfig (camera model, extrinsic, timeshift).
inline bool parseKalibrImucamYaml(const std::string &text, SuperVinsConfig &cfg) {
  std::istringstream iss(text);
  std::string raw;
  bool in_pose = false;
  std::vector<std::vector<double>> pose_rows;

  while (std::getline(iss, raw)) {
    const std::string clean = detail::trim(detail::stripComment(raw));
    if (clean.empty()) continue;

    if (clean.rfind("T_cam_imu:", 0) == 0) {
      in_pose = true;
      pose_rows.clear();
      continue;
    }
    if (in_pose) {
      if (clean.rfind("- [", 0) == 0) {
        auto vals = detail::parseBracketList(clean);
        if (vals.size() >= 4) pose_rows.push_back(vals);
        if (pose_rows.size() == 4) in_pose = false;
        continue;
      } else {
        in_pose = false;
      }
    }

    if (clean.rfind("intrinsics:", 0) == 0) {
      auto v = detail::parseBracketList(clean);
      if (v.size() >= 4 && v[0] > 1.0 && v[1] > 1.0) {
        cfg.fx = v[0]; cfg.fy = v[1]; cfg.cx = v[2]; cfg.cy = v[3];
      }
    } else if (clean.rfind("distortion_coeffs:", 0) == 0) {
      auto v = detail::parseBracketList(clean);
      if (v.size() >= 4) {
        cfg.k1 = v[0]; cfg.k2 = v[1]; cfg.p1 = v[2]; cfg.p2 = v[3];
      }
    } else if (clean.rfind("resolution:", 0) == 0) {
      auto v = detail::parseBracketList(clean);
      if (v.size() >= 2 && v[0] > 0 && v[1] > 0) {
        cfg.width = (int)std::lround(v[0]);
        cfg.height = (int)std::lround(v[1]);
      }
    } else {
      double td = 0.0;
      if (detail::parseScalarAfterColon(clean, "timeshift_cam_imu", td)) {
        if (std::fabs(td) <= 0.2) cfg.td = td;
      }
    }
  }

  if (pose_rows.size() >= 3) {
    Eigen::Matrix4d T = Eigen::Matrix4d::Identity();
    for (int r = 0; r < 3; ++r) {
      for (int c = 0; c < 4; ++c) T(r, c) = pose_rows[r][c];
    }
    if (invertKalibrTcamImu(T, cfg.R_IC, cfg.t_IC)) {
      cfg.has_extrinsic = true;
    }
  }
  return cfg.has_extrinsic;
}

/// Parse kalibr_imu_chain.yaml text into SuperVinsConfig (noise parameters).
inline bool parseKalibrImuYaml(const std::string &text, SuperVinsConfig &cfg) {
  std::istringstream iss(text);
  std::string raw;
  double acc_nd = -1.0, acc_rw = -1.0, gyr_nd = -1.0, gyr_rw = -1.0, rate = 480.0;
  while (std::getline(iss, raw)) {
    detail::parseScalarAfterColon(raw, "accelerometer_noise_density", acc_nd);
    detail::parseScalarAfterColon(raw, "accelerometer_random_walk", acc_rw);
    detail::parseScalarAfterColon(raw, "gyroscope_noise_density", gyr_nd);
    detail::parseScalarAfterColon(raw, "gyroscope_random_walk", gyr_rw);
    detail::parseScalarAfterColon(raw, "update_rate", rate);
  }
  if (acc_nd > 0.0 && acc_rw > 0.0 && gyr_nd > 0.0 && gyr_rw > 0.0) {
    convertKalibrImuNoiseToSuperVins(acc_nd, acc_rw, gyr_nd, gyr_rw, rate,
                                     cfg.acc_n, cfg.acc_w, cfg.gyr_n, cfg.gyr_w);
    return true;
  }
  return false;
}

/// Parse optional supervins_config.yaml overrides.
inline bool parseSuperVinsYaml(const std::string &text, SuperVinsConfig &cfg) {
  std::istringstream iss(text);
  std::string raw;
  double v = 0.0;
  bool any = false;
  while (std::getline(iss, raw)) {
    if (detail::parseScalarAfterColon(raw, "max_solver_time", v) && v > 0.005) { cfg.max_solver_time = v; any = true; }
    if (detail::parseScalarAfterColon(raw, "max_num_iterations", v) && v >= 1) { cfg.max_num_iterations = (int)v; any = true; }
    if (detail::parseScalarAfterColon(raw, "keyframe_parallax", v) && v > 0.5) { cfg.keyframe_parallax = v; any = true; }
    if (detail::parseScalarAfterColon(raw, "estimate_td", v)) { cfg.estimate_td = (int)v; any = true; }
    if (detail::parseScalarAfterColon(raw, "estimate_extrinsic", v)) { cfg.estimate_extrinsic = (int)v; any = true; }
    if (detail::parseScalarAfterColon(raw, "g_norm", v) && v > 9.0 && v < 10.5) { cfg.g_norm = v; any = true; }
    if (detail::parseScalarAfterColon(raw, "max_cnt", v) && v >= 20) { cfg.max_cnt = (int)v; any = true; }
    if (detail::parseScalarAfterColon(raw, "min_dist", v) && v >= 2.0) { cfg.min_dist = (int)v; any = true; }
    if (detail::parseScalarAfterColon(raw, "ransacReprojThreshold", v) && v >= 0.5) { cfg.ransac_reproj_px = v; any = true; }
    if (detail::parseScalarAfterColon(raw, "matche_score_threshold", v) && v > 0.0 && v < 1.0) { cfg.matcher_threshold = (float)v; any = true; }
  }
  return any;
}

inline bool readTextFile(const std::string &path, std::string &out) {
  std::ifstream ifs(path);
  if (!ifs.is_open()) return false;
  std::ostringstream oss;
  oss << ifs.rdbuf();
  out = oss.str();
  return !out.empty();
}

/// Load complete SuperVinsConfig from EasyScanner's calibration directory.
inline bool loadSuperVinsConfigFromDir(const std::string &config_dir, SuperVinsConfig &cfg) {
  std::string txt;
  if (readTextFile(config_dir + "/supervins_config.yaml", txt)) {
    parseSuperVinsYaml(txt, cfg);
  }
  if (readTextFile(config_dir + "/kalibr_imu_chain.yaml", txt)) {
    parseKalibrImuYaml(txt, cfg);
  }
  if (!readTextFile(config_dir + "/kalibr_imucam_chain.yaml", txt)) {
    return false;
  }
  return parseKalibrImucamYaml(txt, cfg);
}

/// Convert SuperVINS body-in-world (R_WI, p_IinW) + camera-in-body (R_IC, t_IC)
/// to EasyScanner's expected camera-in-world position and Hamilton [w, x, y, z] quaternion.
inline void superVinsBodyToEasyScanCamera(
    const Eigen::Matrix3d &R_WI, const Eigen::Vector3d &p_IinW,
    const Eigen::Matrix3d &R_IC, const Eigen::Vector3d &t_IC,
    Eigen::Vector3d &p_CinW, Eigen::Vector4d &q_CtoW_wxyz) {
  const Eigen::Matrix3d R_WC = R_WI * R_IC;
  p_CinW = p_IinW + R_WI * t_IC;
  Eigen::Quaterniond q(R_WC);
  q.normalize();
  q_CtoW_wxyz << q.w(), q.x(), q.y(), q.z();
}

}  // namespace supervins
