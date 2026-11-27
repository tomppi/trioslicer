#include <cassert>
#include <cmath>
#include <cstdio>
#include <string>

#include "../../main/cpp/live_tsdf.h"

int main() {
    using namespace livetsdf;
    const float voxel = 0.002f;  // 2 mm voxels
    const float trunc = 0.008f;
    NativeTsdfVolume vol(voxel, trunc, 4096, 2.0);

    // Exact signed distance of a sharp 24 mm axis-aligned cube centred at (0.020, 0.020, 0.020).
    // Faces sit at 0.008 and 0.032 (intentionally off-grid to exercise QEF sub-voxel snapping).
    const float cx = 0.0203f, cy = 0.0207f, cz = 0.0204f;
    const float half = 0.012f;
    for (int gz = 0; gz <= 20; ++gz) {
        for (int gy = 0; gy <= 20; ++gy) {
            for (int gx = 0; gx <= 20; ++gx) {
                float wx = (gx + 0.5f) * voxel;
                float wy = (gy + 0.5f) * voxel;
                float wz = (gz + 0.5f) * voxel;
                float dx = std::abs(wx - cx) - half;
                float dy = std::abs(wy - cy) - half;
                float dz = std::abs(wz - cz) - half;
                float outD = std::sqrt(std::max(dx, 0.0f) * std::max(dx, 0.0f) +
                                       std::max(dy, 0.0f) * std::max(dy, 0.0f) +
                                       std::max(dz, 0.0f) * std::max(dz, 0.0f));
                float inD = std::min(std::max(dx, std::max(dy, dz)), 0.0f);
                float sdf = outD + inD;
                if (std::abs(sdf) <= trunc) {
                    vol.setVoxel(gx, gy, gz, sdf / trunc, 10.0f, 10.0f, 200, 210, 220);
                }
            }
        }
    }

    ExtractedMesh dc = vol.extractDualContouringMesh();
    const ExtractedMesh& mc = vol.extractMesh();

    assert(!dc.verts.empty());
    assert(!dc.indices.empty());
    assert(dc.indices.size() % 3 == 0);

    // Compute signed volume (must be positive and close to 24^3 mm^3 = 13824 mm^3 = 1.3824e-5 m^3).
    double volDc = 0.0;
    for (size_t i = 0; i < dc.indices.size(); i += 3) {
        const GpuVertex& a = dc.verts[dc.indices[i]];
        const GpuVertex& b = dc.verts[dc.indices[i + 1]];
        const GpuVertex& c = dc.verts[dc.indices[i + 2]];
        volDc += (a.x * (b.y * c.z - b.z * c.y) +
                  a.y * (b.z * c.x - b.x * c.z) +
                  a.z * (b.x * c.y - b.y * c.x)) / 6.0;
    }
    const double trueVol = (2.0 * half) * (2.0 * half) * (2.0 * half);

    // Check closest vertex distance to the sharp 90-degree corner (cx + half, cy + half, cz + half).
    auto cornerDist = [&](const ExtractedMesh& m) {
        double best = 1e9;
        for (const auto& v : m.verts) {
            double d = std::sqrt((v.x - (cx + half)) * (v.x - (cx + half)) +
                                 (v.y - (cy + half)) * (v.y - (cy + half)) +
                                 (v.z - (cz + half)) * (v.z - (cz + half)));
            if (d < best) best = d;
        }
        return best;
    };
    const double dcCornerMm = cornerDist(dc) * 1000.0;
    const double mcCornerMm = cornerDist(mc) * 1000.0;

    std::printf("DC verts=%zu tris=%zu vol=%.6e (true=%.6e, relErr=%.2f%%) cornerErr=%.3fmm (MC=%.3fmm)\n",
                dc.verts.size(), dc.indices.size() / 3, volDc, trueVol,
                100.0 * std::abs(volDc - trueVol) / trueVol, dcCornerMm, mcCornerMm);

    assert(volDc > 0.0);
    assert(std::abs(volDc - trueVol) / trueVol < 0.08);
    // Dual Contouring must preserve the sharp 90-degree corner tighter than Marching Cubes.
    assert(dcCornerMm < mcCornerMm);
    assert(dcCornerMm < 1.0);

    int stlTris = vol.writeMeshStlDualContouring("/tmp/dc_test_cube.stl", true);
    int plyTris = vol.writeMeshPlyDualContouring("/tmp/dc_test_cube.ply", true);
    assert(stlTris == static_cast<int>(dc.indices.size() / 3));
    assert(plyTris == static_cast<int>(dc.indices.size() / 3));
    std::printf("PASS: dual_contouring_test\n");
    return 0;
}
