#include "superpoint_track.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <deque>
#include <mutex>
#include <atomic>
#include <unordered_map>

#include <android/log.h>
#include <opencv2/calib3d/calib3d.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/video/tracking.hpp>

#include "cam/CamBase.h"
#include "feat/Feature.h"
#include "feat/FeatureDatabase.h"
#include "utils/colors.h"
#include "utils/print.h"

static std::atomic<int> g_max_tracks{200};
static std::atomic<int> g_lightglue_on{0};
static std::atomic<int> g_klt_fallback_on{0};
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
extern "C" int sp_lightglue_ready();
extern "C" int sp_lightglue_match(const float* kp0, const float* desc0, int n0,
                                  const float* kp1, const float* desc1, int n1,
                                  int* pairs, int maxPairs);

struct LostTrack {
  int id = 0;
  int age = 0;
  int ttl = 0;
  float x = 0.f;
  float y = 0.f;
  std::vector<float> desc;
};

std::mutex g_lost_mtx;
std::atomic<int> g_reacq_enabled{0};

// Laser stripe exclusion: the line is rigidly attached to the camera, so points on it do not move
// with the scene and would inject false zero-motion constraints into the MSCKF.
static std::atomic<float> g_band_cx{0.f}, g_band_cy{0.f}, g_band_nx{0.f}, g_band_ny{1.f}, g_band_hw{0.f};
static std::atomic<int> g_band_on{0};

static inline bool laserBandHit(float x, float y) {
  if (!g_band_on.load(std::memory_order_relaxed)) return false;
  const float d = g_band_nx.load(std::memory_order_relaxed) * (x - g_band_cx.load(std::memory_order_relaxed)) +
                  g_band_ny.load(std::memory_order_relaxed) * (y - g_band_cy.load(std::memory_order_relaxed));
  return std::fabs(d) < g_band_hw.load(std::memory_order_relaxed);
}
  // descriptor re-acquisition off by default: it creates long-gap tracks that destabilise the MSCKF
std::unordered_map<int, std::vector<LostTrack>> g_lost;
std::unordered_map<int, std::vector<std::vector<float>>> g_prev_desc;
// Per-camera constant-velocity prediction from the previous two matched frames.
// SuperVINS gates LightGlue with an IMU/VIO-predicted position; we start with the
// same idea using the feature's own last displacement, available before VIO update.
std::unordered_map<int, std::vector<cv::Point2f>> g_prev_vel;

// VIO camera-to-world rotation, published by VioManager before each camera frame.
std::mutex g_rot_mtx;
double g_rot_cam_to_world[9] = {1,0,0, 0,1,0, 0,0,1};
bool g_rot_valid = false;
std::unordered_map<int, cv::Matx33d> g_prev_rot_cam_to_world;

// Predicted SLAM-landmark pixels keyed by feature id, published by VioManager.
std::mutex g_slam_pred_mtx;
std::unordered_map<size_t, cv::Point2f> g_slam_pred;
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

void setVioSlamFeaturePrediction(const std::unordered_map<size_t, cv::Point2f> &preds) {
  std::lock_guard<std::mutex> lck(g_slam_pred_mtx);
  g_slam_pred = preds;
}

void setVioCameraRotationPrediction(const double *R_cam_to_world) {
  if (!R_cam_to_world) return;
  std::lock_guard<std::mutex> lck(g_rot_mtx);
  for (int i = 0; i < 9; i++) g_rot_cam_to_world[i] = R_cam_to_world[i];
  g_rot_valid = true;
}

