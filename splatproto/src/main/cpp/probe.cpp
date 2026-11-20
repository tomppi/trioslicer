// Parallax: CPU feature and OpenCL discovery for the device report.
// SPDX-License-Identifier: GPL-3.0-or-later
#include "probe.hpp"

#include <dlfcn.h>
#include <sys/auxv.h>

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#if defined(__aarch64__)
#include <asm/hwcap.h>
#endif

namespace parallax {

std::string cpuFeatures() {
#if defined(__aarch64__)
    // Kernel-reported ISA extensions (getauxval), the canonical runtime check on Linux/Android.
    const unsigned long hw = getauxval(AT_HWCAP);
    struct Bit { unsigned long mask; const char* name; };
    const Bit bits[] = {
        {HWCAP_ASIMD, "neon"},       {HWCAP_FPHP, "fp16"},          {HWCAP_ASIMDHP, "neon-fp16"},
        {HWCAP_ASIMDDP, "dotprod"},  {HWCAP_ATOMICS, "lse-atomics"}, {HWCAP_ASIMDRDM, "rdm"},
        {HWCAP_SVE, "sve"},          {HWCAP_AES, "aes"},            {HWCAP_CRC32, "crc32"},
    };
    std::string out = "arm64";
    for (const auto& b : bits)
        if (hw & b.mask) out += std::string(" ") + b.name;
    // ARMv8.2-A is the first level with dot product; without it the CPU is ARMv8.0/8.1.
    out += (hw & HWCAP_ASIMDDP) ? " (ARMv8.2+)" : " (ARMv8.0/8.1)";
    return out;
#elif defined(__x86_64__)
    return "x86-64";
#else
    return "unknown";
#endif
}

namespace {
using cl_int = int32_t;
using cl_uint = uint32_t;
using cl_ulong = uint64_t;
using PlatformIDs = cl_int (*)(cl_uint, void**, cl_uint*);
using DeviceIDs = cl_int (*)(void*, cl_ulong, cl_uint, void**, cl_uint*);
using DeviceInfo = cl_int (*)(void*, cl_uint, size_t, void*, size_t*);
constexpr cl_uint kDeviceName = 0x102B, kDeviceVersion = 0x102F, kComputeUnits = 0x1002, kGlobalMem = 0x101F;
constexpr cl_ulong kDeviceTypeGpu = 1 << 2;

std::string infoString(DeviceInfo f, void* dev, cl_uint key) {
    char buf[256] = {0};
    if (f(dev, key, sizeof buf - 1, buf, nullptr) != 0) return "?";
    return buf;
}
}  // namespace

std::string openclSummary() {
    // Android exposes vendor OpenCL only if the ROM lists libOpenCL.so in public.libraries.txt
    // and (targetSdk >= 31) the app declares <uses-native-library>. Either may be missing.
    void* h = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) h = dlopen("libOpenCL.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!h) return "not available to apps";
    auto getPlatforms = reinterpret_cast<PlatformIDs>(dlsym(h, "clGetPlatformIDs"));
    auto getDevices = reinterpret_cast<DeviceIDs>(dlsym(h, "clGetDeviceIDs"));
    auto getDeviceInfo = reinterpret_cast<DeviceInfo>(dlsym(h, "clGetDeviceInfo"));
    std::string out;
    if (!getPlatforms || !getDevices || !getDeviceInfo) {
        out = "library present, API incomplete";
    } else {
        cl_uint np = 0;
        void* platforms[4] = {nullptr};
        if (getPlatforms(4, platforms, &np) != 0 || np == 0) {
            out = "library present, no platform";
        } else {
            for (cl_uint p = 0; p < np && p < 4; ++p) {
                void* devs[4] = {nullptr};
                cl_uint nd = 0;
                if (getDevices(platforms[p], kDeviceTypeGpu, 4, devs, &nd) != 0) continue;
                for (cl_uint d = 0; d < nd && d < 4; ++d) {
                    cl_uint cu = 0;
                    cl_ulong mem = 0;
                    getDeviceInfo(devs[d], kComputeUnits, sizeof cu, &cu, nullptr);
                    getDeviceInfo(devs[d], kGlobalMem, sizeof mem, &mem, nullptr);
                    char line[512];
                    std::snprintf(line, sizeof line, "%s%s, %s, %u compute units, %llu MB", out.empty() ? "" : "; ",
                                  infoString(getDeviceInfo, devs[d], kDeviceName).c_str(),
                                  infoString(getDeviceInfo, devs[d], kDeviceVersion).c_str(), cu,
                                  static_cast<unsigned long long>(mem >> 20));
                    out += line;
                }
            }
            if (out.empty()) out = "library present, no GPU device";
        }
    }
    dlclose(h);
    return out;
}

}  // namespace parallax
