#include "superpoint_track.h"

#include <algorithm>
#include <cmath>
#include <deque>
#include <mutex>
#include <unordered_map>

#include <android/log.h>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/video/tracking.hpp>

#include "cam/CamBase.h"
#include "feat/Feature.h"
#include "feat/FeatureDatabase.h"
#include "utils/colors.h"
#include "utils/print.h"

namespace ov_core {

std::mutex g_feat_race_mtx;

static std::mutex g_sp_mtx;
static std::unordered_map<int, std::deque<SuperPointFrame>> g_sp_frames;

void superpoint_submit(int cam_id, double ts, const float *kp_xy, const float *desc, int n) {
  SuperPointFrame f;
  f.ts = ts;
  f.kp.resize(std::max(0, n));
  f.desc.resize(std::max(0, n));
  for (int i = 0; i < n; i++) {
    f.kp[i] = cv::Point2f(kp_xy[2 * i], kp_xy[2 * i + 1]);
    f.desc[i].resize(256);
    std::copy(desc + i * 256, desc + i * 256 + 256, f.desc[i].begin());
  }
  std::lock_guard<std::mutex> lck(g_sp_mtx);
  auto &q = g_sp_frames[cam_id];
  q.push_back(std::move(f));
  while (q.size() > 8) q.pop_front();
}

void TrackSuperPoint::feed_new_camera(const CameraData &message) {
  for (size_t i = 0; i < message.sensor_ids.size(); i++) feed_monocular(message, i);
}

void TrackSuperPoint::feed_monocular(const CameraData &message, size_t msg_id) {
  int cam_id = message.sensor_ids.at(msg_id);

  SuperPointFrame det;
  {
    std::lock_guard<std::mutex> lck(g_sp_mtx);
    auto it = g_sp_frames.find(cam_id);
    if (it == g_sp_frames.end() || it->second.empty()) return;
    auto &q = it->second;
    size_t best = 0;
    double bestd = 1e9;
    for (size_t i = 0; i < q.size(); i++) {
      double d = std::abs(q[i].ts - message.timestamp);
      if (d < bestd) { bestd = d; best = i; }
    }
    if (bestd > 0.05) return;
    det = std::move(q[best]);
    q.erase(q.begin() + best);
  }

  cv::Mat cur = message.images.at(msg_id);
  if (cur.channels() == 3) cv::cvtColor(cur, cur, cv::COLOR_RGB2GRAY);
  else if (cur.channels() == 4) cv::cvtColor(cur, cur, cv::COLOR_RGBA2GRAY);
  if (cur.channels() != 1) return;

  cv::Mat &prev = prev_gray[cam_id];
  std::vector<cv::Point2f> &pts_prev = prev_kp[cam_id];
  std::vector<size_t> &ids_prev = prev_ids[cam_id];
  std::vector<int> &age_prev = track_age[cam_id];
  std::vector<int> &miss_prev = track_miss[cam_id];

  std::vector<cv::Point2f> tracked;
  std::vector<size_t> tracked_ids;
  std::vector<int> tracked_age, tracked_miss;
  std::vector<cv::Point2f> tracked_prev;

  const float MAX_FB_ERR = 0.35f;   // px, forward-backward consistency
  const float SNAP_RADIUS = 3.0f;  // re-support radius against SuperPoint detections
  const int MAX_MISS = 2;          // frames without SuperPoint support before dropping
  const int MAX_AGE = 60;          // cap track length to avoid KLT drift
  const float MIN_NEW_DIST = 10.0f; // new SuperPoint points must be this far from tracks

  if (!prev.empty() && prev.size() == cur.size() && !pts_prev.empty()) {
    std::vector<cv::Point2f> fwd, bwd;
    std::vector<uchar> st1, st2;
    std::vector<float> err;
    auto crit = cv::TermCriteria(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 30, 0.01);
    cv::calcOpticalFlowPyrLK(prev, cur, pts_prev, fwd, st1, err, cv::Size(15, 15), 2, crit);
    cv::calcOpticalFlowPyrLK(cur, prev, fwd, bwd, st2, err, cv::Size(15, 15), 2, crit);

    for (size_t i = 0; i < pts_prev.size(); i++) {
      if (!st1[i] || !st2[i]) continue;
      if (fwd[i].x < 1 || fwd[i].y < 1 || fwd[i].x > cur.cols - 2 || fwd[i].y > cur.rows - 2) continue;
      if (std::hypot(bwd[i].x - pts_prev[i].x, bwd[i].y - pts_prev[i].y) > MAX_FB_ERR) continue;

      // snap to a nearby SuperPoint detection; unsupported tracks accumulate misses
      cv::Point2f pt = fwd[i];
      int miss = miss_prev[i];
      bool supported = false;
      float bestd = SNAP_RADIUS * SNAP_RADIUS;
      for (auto &d : det.kp) {
        float dx = d.x - fwd[i].x, dy = d.y - fwd[i].y;
        float d2 = dx * dx + dy * dy;
        if (d2 < bestd) { bestd = d2; pt = d; supported = true; }
      }
      if (supported) miss = 0;
      else miss += 1;
      if (miss > MAX_MISS) continue;
      int age = age_prev[i] + 1;
      if (age > MAX_AGE) continue;

      tracked.push_back(pt);
      tracked_ids.push_back(ids_prev[i]);
      tracked_age.push_back(age);
      tracked_miss.push_back(miss);
      tracked_prev.push_back(pts_prev[i]);
    }
  }

  std::vector<cv::Point2f> all_pts = tracked;
  std::vector<size_t> all_ids = tracked_ids;
  std::vector<int> all_age = tracked_age;
  std::vector<int> all_miss = tracked_miss;
  int added = 0;
  for (auto &d : det.kp) {
    if (d.x < 1 || d.y < 1 || d.x > cur.cols - 2 || d.y > cur.rows - 2) continue;
    bool near = false;
    for (auto &t : tracked) {
      float dx = d.x - t.x, dy = d.y - t.y;
      if (dx * dx + dy * dy < MIN_NEW_DIST * MIN_NEW_DIST) { near = true; break; }
    }
    if (near) continue;
    for (size_t q = tracked.size(); q < all_pts.size(); q++) {
      float dx = d.x - all_pts[q].x, dy = d.y - all_pts[q].y;
      if (dx * dx + dy * dy < MIN_NEW_DIST * MIN_NEW_DIST) { near = true; break; }
    }
    if (near) continue;
    all_pts.push_back(d);
    all_ids.push_back(currid++);
    all_age.push_back(0);
    all_miss.push_back(0);
    added++;
  }

  if (all_pts.empty()) return;

  // Prefer older (better supported) tracks; cap total features fed to the MSCKF.
  const size_t MAX_TRACKS = 200;
  if (all_pts.size() > MAX_TRACKS) {
    std::vector<size_t> idx(all_pts.size());
    for (size_t i = 0; i < idx.size(); i++) idx[i] = i;
    std::stable_sort(idx.begin(), idx.end(), [&](size_t a, size_t b) { return all_age[a] > all_age[b]; });
    idx.resize(MAX_TRACKS);
    std::vector<cv::Point2f> p2; std::vector<size_t> i2; std::vector<int> a2, m2;
    for (size_t k : idx) { p2.push_back(all_pts[k]); i2.push_back(all_ids[k]); a2.push_back(all_age[k]); m2.push_back(all_miss[k]); }
    all_pts = std::move(p2); all_ids = std::move(i2); all_age = std::move(a2); all_miss = std::move(m2);
  }

  {
    std::lock_guard<std::mutex> lck(g_feat_race_mtx);
    for (size_t j = 0; j < all_pts.size(); j++) {
      cv::Point2f npt = camera_calib.at(cam_id)->undistort_cv(all_pts[j]);
      database->update_feature(all_ids[j], message.timestamp, (size_t)cam_id, all_pts[j].x, all_pts[j].y, npt.x, npt.y);
    }
  }

  {
    std::lock_guard<std::mutex> lck(mtx_last_vars);
    std::vector<cv::KeyPoint> kps;
    kps.reserve(all_pts.size());
    for (auto &p : all_pts) kps.emplace_back(p, 1.f);
    pts_last[cam_id] = kps;
    ids_last[cam_id] = all_ids;
    img_last[cam_id] = cur.clone();
    num_features = (int)all_pts.size();
  }

  float mean_move = 0.f;
  for (size_t i = 0; i < tracked.size(); i++) mean_move += cv::norm(tracked[i] - tracked_prev[i]);
  mean_move = tracked.empty() ? -1.f : mean_move / (float)tracked.size();
  static int log_count = 0;
  if (++log_count % 30 == 0) {
    __android_log_print(ANDROID_LOG_INFO, "SuperPointTrack",
                        "det=%zu tracked=%zu added=%d kept=%zu mean_move=%.2fpx", det.kp.size(), tracked.size(),
                        added, all_pts.size(), mean_move);
  }

  prev_gray[cam_id] = cur.clone();
  prev_kp[cam_id] = all_pts;
  prev_ids[cam_id] = all_ids;
  track_age[cam_id] = all_age;
  track_miss[cam_id] = all_miss;
}

void TrackSuperPoint::display_active(cv::Mat &img_out, int r1, int g1, int b1, int r2, int g2, int b2,
                                     std::string overlay) {
  std::lock_guard<std::mutex> lck(mtx_last_vars);
  for (auto &pair : img_last) {
    if (!pair.second.empty()) { img_out = pair.second.clone(); break; }
  }
  if (img_out.empty()) return;
  if (img_out.channels() == 1) cv::cvtColor(img_out, img_out, cv::COLOR_GRAY2BGR);
  for (auto &pair : pts_last) {
    for (auto &kp : pair.second) cv::circle(img_out, kp.pt, 2, cv::Scalar(b1, g1, r1), -1);
  }
}

void TrackSuperPoint::display_history(cv::Mat &img_out, int r1, int g1, int b1, int r2, int g2, int b2,
                                      std::vector<size_t> highlighted, std::string overlay) {
  display_active(img_out, r1, g1, b1, r2, g2, b2, overlay);
}

} // namespace ov_core
