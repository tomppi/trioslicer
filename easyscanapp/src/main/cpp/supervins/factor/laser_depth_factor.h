#pragma once

#include <ceres/ceres.h>
#include <Eigen/Dense>
#include <cmath>
#include "../utility/utility.h"

/**
 * Unary calibrated laser inverse-depth factor on a feature's host frame (j == start_frame).
 * Locks monocular VIO inverse depth directly to millimeter-accurate laser triangulation.
 */
class LaserHostInvDepthFactor : public ceres::SizedCostFunction<1, 1> {
  public:
    LaserHostInvDepthFactor(double inv_depth_laser, double sigma_inv_depth)
        : inv_dep_meas_(inv_depth_laser), inv_sigma_(1.0 / std::max(1e-5, sigma_inv_depth)) {}

    bool Evaluate(double const *const *parameters, double *residuals, double **jacobians) const override {
        const double inv_dep = parameters[0][0];
        residuals[0] = (inv_dep - inv_dep_meas_) * inv_sigma_;
        if (jacobians && jacobians[0]) {
            jacobians[0][0] = inv_sigma_;
        }
        return true;
    }

  private:
    double inv_dep_meas_;
    double inv_sigma_;
};

/**
 * Tightly-coupled cross-frame calibrated laser depth factor (j != start_frame).
 *
 * Ties host pose (Pi, Qi), target frame pose (Pj, Qj), extrinsic (tic, qic), and landmark
 * inverse depth inv_dep_i directly to the calibrated metric laser depth z_laser observed at
 * frame j. Because this residual depends explicitly on (Pi - Pj) and inv_dep_i, Ceres solves
 * for true millimeter translation scale inside the sliding window.
 */
class LaserCrossDepthFactor : public ceres::SizedCostFunction<1, 7, 7, 7, 1> {
  public:
    LaserCrossDepthFactor(const Eigen::Vector3d &pts_i, double z_laser_j, double sigma_z)
        : pts_i_(pts_i), z_meas_(z_laser_j), inv_sigma_(1.0 / std::max(1e-4, sigma_z)) {}

    bool Evaluate(double const *const *parameters, double *residuals, double **jacobians) const override {
        Eigen::Vector3d Pi(parameters[0][0], parameters[0][1], parameters[0][2]);
        Eigen::Quaterniond Qi(parameters[0][6], parameters[0][3], parameters[0][4], parameters[0][5]);

        Eigen::Vector3d Pj(parameters[1][0], parameters[1][1], parameters[1][2]);
        Eigen::Quaterniond Qj(parameters[1][6], parameters[1][3], parameters[1][4], parameters[1][5]);

        Eigen::Vector3d tic(parameters[2][0], parameters[2][1], parameters[2][2]);
        Eigen::Quaterniond qic(parameters[2][6], parameters[2][3], parameters[2][4], parameters[2][5]);

        const double inv_dep_i = std::max(1e-4, parameters[3][0]);

        const Eigen::Vector3d pts_camera_i = pts_i_ / inv_dep_i;
        const Eigen::Vector3d pts_imu_i = qic * pts_camera_i + tic;
        const Eigen::Vector3d pts_w = Qi * pts_imu_i + Pi;
        const Eigen::Vector3d pts_imu_j = Qj.inverse() * (pts_w - Pj);
        const Eigen::Vector3d pts_camera_j = qic.inverse() * (pts_imu_j - tic);

        residuals[0] = (pts_camera_j.z() - z_meas_) * inv_sigma_;

        if (jacobians) {
            const Eigen::Matrix3d Ri = Qi.toRotationMatrix();
            const Eigen::Matrix3d Rj = Qj.toRotationMatrix();
            const Eigen::Matrix3d ric = qic.toRotationMatrix();
            const Eigen::RowVector3d reduce(0.0, 0.0, inv_sigma_);

            if (jacobians[0]) {
                Eigen::Map<Eigen::Matrix<double, 1, 7, Eigen::RowMajor>> j_pose_i(jacobians[0]);
                Eigen::Matrix<double, 3, 6> jaco_i;
                jaco_i.leftCols<3>() = ric.transpose() * Rj.transpose();
                jaco_i.rightCols<3>() = ric.transpose() * Rj.transpose() * Ri * -Utility::skewSymmetric(pts_imu_i);
                j_pose_i.leftCols<6>() = reduce * jaco_i;
                j_pose_i(0, 6) = 0.0;
            }
            if (jacobians[1]) {
                Eigen::Map<Eigen::Matrix<double, 1, 7, Eigen::RowMajor>> j_pose_j(jacobians[1]);
                Eigen::Matrix<double, 3, 6> jaco_j;
                jaco_j.leftCols<3>() = ric.transpose() * -Rj.transpose();
                jaco_j.rightCols<3>() = ric.transpose() * Utility::skewSymmetric(pts_imu_j);
                j_pose_j.leftCols<6>() = reduce * jaco_j;
                j_pose_j(0, 6) = 0.0;
            }
            if (jacobians[2]) {
                Eigen::Map<Eigen::Matrix<double, 1, 7, Eigen::RowMajor>> j_ex(jacobians[2]);
                Eigen::Matrix<double, 3, 6> jaco_ex;
                jaco_ex.leftCols<3>() = ric.transpose() * (Rj.transpose() * Ri - Eigen::Matrix3d::Identity());
                const Eigen::Matrix3d tmp_r = ric.transpose() * Rj.transpose() * Ri * ric;
                jaco_ex.rightCols<3>() = -tmp_r * Utility::skewSymmetric(pts_camera_i) +
                                         Utility::skewSymmetric(tmp_r * pts_camera_i) +
                                         Utility::skewSymmetric(ric.transpose() * (Rj.transpose() * (Ri * tic + Pi - Pj) - tic));
                j_ex.leftCols<6>() = reduce * jaco_ex;
                j_ex(0, 6) = 0.0;
            }
            if (jacobians[3]) {
                const Eigen::Vector3d d_pcj_d_inv =
                    ric.transpose() * Rj.transpose() * Ri * ric * pts_i_ * (-1.0 / (inv_dep_i * inv_dep_i));
                jacobians[3][0] = (reduce * d_pcj_d_inv)(0, 0);
            }
        }
        return true;
    }

  private:
    Eigen::Vector3d pts_i_;
    double z_meas_;
    double inv_sigma_;
};
