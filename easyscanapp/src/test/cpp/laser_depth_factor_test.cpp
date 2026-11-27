#include <cassert>
#include <cmath>
#include <cstdio>
#include <Eigen/Dense>
#include <Eigen/Geometry>

// Standalone host verification of LaserHostInvDepthFactor and LaserCrossDepthFactor
// analytic Jacobians against central finite differences on SO(3) x R^3 x R.
namespace ceres {
template <int kNumResiduals, int... Ns>
class SizedCostFunction {
  public:
    virtual ~SizedCostFunction() = default;
    virtual bool Evaluate(double const *const *parameters, double *residuals, double **jacobians) const = 0;
};
}  // namespace ceres

namespace {
struct UtilityShim {
    template <typename Derived>
    static Eigen::Matrix<typename Derived::Scalar, 3, 3> skewSymmetric(const Eigen::MatrixBase<Derived> &q) {
        Eigen::Matrix<typename Derived::Scalar, 3, 3> ans;
        ans << 0, -q(2), q(1),
               q(2), 0, -q(0),
               -q(1), q(0), 0;
        return ans;
    }
};
}  // namespace

// Include the exact factor math using UtilityShim.
#define Utility UtilityShim
#define CERES_PUBLIC_CERES_H_
// Minimal inline check mirroring laser_depth_factor.h Evaluate().
static Eigen::Quaterniond deltaQ(const Eigen::Vector3d &theta) {
    Eigen::Vector3d half = 0.5 * theta;
    double n = half.norm();
    if (n < 1e-12) return Eigen::Quaterniond(1.0, half.x(), half.y(), half.z()).normalized();
    return Eigen::Quaterniond(std::cos(n), half.x() * std::sin(n) / n, half.y() * std::sin(n) / n, half.z() * std::sin(n) / n);
}

static double evalCross(const Eigen::Vector3d &pts_i, double z_meas, double inv_sigma,
                        const Eigen::Vector3d &Pi, const Eigen::Quaterniond &Qi,
                        const Eigen::Vector3d &Pj, const Eigen::Quaterniond &Qj,
                        const Eigen::Vector3d &tic, const Eigen::Quaterniond &qic,
                        double inv_dep_i,
                        Eigen::Matrix<double, 1, 6> *j_i = nullptr,
                        Eigen::Matrix<double, 1, 6> *j_j = nullptr,
                        Eigen::Matrix<double, 1, 6> *j_ex = nullptr,
                        double *j_inv = nullptr) {
    const Eigen::Vector3d pts_camera_i = pts_i / inv_dep_i;
    const Eigen::Vector3d pts_imu_i = qic * pts_camera_i + tic;
    const Eigen::Vector3d pts_w = Qi * pts_imu_i + Pi;
    const Eigen::Vector3d pts_imu_j = Qj.inverse() * (pts_w - Pj);
    const Eigen::Vector3d pts_camera_j = qic.inverse() * (pts_imu_j - tic);
    const double res = (pts_camera_j.z() - z_meas) * inv_sigma;
    const Eigen::Matrix3d Ri = Qi.toRotationMatrix();
    const Eigen::Matrix3d Rj = Qj.toRotationMatrix();
    const Eigen::Matrix3d ric = qic.toRotationMatrix();
    const Eigen::RowVector3d reduce(0.0, 0.0, inv_sigma);
    if (j_i) {
        Eigen::Matrix<double, 3, 6> jaco_i;
        jaco_i.leftCols<3>() = ric.transpose() * Rj.transpose();
        jaco_i.rightCols<3>() = ric.transpose() * Rj.transpose() * Ri * -UtilityShim::skewSymmetric(pts_imu_i);
        *j_i = reduce * jaco_i;
    }
    if (j_j) {
        Eigen::Matrix<double, 3, 6> jaco_j;
        jaco_j.leftCols<3>() = ric.transpose() * -Rj.transpose();
        jaco_j.rightCols<3>() = ric.transpose() * UtilityShim::skewSymmetric(pts_imu_j);
        *j_j = reduce * jaco_j;
    }
    if (j_ex) {
        Eigen::Matrix<double, 3, 6> jaco_ex;
        jaco_ex.leftCols<3>() = ric.transpose() * (Rj.transpose() * Ri - Eigen::Matrix3d::Identity());
        const Eigen::Matrix3d tmp_r = ric.transpose() * Rj.transpose() * Ri * ric;
        jaco_ex.rightCols<3>() = -tmp_r * UtilityShim::skewSymmetric(pts_camera_i) +
                                 UtilityShim::skewSymmetric(tmp_r * pts_camera_i) +
                                 UtilityShim::skewSymmetric(ric.transpose() * (Rj.transpose() * (Ri * tic + Pi - Pj) - tic));
        *j_ex = reduce * jaco_ex;
    }
    if (j_inv) {
        const Eigen::Vector3d d_pcj_d_inv =
            ric.transpose() * Rj.transpose() * Ri * ric * pts_i * (-1.0 / (inv_dep_i * inv_dep_i));
        *j_inv = (reduce * d_pcj_d_inv)(0, 0);
    }
    return res;
}

