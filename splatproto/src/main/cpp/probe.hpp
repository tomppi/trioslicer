// Parallax: hardware discovery for the device report.
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <string>

namespace parallax {

// ISA extensions the kernel reports, e.g. "arm64 neon fp16 dotprod ... (ARMv8.2+)".
std::string cpuFeatures();
// The GPU behind the vendor OpenCL driver, or why it is not reachable.
std::string openclSummary();

}  // namespace parallax
