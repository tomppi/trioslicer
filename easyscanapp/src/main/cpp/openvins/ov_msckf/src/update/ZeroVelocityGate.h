#pragma once

#include <cmath>

namespace ov_msckf {

enum class ZeroVelocityDecision { SpeedLimit, VisualMotion, InvalidInertialResidual, NoStillness, VisualStillness, ImuStillness };

inline bool zero_velocity_accepted(ZeroVelocityDecision decision) {
  return decision == ZeroVelocityDecision::VisualStillness || decision == ZeroVelocityDecision::ImuStillness;
}

// A quiet IMU cannot distinguish constant-speed translation from rest. Trust measured
// image motion when enough features are visible in both frames, not when disparity is unavailable.
inline ZeroVelocityDecision zero_velocity_decision(double speed, double speed_limit, double chi2, double chi2_limit,
                                                   double disparity, double disparity_limit, int common_tracks) {
  if (!std::isfinite(speed) || !(speed <= speed_limit)) return ZeroVelocityDecision::SpeedLimit;
  const bool visual_available = std::isfinite(disparity_limit) && disparity_limit > 0.0 && common_tracks > 20 &&
                                std::isfinite(disparity) && disparity >= 0.0;
  if (visual_available && disparity >= disparity_limit) return ZeroVelocityDecision::VisualMotion;
  if (!std::isfinite(chi2) || chi2 < 0.0) return ZeroVelocityDecision::InvalidInertialResidual;
  if (visual_available) return ZeroVelocityDecision::VisualStillness;
  if (std::isfinite(chi2_limit) && chi2_limit > 0.0 && chi2 <= chi2_limit) return ZeroVelocityDecision::ImuStillness;
  return ZeroVelocityDecision::NoStillness;
}

} // namespace ov_msckf
