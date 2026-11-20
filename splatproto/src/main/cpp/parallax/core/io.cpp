// Parallax: PLY and COLMAP text-model export.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <unordered_map>

#include "internal.hpp"

namespace parallax {

bool writeText(const std::string& path, const std::string& text) {
    std::ofstream f(path, std::ios::binary);
    f << text;
    return bool(f);
}

// Binary little-endian PLY: float x y z nx ny nz, uchar red green blue (27 bytes per vertex).
bool writePly(const std::string& path, const std::vector<DensePoint>& pts) {
    std::ofstream f(path, std::ios::binary);
    if (!f) return false;
    f << "ply\nformat binary_little_endian 1.0\ncomment Parallax SfM scanner\n"
      << "element vertex " << pts.size() << "\n"
      << "property float x\nproperty float y\nproperty float z\n"
      << "property float nx\nproperty float ny\nproperty float nz\n"
      << "property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n";
    std::vector<char> buf(pts.size() * 27);
    char* w = buf.data();
    for (const auto& p : pts) {
        std::memcpy(w, &p.X[0], 12);
        std::memcpy(w + 12, &p.n[0], 12);
        w[24] = char(p.rgb[0]);
        w[25] = char(p.rgb[1]);
        w[26] = char(p.rgb[2]);
        w += 27;
    }
    f.write(buf.data(), std::streamsize(buf.size()));
    return bool(f);
}

static void rotationToQuaternion(const cv::Matx33d& R, double q[4]) {
    // Shepperd's method; q = (w, x, y, z), as COLMAP stores it.
    const double tr = R(0, 0) + R(1, 1) + R(2, 2);
    if (tr > 0) {
        const double s = std::sqrt(tr + 1.0) * 2;
        q[0] = 0.25 * s; q[1] = (R(2, 1) - R(1, 2)) / s; q[2] = (R(0, 2) - R(2, 0)) / s; q[3] = (R(1, 0) - R(0, 1)) / s;
    } else if (R(0, 0) > R(1, 1) && R(0, 0) > R(2, 2)) {
        const double s = std::sqrt(1.0 + R(0, 0) - R(1, 1) - R(2, 2)) * 2;
        q[0] = (R(2, 1) - R(1, 2)) / s; q[1] = 0.25 * s; q[2] = (R(0, 1) + R(1, 0)) / s; q[3] = (R(0, 2) + R(2, 0)) / s;
    } else if (R(1, 1) > R(2, 2)) {
        const double s = std::sqrt(1.0 + R(1, 1) - R(0, 0) - R(2, 2)) * 2;
        q[0] = (R(0, 2) - R(2, 0)) / s; q[1] = (R(0, 1) + R(1, 0)) / s; q[2] = 0.25 * s; q[3] = (R(1, 2) + R(2, 1)) / s;
    } else {
        const double s = std::sqrt(1.0 + R(2, 2) - R(0, 0) - R(1, 1)) * 2;
        q[0] = (R(1, 0) - R(0, 1)) / s; q[1] = (R(0, 2) + R(2, 0)) / s; q[2] = (R(1, 2) + R(2, 1)) / s; q[3] = 0.25 * s;
    }
}

// COLMAP text model (https://colmap.github.io/format.html), OPENCV camera model, 1-based ids.
// COLMAP puts the centre of the top-left pixel at (0.5, 0.5); OpenCV puts it at (0, 0).
bool writeColmap(const State& s, const std::string& dir, const std::string& imageRoot) {
    std::error_code ec;
    std::filesystem::create_directories(dir, ec);
    char line[512];

    std::ofstream cams(dir + "/cameras.txt");
    cams << "# Camera list with one line of data per camera:\n"
            "#   CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]\n";
    for (size_t c = 0; c < s.cameras.size(); ++c) {
        const CameraModel& m = s.cameras[c];
        std::snprintf(line, sizeof line, "%zu OPENCV %d %d %.10g %.10g %.10g %.10g %.10g %.10g %.10g %.10g\n", c + 1,
                      m.width, m.height, m.fx, m.fy, m.cx + 0.5, m.cy + 0.5, m.k1, m.k2, m.p1, m.p2);
        cams << line;
    }

    // Point ids: 1-based over valid points.
    std::vector<int> pid(s.points.size(), -1);
    int next = 1;
    for (size_t j = 0; j < s.points.size(); ++j)
        if (s.points[j].valid) pid[j] = next++;

    std::ofstream imgs(dir + "/images.txt");
    imgs << "# Image list with two lines of data per image:\n"
            "#   IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME\n"
            "#   POINTS2D[] as (X, Y, POINT3D_ID)\n";
    std::vector<std::unordered_map<int, int>> point2dIndex(s.images.size());
    for (size_t i = 0; i < s.images.size(); ++i) {
        const Image& im = s.images[i];
        if (!im.registered) continue;
        std::vector<std::pair<int, int>> pts2d;  // keypoint, point id
        for (size_t j = 0; j < s.points.size(); ++j) {
            if (pid[j] < 0) continue;
            for (const auto& o : s.points[j].obs)
                if (o.image == int(i)) pts2d.emplace_back(o.keypoint, pid[j]);
        }
        double q[4];
        rotationToQuaternion(im.R, q);
        std::string name = im.input.path;
        if (!imageRoot.empty() && name.compare(0, imageRoot.size(), imageRoot) == 0) {
            name = name.substr(imageRoot.size());
            while (!name.empty() && name[0] == '/') name.erase(0, 1);
        }
        std::snprintf(line, sizeof line, "%zu %.12g %.12g %.12g %.12g %.12g %.12g %.12g %d ", i + 1, q[0], q[1], q[2], q[3],
                      im.t[0], im.t[1], im.t[2], im.camera + 1);
        imgs << line << name << "\n";
        for (size_t k = 0; k < pts2d.size(); ++k) {
            const cv::Point2f& p = im.pixels[pts2d[k].first];
            // COLMAP puts the centre of the top-left pixel at (0.5, 0.5).
            std::snprintf(line, sizeof line, "%s%.3f %.3f %d", k ? " " : "", p.x + 0.5, p.y + 0.5, pts2d[k].second);
            imgs << line;
            point2dIndex[i][pts2d[k].first] = int(k);
        }
        imgs << "\n";
    }

    std::ofstream p3(dir + "/points3D.txt");
    p3 << "# 3D point list with one line of data per point:\n"
          "#   POINT3D_ID, X, Y, Z, R, G, B, ERROR, TRACK[] as (IMAGE_ID, POINT2D_IDX)\n";
    for (size_t j = 0; j < s.points.size(); ++j) {
        if (pid[j] < 0) continue;
        const Point3D& p = s.points[j];
        double err = 0;
        for (const auto& o : p.obs) err += reprojectionError(s, p, o);
        err /= std::max<size_t>(1, p.obs.size());
        std::snprintf(line, sizeof line, "%d %.9g %.9g %.9g %d %d %d %.4f", pid[j], p.X[0], p.X[1], p.X[2], p.color[0],
                      p.color[1], p.color[2], err);
        p3 << line;
        for (const auto& o : p.obs) p3 << " " << (o.image + 1) << " " << point2dIndex[o.image][o.keypoint];
        p3 << "\n";
    }
    return bool(cams) && bool(imgs) && bool(p3);
}

}  // namespace parallax
