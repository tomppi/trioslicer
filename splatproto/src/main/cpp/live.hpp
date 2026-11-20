// Parallax: live capture support.
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <opencv2/core.hpp>

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace parallax {

// One Camera2 YUV_420_888 image, planes as given by android.media.Image.
struct YuvFrame {
    const uint8_t *y, *u, *v;
    int width, height, yStride, uvStride, uvPixelStride;
};

cv::Mat yuvToBgr(const YuvFrame& f);
bool saveJpeg(const YuvFrame& f, const std::string& path, int quality);
// Renders the frame rotated clockwise by `rotation` degrees into an RGBA_8888 buffer.
void renderPreview(const YuvFrame& f, int rotation, uint8_t* rgba, int outW, int outH, int outStride);

struct TrackResult {
    float progress = 0;    // 0..1 towards the next keyframe
    float sharpness = 0;
    float meanY = 0, darkRatio = 0, clipRatio = 0;
    int tracked = 0;
    bool keyframe = false;  // suggestion; the caller decides and calls markKeyframe()
    bool sharp = true;      // passed the blur gate
    std::vector<cv::Point2f> points;   // normalised [0,1] in the sensor frame
    std::vector<cv::Point2f> anchors;  // the same points at the last keyframe (flow trails)
};

class Tracker {
public:
    void reset();
    TrackResult process(const YuvFrame& f);
    void markKeyframe();

private:
    std::mutex mtx_;
    cv::Mat prev_;
    std::vector<cv::Point2f> pts_, anchors_;
    int anchorsAtKeyframe_ = 0;
    float sharpRef_ = 0;
    bool wantFirst_ = true, topUp_ = true;
};

}  // namespace parallax
