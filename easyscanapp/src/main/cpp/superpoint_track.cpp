#include "superpoint_track.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <deque>
#include <mutex>
#include <atomic>
#include <unordered_map>

#include <android/log.h>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/video/tracking.hpp>

#include "cam/CamBase.h"
#include "feat/Feature.h"
#include "feat/FeatureDatabase.h"
#include "utils/colors.h"
#include "utils/print.h"

static std::atomic<int> g_max_tracks{200};
static std::atomic<float> g_min_new_dist{10.0f};

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


namespace {
extern "C" int ovmm_match(const float* dA, int nA, const float* dB, int nB, float* bestSim, float* secondSim, int* bestA);

struct LostTrack {
  int id = 0;
  int age = 0;
  int ttl = 0;
  float x = 0.f;
  float y = 0.f;
  std::vector<float> desc;
};

std::mutex g_lost_mtx;
std::atomic<int> g_reacq_enabled{1};  // descriptor re-acquisition off by default: it creates long-gap tracks that destabilise the MSCKF
std::unordered_map<int, std::vector<LostTrack>> g_lost;
std::unordered_map<int, std::vector<std::vector<float>>> g_prev_desc;
int g_reacq_total = 0;

inline void normalizeRow(float* p, int n) {
  double s = 0.0;
  for (int i = 0; i < n; i++) s += (double)p[i] * p[i];
  s = std::sqrt(s);
  if (s < 1e-9) return;
  float inv = (float)(1.0 / s);
  for (int i = 0; i < n; i++) p[i] *= inv;
}

// Keep a lost track's descriptor so a later frame can recover its ID.
inline void pushLost(int cam_id, int id, int age, float x, float y, const std::vector<float>* desc) {
  if (!desc || desc->size() != 256) return;
  std::lock_guard<std::mutex> lck(g_lost_mtx);
  auto &v = g_lost[cam_id];
  if ((int)v.size() >= 200) v.erase(v.begin());
  LostTrack lt;
  lt.id = id;
  lt.age = age;
  lt.x = x;
  lt.y = y;
  lt.ttl = 30;   // ~1 s at 30 fps
  lt.desc = *desc;
  v.push_back(std::move(lt));
}
} // namespace

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
  std::vector<std::vector<float>> &desc_prev = g_prev_desc[cam_id];

  std::vector<cv::Point2f> tracked;
  std::vector<size_t> tracked_ids;
  std::vector<int> tracked_age, tracked_miss;
  std::vector<cv::Point2f> tracked_prev;
  std::vector<std::vector<float>> tracked_desc;

  const float MAX_FB_ERR = 0.35f;
  const float SNAP_RADIUS = 3.0f;
  const int MAX_MISS = 2;
  const int MAX_AGE = 60;
  const float MIN_NEW_DIST = g_min_new_dist.load();

  if (!prev.empty() && prev.size() == cur.size() && !pts_prev.empty()) {
    std::vector<cv::Point2f> fwd, bwd;
    std::vector<uchar> st1, st2;
    std::vector<float> err;
    auto crit = cv::TermCriteria(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 30, 0.01);
    cv::calcOpticalFlowPyrLK(prev, cur, pts_prev, fwd, st1, err, cv::Size(15, 15), 2, crit);
    cv::calcOpticalFlowPyrLK(cur, prev, fwd, bwd, st2, err, cv::Size(15, 15), 2, crit);

    for (size_t i = 0; i < pts_prev.size(); i++) {
      const std::vector<float>* last_desc = (i < desc_prev.size() && desc_prev[i].size() == 256) ? &desc_prev[i] : nullptr;
      if (!st1[i] || !st2[i]) { pushLost(cam_id, (int)ids_prev[i], age_prev[i], pts_prev[i].x, pts_prev[i].y, last_desc); continue; }
      if (fwd[i].x < 1 || fwd[i].y < 1 || fwd[i].x > cur.cols - 2 || fwd[i].y > cur.rows - 2) {
        pushLost(cam_id, (int)ids_prev[i], age_prev[i], pts_prev[i].x, pts_prev[i].y, last_desc);
        continue;
      }
      if (std::hypot(bwd[i].x - pts_prev[i].x, bwd[i].y - pts_prev[i].y) > MAX_FB_ERR) {
        pushLost(cam_id, (int)ids_prev[i], age_prev[i], pts_prev[i].x, pts_prev[i].y, last_desc);
        continue;
      }

      cv::Point2f pt = fwd[i];
      int miss = miss_prev[i];
      bool supported = false;
      int sup_idx = -1;
      float bestd = SNAP_RADIUS * SNAP_RADIUS;
      for (size_t di = 0; di < det.kp.size(); di++) {
        float dx = det.kp[di].x - fwd[i].x, dy = det.kp[di].y - fwd[i].y;
        float d2 = dx * dx + dy * dy;
        if (d2 < bestd) { bestd = d2; pt = det.kp[di]; supported = true; sup_idx = (int)di; }
      }
      if (supported) miss = 0;
      else miss += 1;
      if (miss > MAX_MISS) { pushLost(cam_id, (int)ids_prev[i], age_prev[i], pts_prev[i].x, pts_prev[i].y, last_desc); continue; }
      int age = age_prev[i] + 1;
      if (age > MAX_AGE) { pushLost(cam_id, (int)ids_prev[i], age_prev[i], pts_prev[i].x, pts_prev[i].y, last_desc); continue; }

      tracked.push_back(pt);
      tracked_ids.push_back(ids_prev[i]);
      tracked_age.push_back(age);
      tracked_miss.push_back(miss);
      tracked_prev.push_back(pts_prev[i]);
      if (supported && sup_idx >= 0 && sup_idx < (int)det.desc.size() && det.desc[sup_idx].size() == 256) {
        tracked_desc.push_back(det.desc[sup_idx]);
      } else if (last_desc != nullptr) {
        tracked_desc.push_back(*last_desc);
      } else {
        tracked_desc.emplace_back();
      }
    }
  }

  std::vector<cv::Point2f> all_pts = tracked;
  std::vector<size_t> all_ids = tracked_ids;
  std::vector<int> all_age = tracked_age;
  std::vector<int> all_miss = tracked_miss;
  std::vector<std::vector<float>> all_desc = tracked_desc;

  // detections already covered by a tracked point (KLT carries it)
  std::vector<char> detUsed(det.kp.size(), 0);
  for (size_t b = 0; b < det.kp.size(); b++) {
    for (auto &t : tracked) {
      float dx = det.kp[b].x - t.x, dy = det.kp[b].y - t.y;
      if (dx * dx + dy * dy < 16.0f) { detUsed[b] = 1; break; }
    }
  }

  // ---- hybrid: re-acquire lost tracks with the NPU descriptor matcher ----
  int reacq = 0;
  if (g_reacq_enabled.load()) {
    std::vector<LostTrack> lost;
    {
      std::lock_guard<std::mutex> lck(g_lost_mtx);
      auto &v = g_lost[cam_id];
      for (auto &lt : v) if (lt.ttl > 0 && lt.desc.size() == 256) lost.push_back(lt);
    }
    if (!lost.empty()) {
      std::vector<int> detIdx;
      for (size_t b = 0; b < det.kp.size(); b++) {
        if (detUsed[b]) continue;
        if (b < det.desc.size() && det.desc[b].size() == 256) detIdx.push_back((int)b);
      }
      if (!detIdx.empty()) {
        const int nL = (int)lost.size();
        const int nD = (int)detIdx.size();
        std::vector<float> dA((size_t)nL * 256), dB((size_t)nD * 256);
        for (int i = 0; i < nL; i++) {
          float* p = dA.data() + (size_t)i * 256;
          std::memcpy(p, lost[i].desc.data(), 256 * sizeof(float));
          normalizeRow(p, 256);
        }
        for (int i = 0; i < nD; i++) {
          float* p = dB.data() + (size_t)i * 256;
          std::memcpy(p, det.desc[detIdx[i]].data(), 256 * sizeof(float));
          normalizeRow(p, 256);
        }
        std::vector<float> bestSim(nD, -2.f), secondSim(nD, -2.f);
        std::vector<int> bestA(nD, -1);
        if (ovmm_match(dA.data(), nL, dB.data(), nD, bestSim.data(), secondSim.data(), bestA.data())) {
          std::vector<char> lostUsed(nL, 0);
          int rejectedGate = 0;
          const int MAX_REACQ_PER_FRAME = 30;
          for (int i = 0; i < nD && reacq < MAX_REACQ_PER_FRAME; i++) {
            int a = bestA[i];
            if (a < 0 || lostUsed[a]) continue;
            if (bestSim[i] < 0.85f || (bestSim[i] - secondSim[i]) < 0.04f) continue;
            int b = detIdx[i];
            // geometry gate: a recovered track must reappear near where it was last seen
            int missed = 30 - lost[a].ttl;
            float radius = 50.0f + 8.0f * (float)missed;
            float dx = det.kp[b].x - lost[a].x;
            float dy = det.kp[b].y - lost[a].y;
            if (dx * dx + dy * dy > radius * radius) { rejectedGate++; continue; }
            all_pts.push_back(det.kp[b]);
            all_ids.push_back((size_t)lost[a].id);
            all_age.push_back(lost[a].age + 1);
            all_miss.push_back(0);
            all_desc.push_back(det.desc[b]);
            lostUsed[a] = 1;
            detUsed[b] = 1;
            reacq++;
          }
          // age the pool, drop recovered entries and expired ones
          std::lock_guard<std::mutex> lck(g_lost_mtx);
          auto &v = g_lost[cam_id];
          std::vector<LostTrack> keep;
          int li = 0;
          for (size_t k = 0; k < v.size(); k++) {
            if (v[k].ttl <= 0) continue;
            bool recovered = (li < nL) ? (bool)lostUsed[li] : false;
            int j = li++;
            if (recovered) continue;
            LostTrack lt = std::move(v[k]);
            lt.ttl -= 1;
            if (lt.ttl > 0) keep.push_back(std::move(lt));
            (void)j;
          }
          v = std::move(keep);
        }
      }
    }
  }
  if (reacq > 0) g_reacq_total += reacq;

  int added = 0;
  for (size_t b = 0; b < det.kp.size(); b++) {
    if (detUsed[b]) continue;
    const cv::Point2f &d = det.kp[b];
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
    all_desc.push_back((b < det.desc.size() && det.desc[b].size() == 256) ? det.desc[b] : std::vector<float>());
    added++;
  }

  if (all_pts.empty()) return;

  const size_t MAX_TRACKS = (size_t)g_max_tracks.load();
  if (all_pts.size() > MAX_TRACKS) {
    std::vector<size_t> idx(all_pts.size());
    for (size_t i = 0; i < idx.size(); i++) idx[i] = i;
    std::stable_sort(idx.begin(), idx.end(), [&](size_t a, size_t b) { return all_age[a] > all_age[b]; });
    idx.resize(MAX_TRACKS);
    std::vector<cv::Point2f> p2; std::vector<size_t> i2; std::vector<int> a2, m2;
    std::vector<std::vector<float>> d2v;
    for (size_t k : idx) { p2.push_back(all_pts[k]); i2.push_back(all_ids[k]); a2.push_back(all_age[k]); m2.push_back(all_miss[k]); d2v.push_back(all_desc[k]); }
    all_pts = std::move(p2); all_ids = std::move(i2); all_age = std::move(a2); all_miss = std::move(m2);
    all_desc = std::move(d2v);
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
                        "det=%zu tracked=%zu reacq=%d added=%d kept=%zu reacqTotal=%d mean_move=%.2fpx",
                        det.kp.size(), tracked.size(), reacq, added, all_pts.size(), g_reacq_total, mean_move);
  }

  prev_gray[cam_id] = cur.clone();
  prev_kp[cam_id] = all_pts;
  prev_ids[cam_id] = all_ids;
  track_age[cam_id] = all_age;
  track_miss[cam_id] = all_miss;
  g_prev_desc[cam_id] = all_desc;
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

void setTrackCaps(int maxTracks, float minNewDist) {
  int mt = maxTracks < 20 ? 20 : (maxTracks > 2000 ? 2000 : maxTracks);
  float md = minNewDist < 1.0f ? 1.0f : (minNewDist > 40.0f ? 40.0f : minNewDist);
  g_max_tracks.store(mt);
  g_min_new_dist.store(md);
}

void setReacqEnabled(int enabled) {
  ov_core::g_reacq_enabled.store(enabled != 0 ? 1 : 0);
}
