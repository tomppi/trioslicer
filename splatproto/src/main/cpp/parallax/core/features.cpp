// Parallax: SIFT feature extraction (RootSIFT) and mutual ratio-test matching.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <opencv2/features2d.hpp>
#include <opencv2/flann.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#if __has_include(<opencv2/objdetect/aruco_detector.hpp>)
#include <opencv2/objdetect/aruco_detector.hpp>
#define PARALLAX_HAVE_ARUCO 1
#endif

#include <algorithm>

#include "internal.hpp"

namespace parallax {

bool extractFeatures(State& s, int imageIndex) {
    Image& im = s.images[imageIndex];
    // One read: colour for the sparse points, grey for detection.
    const cv::Mat bgr = cv::imread(im.input.path, cv::IMREAD_COLOR | cv::IMREAD_IGNORE_ORIENTATION);
    if (bgr.empty()) return false;
    cv::Mat gray;
    cv::cvtColor(bgr, gray, cv::COLOR_BGR2GRAY);
#ifdef PARALLAX_HAVE_ARUCO
    if (s.opt.markerSizeMm > 0) {
        // Printed scale markers, sub-pixel corners (Garrido-Jurado et al. 2014).
        cv::aruco::DetectorParameters params;
        params.cornerRefinementMethod = cv::aruco::CORNER_REFINE_SUBPIX;
        cv::aruco::ArucoDetector detector(cv::aruco::getPredefinedDictionary(cv::aruco::DICT_4X4_50), params);
        std::vector<int> ids;
        std::vector<std::vector<cv::Point2f>> corners;
        detector.detectMarkers(gray, corners, ids);
        for (size_t k = 0; k < ids.size(); ++k)
            if (corners[k].size() == 4) im.markers[ids[k]] = {corners[k][0], corners[k][1], corners[k][2], corners[k][3]};
    }
#endif

    const double scale = std::min(1.0, s.opt.featureMaxSide / double(std::max(gray.cols, gray.rows)));
    cv::Mat work;
    if (scale < 1.0) cv::resize(gray, work, cv::Size(), scale, scale, cv::INTER_AREA);
    else work = gray;

    // Lowe's SIFT, the de-facto standard detector/descriptor for SfM (patent expired 2020).
    auto sift = cv::SIFT::create(s.opt.maxFeatures, 3, 0.02, 10.0, 1.6);
    std::vector<cv::KeyPoint> kps;
    cv::Mat desc;
    sift->detectAndCompute(work, cv::noArray(), kps, desc);

    // RootSIFT (Arandjelovic & Zisserman 2012): L1-normalise, then square root.
    for (int r = 0; r < desc.rows; ++r) {
        float* d = desc.ptr<float>(r);
        double l1 = 0;
        for (int c = 0; c < desc.cols; ++c) l1 += d[c];
        const float inv = l1 > 0 ? float(1.0 / l1) : 0.f;
        for (int c = 0; c < desc.cols; ++c) d[c] = std::sqrt(d[c] * inv);
    }

    im.pixels.resize(kps.size());
    const float inv = float(1.0 / scale);
    for (size_t i = 0; i < kps.size(); ++i) {
        // Keypoint centres follow OpenCV's pixel-centre convention, so a plain rescale suffices
        // up to a sub-pixel shift: (x + 0.5) / s - 0.5.
        im.pixels[i] = {(kps[i].pt.x + 0.5f) * inv - 0.5f, (kps[i].pt.y + 0.5f) * inv - 0.5f};
    }
    im.colors.resize(im.pixels.size());
    for (size_t i = 0; i < im.pixels.size(); ++i) {
        const int x = std::clamp(int(im.pixels[i].x + 0.5f), 0, bgr.cols - 1);
        const int y = std::clamp(int(im.pixels[i].y + 0.5f), 0, bgr.rows - 1);
        const cv::Vec3b c = bgr.at<cv::Vec3b>(y, x);
        im.colors[i] = {c[2], c[1], c[0]};
    }
    im.descriptors = desc;
    // Randomised kd-trees (Muja & Lowe), built once per image and shared by all its pairs.
    if (desc.rows >= 8) im.index = std::make_shared<cv::flann::Index>(im.descriptors, cv::flann::KDTreeIndexParams(4));
    im.track.assign(kps.size(), -1);
    undistortKeypoints(s.cameras[im.camera], im.pixels, im.normalized);
    return true;
}

static std::vector<cv::DMatch> knnBest(cv::flann::Index& index, const cv::Mat& query, float ratio) {
    std::vector<cv::DMatch> out;
    if (query.empty()) return out;
    cv::Mat idx, dist;
    index.knnSearch(query, idx, dist, 2, cv::flann::SearchParams(64));
    for (int i = 0; i < query.rows; ++i) {
        const float d0 = dist.at<float>(i, 0), d1 = dist.at<float>(i, 1);
        // FLANN returns squared L2 distances.
        if (d0 < ratio * ratio * d1) out.emplace_back(i, idx.at<int>(i, 0), d0);
        else out.emplace_back(i, -1, d0);
    }
    return out;
}

std::vector<std::pair<int, int>> matchDescriptors(const Image& a, const Image& b, float ratio) {
    std::vector<std::pair<int, int>> matches;
    if (!a.index || !b.index) return matches;
    // Searching a built index is read-only, so pairs sharing an image can run in parallel.
    auto fwd = knnBest(*b.index, a.descriptors, ratio);
    auto bwd = knnBest(*a.index, b.descriptors, ratio);
    for (const auto& m : fwd) {
        if (m.trainIdx < 0) continue;
        // Mutual nearest neighbour check.
        if (bwd[m.trainIdx].trainIdx == m.queryIdx) matches.emplace_back(m.queryIdx, m.trainIdx);
    }
    return matches;
}

}  // namespace parallax