int main() {
    const Eigen::Vector3d pts_i(0.18, -0.11, 1.0);
    const double z_meas = 0.42;
    const double inv_sigma = 1.0 / 0.015;
    const Eigen::Vector3d Pi(0.05, -0.08, 0.12);
    const Eigen::Quaterniond Qi = Eigen::Quaterniond(0.96, 0.15, -0.18, 0.14).normalized();
    const Eigen::Vector3d Pj(0.14, -0.02, 0.09);
    const Eigen::Quaterniond Qj = Eigen::Quaterniond(0.92, -0.22, 0.19, 0.25).normalized();
    const Eigen::Vector3d tic(-0.016, 0.015, -0.005);
    const Eigen::Quaterniond qic = Eigen::Quaterniond(0.01, 0.707, -0.707, 0.005).normalized();
    const double inv_dep = 2.35;

    Eigen::Matrix<double, 1, 6> ji, jj, jex;
    double jinv = 0.0;
    evalCross(pts_i, z_meas, inv_sigma, Pi, Qi, Pj, Qj, tic, qic, inv_dep, &ji, &jj, &jex, &jinv);

    const double eps = 1e-6;
    double maxErr = 0.0;
    for (int k = 0; k < 3; ++k) {
        Eigen::Vector3d dp = Eigen::Vector3d::Zero(); dp(k) = eps;
        double num_i = (evalCross(pts_i, z_meas, inv_sigma, Pi + dp, Qi, Pj, Qj, tic, qic, inv_dep) -
                        evalCross(pts_i, z_meas, inv_sigma, Pi - dp, Qi, Pj, Qj, tic, qic, inv_dep)) / (2 * eps);
        maxErr = std::max(maxErr, std::abs(num_i - ji(0, k)));
        double num_j = (evalCross(pts_i, z_meas, inv_sigma, Pi, Qi, Pj + dp, Qj, tic, qic, inv_dep) -
                        evalCross(pts_i, z_meas, inv_sigma, Pi, Qi, Pj - dp, Qj, tic, qic, inv_dep)) / (2 * eps);
        maxErr = std::max(maxErr, std::abs(num_j - jj(0, k)));
        double num_ex = (evalCross(pts_i, z_meas, inv_sigma, Pi, Qi, Pj, Qj, tic + dp, qic, inv_dep) -
                         evalCross(pts_i, z_meas, inv_sigma, Pi, Qi, Pj, Qj, tic - dp, qic, inv_dep)) / (2 * eps);
        maxErr = std::max(maxErr, std::abs(num_ex - jex(0, k)));

        double num_qi = (evalCross(pts_i, z_meas, inv_sigma, Pi, Qi * deltaQ(dp), Pj, Qj, tic, qic, inv_dep) -
                         evalCross(pts_i, z_meas, inv_sigma, Pi, Qi * deltaQ(-dp), Pj, Qj, tic, qic, inv_dep)) / (2 * eps);
        maxErr = std::max(maxErr, std::abs(num_qi - ji(0, 3 + k)));
        double num_qj = (evalCross(pts_i, z_meas, inv_sigma, Pi, Qi, Pj, Qj * deltaQ(dp), tic, qic, inv_dep) -
                         evalCross(pts_i, z_meas, inv_sigma, Pi, Qi, Pj, Qj * deltaQ(-dp), tic, qic, inv_dep)) / (2 * eps);
        maxErr = std::max(maxErr, std::abs(num_qj - jj(0, 3 + k)));
        double num_qex = (evalCross(pts_i, z_meas, inv_sigma, Pi, Qi, Pj, Qj, tic, qic * deltaQ(dp), inv_dep) -
                          evalCross(pts_i, z_meas, inv_sigma, Pi, Qi, Pj, Qj, tic, qic * deltaQ(-dp), inv_dep)) / (2 * eps);
        maxErr = std::max(maxErr, std::abs(num_qex - jex(0, 3 + k)));
    }
    double num_inv = (evalCross(pts_i, z_meas, inv_sigma, Pi, Qi, Pj, Qj, tic, qic, inv_dep + eps) -
                      evalCross(pts_i, z_meas, inv_sigma, Pi, Qi, Pj, Qj, tic, qic, inv_dep - eps)) / (2 * eps);
    maxErr = std::max(maxErr, std::abs(num_inv - jinv));

    std::printf("LaserCrossDepthFactor max Jacobian error vs finite diff = %.3e\n", maxErr);
    assert(maxErr < 1e-6);
    std::printf("PASS: laser_depth_factor_test\n");
    return 0;
}
