// Parallax: internal data structures shared by the reconstruction stages.
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <opencv2/core.hpp>
#include <opencv2/flann.hpp>

#include <array>
#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "core.hpp"

namespace parallax {

constexpr double kPi = 3.14159265358979323846;

struct Image {
    ImageInput input;
    int camera = 0;                       // index into State::cameras
    std::vector<cv::Point2f> pixels;      // keypoints, full-resolution pixels
    std::vector<cv::Point2d> normalized;  // undistorted, normalized image coordinates
    cv::Mat descriptors;                  // RootSIFT, CV_32F
    std::shared_ptr<cv::flann::Index> index;  // kd-trees over descriptors, built once
    std::vector<int> track;               // per keypoint: track id or -1
    std::vector<cv::Vec3b> colors;        // per keypoint RGB, sampled at extraction
    std::map<int, std::array<cv::Point2f, 4>> markers;  // ArUco id -> corners, full-resolution pixels
    bool registered = false;
    cv::Matx33d R = cv::Matx33d::eye();   // world -> camera
    cv::Vec3d t = {0, 0, 0};
    cv::Vec3d centre() const { return -(R.t() * t); }
    // Rolling-shutter motion during readout: angular rate (camera frame) and centre velocity (world).
    bool rs = false;
    cv::Vec3d omega = {0, 0, 0}, velocity = {0, 0, 0};
};

struct Observation {
    int image;
    int keypoint;
};

struct Track {
    std::vector<Observation> elements;  // every feature of this track, registered or not
    int point = -1;                     // index into State::points, or -1
};

struct Point3D {
    cv::Vec3d X;
    cv::Vec3b color = {200, 200, 200};  // RGB
    std::vector<Observation> obs;       // observations used in the model
    int track = -1;
    bool valid = true;
};

struct PairGeometry {
    int a = 0, b = 0;
    std::vector<std::pair<int, int>> inliers;  // keypoint indices in a and b
    int homographyInliers = 0;
    cv::Matx33d R;  // b relative to a: x_b = R x_a + t
    cv::Vec3d t;
};

struct State {
    Options opt;
    std::vector<CameraModel> cameras;
    std::vector<CameraModel> priors;   // intrinsics as supplied, for the calibration prior
    std::vector<Image> images;
    std::vector<PairGeometry> pairs;
    std::vector<Track> tracks;
    std::vector<Point3D> points;
};

// features.cpp
bool extractFeatures(State& s, int imageIndex);
std::vector<std::pair<int, int>> matchDescriptors(const Image& a, const Image& b, float ratio);

// geometry.cpp
// Camera-frame point of X seen at image row `row`. With a rolling shutter the camera has moved
// by dt = skew (row / (H - 1) - 1/2) since the middle row (first order in dt):
//   x = A (R X + t + offset),  A = I - dt [omega]x,  offset = -dt R v.
struct RowMotion {
    cv::Matx33d A = cv::Matx33d::eye();
    cv::Vec3d offset = {0, 0, 0};
};
RowMotion rowMotion(const State& s, const Image& im, double row, const cv::Matx33d& R);
cv::Point2d camToPixel(const CameraModel& c, const cv::Vec3d& p, bool* inFront = nullptr);
cv::Matx33d cameraMatrix(const CameraModel& c);
cv::Mat distortionVector(const CameraModel& c);
cv::Point2d projectPoint(const CameraModel& c, const cv::Matx33d& R, const cv::Vec3d& t,
                         const cv::Vec3d& X, bool* inFront = nullptr);
void undistortKeypoints(const CameraModel& c, const std::vector<cv::Point2f>& px,
                        std::vector<cv::Point2d>& normalized);
bool verifyPair(const State& s, int a, int b, const std::vector<std::pair<int, int>>& matches,
                PairGeometry& out);
bool triangulateTrack(State& s, int trackId);
double reprojectionError(const State& s, const Point3D& p, const Observation& o);
double triangulationAngleDeg(const cv::Vec3d& X, const cv::Vec3d& c1, const cv::Vec3d& c2);

// ba.cpp
struct BAOptions {
    std::vector<int> variableImages;  // poses optimised; others observing the points stay fixed
    int fixedGaugeImage = -1;         // never moved
    // Scale gauge: one translation component of a second image is held fixed (global BA only).
    int scaleGaugeImage = -1, scaleGaugeAxis = 0;
    bool refineIntrinsics = false;
    int maxIterations = 15;
    double huberPx = 2.0;
};
double bundleAdjust(State& s, const BAOptions& o);  // returns RMS reprojection error, pixels

// dense.cpp
struct DensePoint {
    cv::Vec3f X, n;
    cv::Vec3b rgb;
};
std::vector<DensePoint> denseReconstruct(const State& s, const ProgressFn& progress, float p0, float p1);

// io.cpp
bool writePly(const std::string& path, const std::vector<DensePoint>& pts);
bool writeColmap(const State& s, const std::string& dir, const std::string& imageRoot);
bool writeText(const std::string& path, const std::string& text);

}  // namespace parallax