void setLaserBand(float cx, float cy, float nx, float ny, float half_width, int on) {
  g_band_cx.store(cx); g_band_cy.store(cy);
  g_band_nx.store(nx); g_band_ny.store(ny);
  g_band_hw.store(half_width); g_band_on.store(on);
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
  std::vector<std::vector<float>> &desc_prev = g_prev_desc[cam_id];
  std::vector<cv::Point2f> &vel_prev = g_prev_vel[cam_id];
  if (vel_prev.size() != pts_prev.size()) vel_prev.assign(pts_prev.size(), cv::Point2f(0.f, 0.f));

  cv::Matx33d R_cur(1,0,0, 0,1,0, 0,0,1);
  bool has_cur_rot = false;
  {
    std::lock_guard<std::mutex> lck(g_rot_mtx);
    if (g_rot_valid) {
      R_cur = cv::Matx33d(g_rot_cam_to_world[0], g_rot_cam_to_world[1], g_rot_cam_to_world[2],
                          g_rot_cam_to_world[3], g_rot_cam_to_world[4], g_rot_cam_to_world[5],
                          g_rot_cam_to_world[6], g_rot_cam_to_world[7], g_rot_cam_to_world[8]);
      has_cur_rot = true;
    }
  }
  cv::Matx33d R_rel;
  bool have_rot_pred = false;
  auto it_prev_rot = g_prev_rot_cam_to_world.find(cam_id);
  if (has_cur_rot && it_prev_rot != g_prev_rot_cam_to_world.end()) {
    R_rel = R_cur.t() * it_prev_rot->second;
    have_rot_pred = true;
  }
  if (has_cur_rot) g_prev_rot_cam_to_world[cam_id] = R_cur;
  else g_prev_rot_cam_to_world.erase(cam_id);

  std::vector<cv::Point2f> tracked;
  std::vector<size_t> tracked_ids;
  std::vector<int> tracked_age, tracked_miss;
  std::vector<cv::Point2f> tracked_prev;
  std::vector<cv::Point2f> tracked_vel;
  std::vector<std::vector<float>> tracked_desc;

  const float MAX_FB_ERR = 0.35f;
  const float SNAP_RADIUS = 3.0f;
  const int MAX_MISS = 2;
  const int MAX_AGE = 60;
  const float MIN_NEW_DIST = g_min_new_dist.load();

  std::vector<cv::Point2f> rot_pred(pts_prev.size(), cv::Point2f(-1.f, -1.f));
  if (have_rot_pred && !pts_prev.empty()) {
    auto cam = camera_calib.at(cam_id);
    for (size_t i = 0; i < pts_prev.size(); i++) {
      cv::Point2f un = cam->undistort_cv(pts_prev[i]);
      cv::Vec3d rot = R_rel * cv::Vec3d(un.x, un.y, 1.0);
      if (rot[2] > 1e-6) {
        Eigen::Vector2d px = cam->distort_d(Eigen::Vector2d(rot[0] / rot[2], rot[1] / rot[2]));
        if (px.allFinite()) rot_pred[i] = cv::Point2f((float)px.x(), (float)px.y());
      }
    }
  }

  if (!prev.empty() && prev.size() == cur.size() && !pts_prev.empty()) {
    std::vector<cv::Point2f> fwd, bwd;
    std::vector<uchar> st1, st2;
    std::vector<float> err;
    bool usedLightGlue = false;
    const bool glReady = g_lightglue_on.load(std::memory_order_relaxed) != 0 && sp_lightglue_ready() != 0;
    if (glReady && !desc_prev.empty() && det.desc.size() >= det.kp.size() && det.kp.size() > 1) {
      bool descOk = desc_prev.size() >= pts_prev.size();
      for (size_t i = 0; descOk && i < pts_prev.size(); i++) descOk = desc_prev[i].size() == 256;
      if (descOk) {
        std::vector<float> pk, pd, ck, cd;
        pk.reserve(pts_prev.size() * 2); pd.reserve(pts_prev.size() * 256);
        ck.reserve(det.kp.size() * 2); cd.reserve(det.kp.size() * 256);
        for (size_t i = 0; i < pts_prev.size(); i++) {
          pk.push_back(pts_prev[i].x); pk.push_back(pts_prev[i].y);
          pd.insert(pd.end(), desc_prev[i].begin(), desc_prev[i].end());
        }
        for (size_t i = 0; i < det.kp.size(); i++) {
          ck.push_back(det.kp[i].x); ck.push_back(det.kp[i].y);
          if (i < det.desc.size() && det.desc[i].size() == 256) cd.insert(cd.end(), det.desc[i].begin(), det.desc[i].end());
          else cd.insert(cd.end(), 256, 0.f);
        }
        std::vector<int> pairs(pts_prev.size() * 2, -1);
        const int np = sp_lightglue_match(pk.data(), pd.data(), (int)pts_prev.size(),
                                          ck.data(), cd.data(), (int)det.kp.size(), pairs.data(), (int)pts_prev.size());
        if (np > 0) {
          fwd.assign(pts_prev.size(), cv::Point2f(0.f, 0.f));
          bwd = pts_prev;
          st1.assign(pts_prev.size(), 0);
          st2.assign(pts_prev.size(), 1);
          int accepted = 0, refined = 0, gated = 0;
          std::unordered_map<size_t, cv::Point2f> slam_pred_snapshot;
          {
            std::lock_guard<std::mutex> lck(g_slam_pred_mtx);
            slam_pred_snapshot = g_slam_pred;
          }
          std::vector<int> lg_idx;
          std::vector<cv::Point2f> lg_prev, lg_seed, lg_fwd;
          lg_idx.reserve(np); lg_prev.reserve(np); lg_seed.reserve(np); lg_fwd.reserve(np);
          for (int m = 0; m < np; m++) {
            const int a = pairs[m * 2], b = pairs[m * 2 + 1];
            if (a >= 0 && a < (int)pts_prev.size() && b >= 0 && b < (int)det.kp.size()) {
              // Full depth prediction where a SLAM landmark exists: its 3D
              // position was projected into this camera by VioManager.
              if (a < (int)ids_prev.size()) {
                auto it_slam = slam_pred_snapshot.find(ids_prev[a]);
                if (it_slam != slam_pred_snapshot.end()) {
                  if (cv::norm(det.kp[b] - it_slam->second) > 70.0f) { gated++; continue; }
                }
              }
              // VIO rotation prediction gate (translation ignored): the point
              // should land near distort(R_cur^T * R_prev * undistort(p_prev)).
              if (have_rot_pred && a < (int)rot_pred.size() && rot_pred[a].x >= 0.f) {
                if (cv::norm(det.kp[b] - rot_pred[a]) > 90.0f) { gated++; continue; }
              }
              // Constant-velocity prediction gate: a LightGlue match far from
              // where this track was heading is allowed only if it is plausibly
              // explained by its own previous displacement.
              if (a < (int)vel_prev.size()) {
                const cv::Point2f v = vel_prev[a];
                const float speed = (float)cv::norm(v);
                if (speed > 0.25f) {
                  const cv::Point2f pred = pts_prev[a] + v;
                  const float gate = 30.0f + 2.0f * speed;
                  if (cv::norm(det.kp[b] - pred) > gate) { gated++; continue; }
                }
              }
              lg_idx.push_back(a);
              lg_prev.push_back(pts_prev[a]);
              lg_seed.push_back(det.kp[b]);
              lg_fwd.push_back(det.kp[b]);
            }
          }
          // Refine every LightGlue integer match det.kp[b] to ~0.05 px sub-pixel precision
          // using PyrLK seeded at det.kp[b] (handles big jumps while giving OpenVINS smooth tracks).
          if (!lg_idx.empty()) {
            auto crit = cv::TermCriteria(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 30, 0.01);
            std::vector<uchar> r_st1, r_st2;
            std::vector<float> r_err;
            cv::calcOpticalFlowPyrLK(prev, cur, lg_prev, lg_fwd, r_st1, r_err, cv::Size(15, 15), 2, crit, cv::OPTFLOW_USE_INITIAL_FLOW);
            std::vector<cv::Point2f> lg_bwd = lg_prev;
            cv::calcOpticalFlowPyrLK(cur, prev, lg_fwd, lg_bwd, r_st2, r_err, cv::Size(15, 15), 2, crit, cv::OPTFLOW_USE_INITIAL_FLOW);
            for (size_t k = 0; k < lg_idx.size(); k++) {
              const int a = lg_idx[k];
              const bool subOk = r_st1[k] && r_st2[k] &&
                  lg_fwd[k].x >= 1.f && lg_fwd[k].y >= 1.f &&
                  lg_fwd[k].x <= cur.cols - 2.f && lg_fwd[k].y <= cur.rows - 2.f &&
                  cv::norm(lg_fwd[k] - lg_seed[k]) <= 2.5f &&
                  cv::norm(lg_bwd[k] - lg_prev[k]) <= 0.75f;
              if (subOk) {
                fwd[a] = lg_fwd[k];
                bwd[a] = lg_bwd[k];
                st1[a] = 1;
                st2[a] = 1;
                refined++;
                accepted++;
              } else {
                // Fall back to the LightGlue match only if KLT didn't reject it as a wild mismatch.
                fwd[a] = lg_seed[k];
                bwd[a] = lg_prev[k];
                st1[a] = 1;
                st2[a] = 1;
                accepted++;
              }
            }
          }
          // Optional hybrid fallback: keep KLT tracks that LightGlue did not
          // match, or that the prediction gate rejected. Off by default; selected
          // with Settings -> Development -> Keep KLT fallback.
          if (g_klt_fallback_on.load() && accepted < (int)pts_prev.size()) {
            std::vector<cv::Point2f> um_prev;
            std::vector<int> um_idx;
            for (size_t i = 0; i < pts_prev.size(); i++) {
              if (!st1[i]) { um_prev.push_back(pts_prev[i]); um_idx.push_back((int)i); }
            }
            if (!um_prev.empty()) {
              auto crit = cv::TermCriteria(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 30, 0.01);
              std::vector<cv::Point2f> um_fwd, um_bwd;
              std::vector<uchar> um_st1, um_st2;
              std::vector<float> um_err;
              cv::calcOpticalFlowPyrLK(prev, cur, um_prev, um_fwd, um_st1, um_err, cv::Size(15, 15), 2, crit);
              cv::calcOpticalFlowPyrLK(cur, prev, um_fwd, um_bwd, um_st2, um_err, cv::Size(15, 15), 2, crit);
              for (size_t k = 0; k < um_idx.size(); k++) {
                if (!um_st1[k] || !um_st2[k]) continue;
                const int a = um_idx[k];
                fwd[a] = um_fwd[k];
                bwd[a] = um_bwd[k];
                st1[a] = 1;
              }
            }
          }
          usedLightGlue = accepted > 0;
          static int pred_log = 0;
          if (++pred_log % 30 == 0)
            __android_log_print(ANDROID_LOG_INFO, "SuperPointTrack",
                                "lightglue accepted=%d refined=%d gated=%d rot_pred=%d (prediction)",
                                accepted, refined, gated, have_rot_pred ? 1 : 0);
        }
      }
    }
    if (!usedLightGlue) {
      auto crit = cv::TermCriteria(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 30, 0.01);
      cv::calcOpticalFlowPyrLK(prev, cur, pts_prev, fwd, st1, err, cv::Size(15, 15), 2, crit);
      cv::calcOpticalFlowPyrLK(cur, prev, fwd, bwd, st2, err, cv::Size(15, 15), 2, crit);
    }

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

      if (laserBandHit(fwd[i].x, fwd[i].y)) {
        pushLost(cam_id, (int)ids_prev[i], age_prev[i], pts_prev[i].x, pts_prev[i].y, last_desc);
        continue;
      }
      // Preserve KLT sub-pixel position (fwd[i]); use SuperPoint only to validate support,
      // never snap a 0.05 px KLT track back onto an integer SuperPoint pixel.
      cv::Point2f pt = fwd[i];
      int miss = miss_prev[i];
      bool supported = false;
      int sup_idx = -1;
      float bestd = SNAP_RADIUS * SNAP_RADIUS;
      for (size_t di = 0; di < det.kp.size(); di++) {
        float dx = det.kp[di].x - fwd[i].x, dy = det.kp[di].y - fwd[i].y;
        float d2 = dx * dx + dy * dy;
        if (d2 < bestd) { bestd = d2; supported = true; sup_idx = (int)di; }
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
      tracked_vel.push_back(pt - pts_prev[i]);
      if (supported && sup_idx >= 0 && sup_idx < (int)det.desc.size() && det.desc[sup_idx].size() == 256) {
        tracked_desc.push_back(det.desc[sup_idx]);
      } else if (last_desc != nullptr) {
        tracked_desc.push_back(*last_desc);
      } else {
        tracked_desc.emplace_back();
      }
    }

    // Geometric RANSAC outlier rejection on undistorted KLT pairs (matches OpenVINS TrackKLT).
    if (tracked.size() >= 8) {
      float dispSum = 0.0f;
      for (size_t i = 0; i < tracked.size(); i++) dispSum += cv::norm(tracked[i] - tracked_prev[i]);
      if (dispSum / (float)tracked.size() > 0.5f) {
        auto cam = camera_calib.at(cam_id);
        std::vector<cv::Point2f> pts0_n(tracked.size()), pts1_n(tracked.size());
        for (size_t i = 0; i < tracked.size(); i++) {
          pts0_n[i] = cam->undistort_cv(tracked_prev[i]);
          pts1_n[i] = cam->undistort_cv(tracked[i]);
        }
        double maxF = std::max(cam->get_K()(0, 0), cam->get_K()(1, 1));
        if (maxF > 1.0) {
          // SuperVINS-style F-RANSAC: a 3 px base threshold, widened when the
          // recent inlier rate says the scene/matches are unusually noisy.
          static float s_inlier_hist = 1.0f;
          const double ransac_px = s_inlier_hist < 0.55f ? 4.0 : (s_inlier_hist > 0.85f ? 2.5 : 3.0);
          std::vector<uchar> mask_rsc;
          cv::findFundamentalMat(pts0_n, pts1_n, cv::FM_RANSAC, ransac_px / maxF, 0.999, mask_rsc);
          if (mask_rsc.size() == tracked.size()) {
            size_t inliers = 0;
            for (uchar m : mask_rsc) if (m) inliers++;
            s_inlier_hist = 0.8f * s_inlier_hist + 0.2f * ((float)inliers / (float)tracked.size());
            if (inliers >= 8 && inliers < tracked.size()) {
              std::vector<cv::Point2f> f_pts, f_prev;
              std::vector<size_t> f_ids;
              std::vector<int> f_age, f_miss;
              std::vector<std::vector<float>> f_desc;
              std::vector<cv::Point2f> f_vel;
              f_pts.reserve(inliers); f_prev.reserve(inliers); f_ids.reserve(inliers);
              f_age.reserve(inliers); f_miss.reserve(inliers); f_desc.reserve(inliers); f_vel.reserve(inliers);
              for (size_t i = 0; i < tracked.size(); i++) {
                if (!mask_rsc[i]) continue;
                f_pts.push_back(tracked[i]);
                f_prev.push_back(tracked_prev[i]);
                f_ids.push_back(tracked_ids[i]);
                f_age.push_back(tracked_age[i]);
                f_miss.push_back(tracked_miss[i]);
                f_desc.push_back(std::move(tracked_desc[i]));
                f_vel.push_back(tracked_vel[i]);
              }
              tracked = std::move(f_pts); tracked_prev = std::move(f_prev);
              tracked_ids = std::move(f_ids); tracked_age = std::move(f_age);
              tracked_miss = std::move(f_miss); tracked_desc = std::move(f_desc); tracked_vel = std::move(f_vel);
            }
          }
        }
      }
    }
  }

  std::vector<cv::Point2f> all_pts = tracked;
  std::vector<size_t> all_ids = tracked_ids;
  std::vector<int> all_age = tracked_age;
  std::vector<int> all_miss = tracked_miss;
  std::vector<std::vector<float>> all_desc = tracked_desc;
  std::vector<cv::Point2f> all_vel = tracked_vel;

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
          const int MAX_REACQ_PER_FRAME = 5;
          for (int i = 0; i < nD && reacq < MAX_REACQ_PER_FRAME; i++) {
            int a = bestA[i];
            if (a < 0 || lostUsed[a]) continue;
            if (bestSim[i] < 0.90f || (bestSim[i] - secondSim[i]) < 0.05f) continue;
            int b = detIdx[i];
            if (laserBandHit(det.kp[b].x, det.kp[b].y)) continue;
            // geometry gate: a recovered track must reappear near where it was last seen
            int missed = 30 - lost[a].ttl;
            float radius = 18.0f + 2.0f * (float)missed;
            float dx = det.kp[b].x - lost[a].x;
            float dy = det.kp[b].y - lost[a].y;
            if (dx * dx + dy * dy > radius * radius) { rejectedGate++; continue; }
            all_pts.push_back(det.kp[b]);
            all_ids.push_back((size_t)lost[a].id);
            all_age.push_back(lost[a].age + 1);
            all_miss.push_back(0);
            all_desc.push_back(det.desc[b]);
            all_vel.push_back(cv::Point2f(0.f, 0.f));
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
    if (laserBandHit(d.x, d.y)) continue;
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
    all_vel.push_back(cv::Point2f(0.f, 0.f));
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
    std::vector<std::vector<float>> d2v; std::vector<cv::Point2f> v2;
    for (size_t k : idx) { p2.push_back(all_pts[k]); i2.push_back(all_ids[k]); a2.push_back(all_age[k]); m2.push_back(all_miss[k]); d2v.push_back(all_desc[k]); v2.push_back(all_vel[k]); }
    all_pts = std::move(p2); all_ids = std::move(i2); all_age = std::move(a2); all_miss = std::move(m2);
    all_desc = std::move(d2v); all_vel = std::move(v2);
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
  g_prev_vel[cam_id] = all_vel;
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

extern "C" void setKltFallbackEnabled(int enabled) {
  g_klt_fallback_on.store(enabled != 0 ? 1 : 0);
}

extern "C" void setLightGlueEnabled(int enabled) {
  g_lightglue_on.store(enabled != 0 ? 1 : 0);
}

// Clear all cross-session tracker state (lost-track pool, descriptor history, pending frames).
void superpoint_reset() {
  {
    std::lock_guard<std::mutex> lck(ov_core::g_lost_mtx);
    ov_core::g_lost.clear();
    ov_core::g_prev_desc.clear();
    ov_core::g_prev_vel.clear();
    {
      std::lock_guard<std::mutex> lck(ov_core::g_rot_mtx);
      ov_core::g_rot_valid = false;
      ov_core::g_prev_rot_cam_to_world.clear();
      std::lock_guard<std::mutex> l2(ov_core::g_slam_pred_mtx);
      ov_core::g_slam_pred.clear();
    }
  }
  {
    std::lock_guard<std::mutex> lck(ov_core::g_sp_mtx);
    ov_core::g_sp_frames.clear();
  }
  ov_core::g_reacq_total = 0;
}
