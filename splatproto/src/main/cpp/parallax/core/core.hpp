// Parallax: public API of the platform-independent reconstruction core.
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <atomic>
#include <functional>
#include <string>
#include <vector>

namespace parallax {

// Pinhole camera with OpenCV / Brown-Conrady distortion, in pixels of the saved image.
struct CameraModel {
    int id = 0;
    int width = 0, height = 0;
    double fx = 0, fy = 0, cx = 0, cy = 0;
    double k1 = 0, k2 = 0, p1 = 0, p2 = 0, k3 = 0;
    // True when fx..k3 come from a factory calibration (LENS_INTRINSIC_CALIBRATION), false
    // when they are derived from focal length and sensor size. Approximate intrinsics are
    // refined more freely by bundle adjustment.
    bool calibrated = false;
    // Optical centre in the device frame (metres), from LENS_POSE_TRANSLATION.
    double lensX = 0, lensY = 0, lensZ = 0;
    bool hasLensPosition = false;
};

struct ImageInput {
    std::string path;
    int cameraId = 0;
    // Images captured by one request on several physical cameras share a rig group.
    int rigGroup = -1;
    // "Up" (reaction to gravity) in camera coordinates (x right, y down, z forward); zero if unknown.
    double upX = 0, upY = 0, upZ = 0;
    // Rolling shutter: capture time of the first row (s), readout time from the first to the last
    // row (s, SENSOR_ROLLING_SHUTTER_SKEW) and the gyroscope rate in camera coordinates (rad/s).
    double time = 0, rollingShutterSkew = 0;
    double gyroX = 0, gyroY = 0, gyroZ = 0;
    bool hasGyro = false;
};

struct Options {
    int featureMaxSide = 1600;     // SIFT runs on images resized to this longest side
    int maxFeatures = 4000;
    int matchWindow = 10;          // sequential neighbours matched per image (circular)
    float ratioTest = 0.8f;
    double maxReprojError = 4.0;   // pixels
    double minTriAngleDeg = 1.5;
    bool dense = true;
    int denseMaxSide = 960;
    int maxDenseImages = 48;
    bool rollingShutter = true;    // model row-wise capture time in bundle adjustment
    // Printed ArUco marker (DICT_4X4_50) of this edge length in the scene gives metric scale
    // to single-camera scans; 0 disables detection.
    double markerSizeMm = 0;
};

struct Result {
    bool ok = false;
    std::string message;
    int imagesTotal = 0, imagesRegistered = 0;
    int sparsePoints = 0, densePoints = 0;
    double meanReprojError = 0;    // pixels, after the final bundle adjustment
    double metresPerUnit = 0;      // 0 when the scale is unknown (single camera)
    std::string scaleSource = "none";  // "marker", "rig" or "none"
    bool gravityAligned = false;
    std::vector<float> cameraCentres;  // xyz triples, in the exported frame
    std::vector<int> cameraImages;     // input image index of each centre
};

// Return false to request cancellation.
using ProgressFn = std::function<bool(const std::string& stage, float fraction)>;

class Reconstructor {
public:
    explicit Reconstructor(Options options = {}) : opt_(options) {}
    void addCamera(const CameraModel& camera) { cameras_.push_back(camera); }
    void addImage(const ImageInput& image) { images_.push_back(image); }
    // Writes sparse.ply, dense.ply, sparse/0/{cameras,images,points3D}.txt and report.json
    // into outputDir. Image paths in images.txt are relative to imageRoot.
    Result run(const std::string& outputDir, const std::string& imageRoot, const ProgressFn& progress);

private:
    Options opt_;
    std::vector<CameraModel> cameras_;
    std::vector<ImageInput> images_;
};

std::string resultToJson(const Result& r);

}  // namespace parallax
