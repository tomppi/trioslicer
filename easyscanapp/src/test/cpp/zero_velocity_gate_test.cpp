#include "ZeroVelocityGate.h"

#include <cassert>
#include <initializer_list>
#include <limits>

int main() {
  using ov_msckf::ZeroVelocityDecision;
  using ov_msckf::zero_velocity_accepted;
  using ov_msckf::zero_velocity_decision;
  using D = ZeroVelocityDecision;

  // Fold log at 22:15:30.385: strong visual motion despite a passing inertial test.
  const auto observed = zero_velocity_decision(0.445, 0.5, 56.777, 84.595, 17.534, 1.5, 124);
  assert(observed == D::VisualMotion && !zero_velocity_accepted(observed));
  assert(zero_velocity_decision(0.1, 0.5, 3.0, 8.0, 1.5, 1.5, 21) == D::VisualMotion);
  assert(zero_velocity_decision(0.1, 0.5, 3.0, 8.0, 17.5, 1.5, 20) == D::ImuStillness);

  // Preserve stationary updates and the existing velocity cap.
  const auto still = zero_velocity_decision(0.02, 0.5, 9.0, 8.0, 0.2, 1.5, 124);
  assert(still == D::VisualStillness && zero_velocity_accepted(still));
  assert(zero_velocity_decision(0.6, 0.5, 3.0, 8.0, 0.2, 1.5, 124) == D::SpeedLimit);

  // Missing, too few or invalid tracks cannot veto or confirm stillness.
  const double nan = std::numeric_limits<double>::quiet_NaN();
  const double inf = std::numeric_limits<double>::infinity();
  for (double disparity : {-1.0, nan, inf}) {
    assert(zero_velocity_decision(0.1, 0.5, 3.0, 8.0, disparity, 1.5, 124) == D::ImuStillness);
    assert(zero_velocity_decision(0.1, 0.5, 9.0, 8.0, disparity, 1.5, 124) == D::NoStillness);
  }
  assert(zero_velocity_decision(0.1, 0.5, 9.0, 8.0, 0.2, 1.5, 0) == D::NoStillness);
  assert(zero_velocity_decision(0.1, 0.5, nan, 8.0, 0.2, 1.5, 124) == D::InvalidInertialResidual);

  // Zero disables the corresponding detector, even for an exact zero measurement.
  assert(zero_velocity_decision(0.1, 0.5, 3.0, 8.0, 17.5, 0.0, 124) == D::ImuStillness);
  assert(zero_velocity_decision(0.1, 0.5, 9.0, 0.0, 0.2, 1.5, 124) == D::VisualStillness);
  assert(zero_velocity_decision(0.1, 0.5, 0.0, 0.0, 0.2, 1.5, 0) == D::NoStillness);
  assert(zero_velocity_decision(0.1, 0.5, 0.0, 0.0, 0.0, 0.0, 124) == D::NoStillness);
}
