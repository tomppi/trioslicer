#include "supervins_frontend.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include <android/log.h>
#include <opencv2/calib3d/calib3d.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/video/tracking.hpp>

#define FTAG "SuperVinsTrack"

extern "C" int sp_lightglue_ready();
extern "C" int sp_lightglue_match(const float *kp0, const float *desc0, int n0,
                                  const float *kp1, const float *desc1, int n1,
                                  int *pairs, int maxPairs);

namespace supervins {

namespace {

constexpr int LG_MAX_KP = 400;
constexpr int DESC_DIM = 64;

std::atomic<int> g_max_tracks{180};
std::atomic<float> g_min_dist{8.0f};
std::atomic<int> g_lightglue_on{1};
std::atomic<int> g_klt_fallback_on{1};
std::atomic<float> g_ransac_px{3.0f};
std::atomic<int> g_reacq_enabled{0};

std::atomic<float> g_band_cx{0.f}, g_band_cy{0.f}, g_band_nx{0.f}, g_band_ny{1.f}, g_band_hw{0.f};
std::atomic<int> g_band_on{0};

inline bool laserBandHit(float x, float y) {
  if (!g_band_on.load(std::memory_order_relaxed)) return false;
  const float d = g_band_nx.load(std::memory_order_relaxed) * (x - g_band_cx.load(std::memory_order_relaxed)) +
                  g_band_ny.load(std::memory_order_relaxed) * (y - g_band_cy.load(std::memory_order_relaxed));
  return std::fabs(d) < g_band_hw.load(std::memory_order_relaxed);
}

inline bool inBounds(const cv::Point2f &p, int w, int h) {
  return std::isfinite(p.x) && std::isfinite(p.y) && p.x >= 2.0f && p.y >= 2.0f && p.x <= w - 3.0f && p.y <= h - 3.0f;
}

// Refine integer-grid SuperPoint detections (8x8 heatmap quantization) to true sub-pixel corners
// via local gradient-orthogonality cornerSubPix, clamped tightly to the original detection so
// SuperPoint's descriptor correspondence is never drifted onto a neighboring texture edge.
void refineCornerSubPixSafe(const cv::Mat &gray, std::vector<cv::Point2f> &pts, float maxShiftPx = 1.25f) {
  if (pts.empty() || gray.empty() || gray.type() != CV_8UC1) return;
  const int W = gray.cols, H = gray.rows;
  std::vector<cv::Point2f> work;
  std::vector<size_t> mapIdx;
  work.reserve(pts.size());
  mapIdx.reserve(pts.size());
  for (size_t i = 0; i < pts.size(); ++i) {
    const cv::Point2f &p = pts[i];
    if (std::isfinite(p.x) && std::isfinite(p.y) && p.x >= 5.0f && p.y >= 5.0f && p.x <= W - 6.0f && p.y <= H - 6.0f) {
      work.push_back(p);
      mapIdx.push_back(i);
    }
  }
  if (work.empty()) return;
  auto crit = cv::TermCriteria(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 15, 0.01);
  cv::cornerSubPix(gray, work, cv::Size(4, 4), cv::Size(-1, -1), crit);
  const float maxShift2 = maxShiftPx * maxShiftPx;
  for (size_t k = 0; k < work.size(); ++k) {
    const size_t idx = mapIdx[k];
    const cv::Point2f &orig = pts[idx];
    const cv::Point2f &ref = work[k];
    const float dx = ref.x - orig.x, dy = ref.y - orig.y;
    if (inBounds(ref, W, H) && !laserBandHit(ref.x, ref.y) && (dx * dx + dy * dy) <= maxShift2) {
      pts[idx] = ref;
    }
  }
}

}  // namespace

void setLaserBand(float cx, float cy, float nx, float ny, float half_width, int on) {
  g_band_cx.store(cx, std::memory_order_relaxed);
  g_band_cy.store(cy, std::memory_order_relaxed);
  g_band_nx.store(nx, std::memory_order_relaxed);
  g_band_ny.store(ny, std::memory_order_relaxed);
  g_band_hw.store(half_width, std::memory_order_relaxed);
  g_band_on.store(on ? 1 : 0, std::memory_order_relaxed);
}

void setTrackCaps(int maxTracks, float minNewDist) {
  const int mt = std::max(20, std::min(400, maxTracks));
  const float md = std::max(2.0f, std::min(60.0f, minNewDist));
  g_max_tracks.store(mt, std::memory_order_relaxed);
  g_min_dist.store(md, std::memory_order_relaxed);
}

void setLightGlueEnabled(int enabled) {
  g_lightglue_on.store(enabled ? 1 : 0, std::memory_order_relaxed);
}

void setKltFallbackEnabled(int enabled) {
  g_klt_fallback_on.store(enabled ? 1 : 0, std::memory_order_relaxed);
}

void setRansacThresholdPx(float px) {
  g_ransac_px.store(std::max(1.0f, std::min(10.0f, px)), std::memory_order_relaxed);
}

void setReacqEnabled(int enabled) {
  g_reacq_enabled.store(enabled ? 1 : 0, std::memory_order_relaxed);
}

void SuperVinsFrontend::reset(const CameraModel &cam) {
  cam_ = cam;
  prev_gray_.release();
  prev_ts_ = -1.0;
  next_id_ = 1;
  inlier_hist_ = 1.0f;
  log_counter_ = 0;
  last_tracked_ = 0;
  last_total_ = 0;
  prev_kp_.clear();
  prev_un_.clear();
  prev_ids_.clear();
  prev_age_.clear();
  prev_desc_.clear();
}

FeaturePacket SuperVinsFrontend::trackFrame(
    double timestamp,
    const cv::Mat &gray,
    const float *kp_xy,
    const float *desc,
    int n_det,
    const std::map<int, Eigen::Vector3d> &predicted_pts_cam) {
  FeaturePacket packet;
  if (gray.empty() || gray.channels() != 1) return packet;

  const int W = gray.cols;
  const int H = gray.rows;
  const float min_dist = g_min_dist.load(std::memory_order_relaxed);
  const float min_dist2 = min_dist * min_dist;
  const int cfg_max_tracks = g_max_tracks.load(std::memory_order_relaxed);
  const bool use_lg = g_lightglue_on.load(std::memory_order_relaxed) != 0 && sp_lightglue_ready() != 0;
  const int max_tracks = use_lg ? std::min(cfg_max_tracks, LG_MAX_KP) : cfg_max_tracks;

  // Step 1: Collect top score-ranked SuperPoint detections (native NMS-4) after bounds & laser-band mask.
  // Do NOT apply greedy large-radius suppression before LightGlue: swapping score order between nearby
  // corners across frames destroys repeatability and halves LightGlue match counts. Spatial spacing
  // (min_dist) is enforced in Step 4 when adding NEW untracked points.
  std::vector<cv::Point2f> det_kp;
  std::vector<std::vector<float>> det_desc;
  const int det_cap = use_lg ? LG_MAX_KP : std::max(cfg_max_tracks, 150);
  det_kp.reserve(std::min(std::max(n_det, 0), det_cap));
  det_desc.reserve(std::min(std::max(n_det, 0), det_cap));

  for (int i = 0; i < n_det && (int)det_kp.size() < det_cap; ++i) {
    cv::Point2f p(kp_xy[2 * i], kp_xy[2 * i + 1]);
    if (!inBounds(p, W, H) || laserBandHit(p.x, p.y)) continue;
    det_kp.push_back(p);
    std::vector<float> d(DESC_DIM);
    std::memcpy(d.data(), desc + (size_t)i * DESC_DIM, DESC_DIM * sizeof(float));
    det_desc.push_back(std::move(d));
  }

  std::vector<cv::Point2f> tracked_kp;
  std::vector<Eigen::Vector2d> tracked_un;
  std::vector<Eigen::Vector2d> tracked_prev_un;
  std::vector<cv::Point2f> tracked_prev_kp;
  std::vector<int> tracked_ids;
  std::vector<int> tracked_age;
  std::vector<std::vector<float>> tracked_desc;
  std::vector<char> det_used(det_kp.size(), 0);

  int lg_matches = 0;
  int lg_gated = 0;
  int lg_refined = 0;
  int klt_carried = 0;

  // Step 2: Frame-to-frame matching against previous frame.
  if (!prev_gray_.empty() && prev_gray_.size() == gray.size() && !prev_kp_.empty()) {
    std::vector<char> prev_matched(prev_kp_.size(), 0);
    std::vector<char> prev_gated(prev_kp_.size(), 0);

    if (use_lg && det_kp.size() >= 2 && prev_desc_.size() == prev_kp_.size()) {
      const int n_prev = std::min((int)prev_kp_.size(), LG_MAX_KP);
      const int n_cur = std::min((int)det_kp.size(), LG_MAX_KP);

      std::vector<float> pk((size_t)n_prev * 2);
      std::vector<float> pd((size_t)n_prev * DESC_DIM);
      std::vector<float> ck((size_t)n_cur * 2);
      std::vector<float> cd((size_t)n_cur * DESC_DIM);
      for (int i = 0; i < n_prev; ++i) {
        pk[2 * i] = prev_kp_[i].x;
        pk[2 * i + 1] = prev_kp_[i].y;
        std::memcpy(pd.data() + (size_t)i * DESC_DIM, prev_desc_[i].data(), DESC_DIM * sizeof(float));
      }
      for (int i = 0; i < n_cur; ++i) {
        ck[2 * i] = det_kp[i].x;
        ck[2 * i + 1] = det_kp[i].y;
        std::memcpy(cd.data() + (size_t)i * DESC_DIM, det_desc[i].data(), DESC_DIM * sizeof(float));
      }

      std::vector<int> pairs((size_t)n_prev * 2, -1);
      const int np = sp_lightglue_match(pk.data(), pd.data(), n_prev, ck.data(), cd.data(), n_cur, pairs.data(), n_prev);
      lg_matches = np;

      std::vector<int> cand_a, cand_b;
      std::vector<cv::Point2f> lk_prev, lk_seed, lk_fwd;
      cand_a.reserve(np);
      cand_b.reserve(np);
      lk_prev.reserve(np);
      lk_seed.reserve(np);
      lk_fwd.reserve(np);

      for (int m = 0; m < np; ++m) {
        const int a = pairs[2 * m];
        const int b = pairs[2 * m + 1];
        if (a < 0 || a >= n_prev || b < 0 || b >= n_cur) continue;
        if (prev_matched[a] || det_used[b]) continue;

        // SuperVINS 3D landmark prediction gate when available from Estimator.
        auto it_pred = predicted_pts_cam.find(prev_ids_[a]);
        if (it_pred != predicted_pts_cam.end()) {
          cv::Point2f pred_px;
          if (cam_.project(it_pred->second, pred_px)) {
            if (cv::norm(det_kp[b] - pred_px) > 90.0f) {
              lg_gated++;
              prev_gated[a] = 1;
              continue;
            }
          }
        }

        prev_matched[a] = 1;
        det_used[b] = 1;
        cand_a.push_back(a);
        cand_b.push_back(b);
        lk_prev.push_back(prev_kp_[a]);
        lk_seed.push_back(det_kp[b]);
        lk_fwd.push_back(det_kp[b]);
      }

      // Refine LightGlue integer keypoints to sub-pixel accuracy with seeded PyrLK.
      // If PyrLK cannot refine a point cleanly, keep the LightGlue match itself.
      if (!cand_a.empty()) {
        auto crit = cv::TermCriteria(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 25, 0.01);
        std::vector<uchar> st1, st2;
        std::vector<float> err;
        cv::calcOpticalFlowPyrLK(prev_gray_, gray, lk_prev, lk_fwd, st1, err, cv::Size(21, 21), 3, crit, cv::OPTFLOW_USE_INITIAL_FLOW);
        std::vector<cv::Point2f> lk_bwd = lk_prev;
        cv::calcOpticalFlowPyrLK(gray, prev_gray_, lk_fwd, lk_bwd, st2, err, cv::Size(21, 21), 3, crit, cv::OPTFLOW_USE_INITIAL_FLOW);

        // Sub-pixel refine LightGlue fallback seeds (where PyrLK could not lock) so no raw integer
        // SuperPoint grid coordinates enter Ceres.
        std::vector<cv::Point2f> fallback_seeds;
        std::vector<size_t> fallback_k;
        for (size_t k = 0; k < cand_a.size(); ++k) {
          const bool lkOk = st1[k] && st2[k] && inBounds(lk_fwd[k], W, H) && !laserBandHit(lk_fwd[k].x, lk_fwd[k].y) &&
                            cv::norm(lk_fwd[k] - lk_seed[k]) <= 2.0f && cv::norm(lk_bwd[k] - lk_prev[k]) <= 0.40f;
          if (!lkOk) {
            fallback_seeds.push_back(lk_seed[k]);
            fallback_k.push_back(k);
          }
        }
        if (!fallback_seeds.empty()) {
          refineCornerSubPixSafe(gray, fallback_seeds, 1.0f);
          for (size_t j = 0; j < fallback_k.size(); ++j) lk_seed[fallback_k[j]] = fallback_seeds[j];
        }

        for (size_t k = 0; k < cand_a.size(); ++k) {
          const int a = cand_a[k];
          const int b = cand_b[k];
          cv::Point2f pt = lk_seed[k];
          if (st1[k] && st2[k] && inBounds(lk_fwd[k], W, H) && !laserBandHit(lk_fwd[k].x, lk_fwd[k].y) &&
              cv::norm(lk_fwd[k] - lk_seed[k]) <= 2.0f &&
              cv::norm(lk_bwd[k] - lk_prev[k]) <= 0.40f) {
            pt = lk_fwd[k];
            lg_refined++;
          }
          tracked_kp.push_back(pt);
          tracked_un.push_back(cam_.undistort(pt.x, pt.y));
          tracked_prev_kp.push_back(prev_kp_[a]);
          tracked_prev_un.push_back(prev_un_[a]);
          tracked_ids.push_back(prev_ids_[a]);
          tracked_age.push_back(prev_age_[a] + 1);
          tracked_desc.push_back(det_desc[b]);
        }
      }

      // Track-continuity PyrLK bridge for existing tracks that temporarily fell out of
      // SuperPoint's top-100 or suffered single-frame motion blur. Preserves the track's
      // SuperPoint descriptor (or refreshes it if a nearby unused detection matches) so
      // LightGlue can re-match it on the next sharp frame, and passes through F-RANSAC.
      if (g_klt_fallback_on.load(std::memory_order_relaxed) != 0) {
        std::vector<int> um_idx;
        um_idx.reserve(prev_kp_.size());
        for (size_t i = 0; i < prev_kp_.size(); ++i) {
          if (!prev_matched[i] && !prev_gated[i]) {
            um_idx.push_back((int)i);
          }
        }
        // Prefer carrying longer-lived tracks first so GlobalSFM and Ceres (>=4 obs) stay well-constrained.
        std::stable_sort(um_idx.begin(), um_idx.end(), [&](int a, int b) {
          return prev_age_[a] > prev_age_[b];
        });
        const int bridge_cap = std::max(0, max_tracks - 15 - (int)tracked_kp.size());
        if (!um_idx.empty() && bridge_cap > 0) {
          std::vector<cv::Point2f> um_prev;
          um_prev.reserve(um_idx.size());
          for (int idx : um_idx) um_prev.push_back(prev_kp_[idx]);
          auto crit = cv::TermCriteria(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 25, 0.01);
          std::vector<cv::Point2f> um_fwd, um_bwd;
          std::vector<uchar> st1, st2;
          std::vector<float> err;
          cv::calcOpticalFlowPyrLK(prev_gray_, gray, um_prev, um_fwd, st1, err, cv::Size(21, 21), 3, crit);
          cv::calcOpticalFlowPyrLK(gray, prev_gray_, um_fwd, um_bwd, st2, err, cv::Size(21, 21), 3, crit);
          for (size_t k = 0; k < um_idx.size() && klt_carried < bridge_cap; ++k) {
            if (!st1[k] || !st2[k]) continue;
            if (!inBounds(um_fwd[k], W, H) || laserBandHit(um_fwd[k].x, um_fwd[k].y)) continue;
            if (cv::norm(um_bwd[k] - um_prev[k]) > 0.5f) continue;
            const int a = um_idx[k];
            // Refresh descriptor from an unused nearby SuperPoint detection if within 4 px.
            int near_det = -1;
            float best_d2 = 16.0f;
            for (size_t b = 0; b < det_kp.size(); ++b) {
              if (det_used[b]) continue;
              const float dx = um_fwd[k].x - det_kp[b].x;
              const float dy = um_fwd[k].y - det_kp[b].y;
              const float d2 = dx * dx + dy * dy;
              if (d2 < best_d2) {
                best_d2 = d2;
                near_det = (int)b;
              }
            }
            if (near_det >= 0) det_used[near_det] = 1;
            tracked_kp.push_back(um_fwd[k]);
            tracked_un.push_back(cam_.undistort(um_fwd[k].x, um_fwd[k].y));
            tracked_prev_kp.push_back(prev_kp_[a]);
            tracked_prev_un.push_back(prev_un_[a]);
            tracked_ids.push_back(prev_ids_[a]);
            tracked_age.push_back(prev_age_[a] + 1);
            tracked_desc.push_back(near_det >= 0 ? det_desc[near_det] : prev_desc_[a]);
            klt_carried++;
          }
        }
      }
    } else if (!use_lg) {
      // KLT fallback when LightGlue NPU is unavailable or explicitly disabled in settings.
      auto crit = cv::TermCriteria(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 25, 0.01);
      std::vector<cv::Point2f> fwd, bwd;
      std::vector<uchar> st1, st2;
      std::vector<float> err;
      cv::calcOpticalFlowPyrLK(prev_gray_, gray, prev_kp_, fwd, st1, err, cv::Size(15, 15), 2, crit);
      cv::calcOpticalFlowPyrLK(gray, prev_gray_, fwd, bwd, st2, err, cv::Size(15, 15), 2, crit);
      for (size_t i = 0; i < prev_kp_.size(); ++i) {
        if (!st1[i] || !st2[i]) continue;
        if (!inBounds(fwd[i], W, H) || laserBandHit(fwd[i].x, fwd[i].y)) continue;
        if (cv::norm(bwd[i] - prev_kp_[i]) > 0.5f) continue;
        tracked_kp.push_back(fwd[i]);
        tracked_un.push_back(cam_.undistort(fwd[i].x, fwd[i].y));
        tracked_prev_kp.push_back(prev_kp_[i]);
        tracked_prev_un.push_back(prev_un_[i]);
        tracked_ids.push_back(prev_ids_[i]);
        tracked_age.push_back(prev_age_[i] + 1);
        tracked_desc.push_back(prev_desc_[i]);
        klt_carried++;
      }
    }

    // Step 3: SuperVINS Fundamental-matrix RANSAC on undistorted coordinates.
    if (tracked_kp.size() >= 8) {
      float disp_sum = 0.0f;
      for (size_t i = 0; i < tracked_kp.size(); ++i) {
        disp_sum += (float)cv::norm(tracked_kp[i] - tracked_prev_kp[i]);
      }
      if (disp_sum / (float)tracked_kp.size() > 0.5f) {
        const double max_f = std::max(cam_.fx, cam_.fy);
        if (max_f > 1.0) {
          std::vector<cv::Point2f> pts0(tracked_kp.size()), pts1(tracked_kp.size());
          for (size_t i = 0; i < tracked_kp.size(); ++i) {
            pts0[i] = cv::Point2f((float)tracked_prev_un[i].x(), (float)tracked_prev_un[i].y());
            pts1[i] = cv::Point2f((float)tracked_un[i].x(), (float)tracked_un[i].y());
          }
          const float base_px = g_ransac_px.load(std::memory_order_relaxed);
          const double ransac_px = inlier_hist_ < 0.55f ? (double)base_px * 1.33
                                 : (inlier_hist_ > 0.85f ? (double)base_px * 0.85 : (double)base_px);
          std::vector<uchar> mask;
          cv::findFundamentalMat(pts0, pts1, cv::FM_RANSAC, ransac_px / max_f, 0.999, mask);
          if (mask.size() == tracked_kp.size()) {
            size_t inliers = 0;
            for (uchar m : mask) if (m) inliers++;
            inlier_hist_ = 0.8f * inlier_hist_ + 0.2f * ((float)inliers / (float)tracked_kp.size());
            if (inliers >= 8 && inliers < tracked_kp.size()) {
              std::vector<cv::Point2f> fk, fpk;
              std::vector<Eigen::Vector2d> fun, fpun;
              std::vector<int> fid, fa;
              std::vector<std::vector<float>> fd;
              fk.reserve(inliers); fpk.reserve(inliers); fun.reserve(inliers); fpun.reserve(inliers);
              fid.reserve(inliers); fa.reserve(inliers); fd.reserve(inliers);
              for (size_t i = 0; i < tracked_kp.size(); ++i) {
                if (!mask[i]) continue;
                fk.push_back(tracked_kp[i]);
                fpk.push_back(tracked_prev_kp[i]);
                fun.push_back(tracked_un[i]);
                fpun.push_back(tracked_prev_un[i]);
                fid.push_back(tracked_ids[i]);
                fa.push_back(tracked_age[i]);
                fd.push_back(std::move(tracked_desc[i]));
              }
              tracked_kp = std::move(fk);
              tracked_prev_kp = std::move(fpk);
              tracked_un = std::move(fun);
              tracked_prev_un = std::move(fpun);
              tracked_ids = std::move(fid);
              tracked_age = std::move(fa);
              tracked_desc = std::move(fd);
            }
          }
        }
      }
    }
  }

  // Step 4: Enforce min_dist spacing among tracked points (prefer longer tracks) + replenish with new SuperPoint detections.
  std::vector<size_t> order(tracked_kp.size());
  for (size_t i = 0; i < order.size(); ++i) order[i] = i;
  std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) {
    return tracked_age[a] > tracked_age[b];
  });

  std::vector<cv::Point2f> all_kp;
  std::vector<Eigen::Vector2d> all_un;
  std::vector<Eigen::Vector2d> all_vel;
  std::vector<int> all_ids;
  std::vector<int> all_age;
  std::vector<std::vector<float>> all_desc;
  all_kp.reserve(max_tracks);
  all_un.reserve(max_tracks);
  all_vel.reserve(max_tracks);
  all_ids.reserve(max_tracks);
  all_age.reserve(max_tracks);
  all_desc.reserve(max_tracks);

  const double dt = (prev_ts_ > 0.0) ? (timestamp - prev_ts_) : 0.0;
  const bool valid_dt = (dt > 1e-4 && dt < 1.0);

  for (size_t idx : order) {
    if ((int)all_kp.size() >= max_tracks) break;
    const cv::Point2f &pt = tracked_kp[idx];
    bool crowded = false;
    // Only collapse true sub-NMS duplicates (4 px); never discard distinct matched LightGlue tracks.
    for (const auto &q : all_kp) {
      const float dx = pt.x - q.x;
      const float dy = pt.y - q.y;
      if (dx * dx + dy * dy < 16.0f) {
        crowded = true;
        break;
      }
    }
    if (crowded) continue;
    Eigen::Vector2d vel(0.0, 0.0);
    if (valid_dt) {
      vel = (tracked_un[idx] - tracked_prev_un[idx]) / dt;
      if (!vel.allFinite()) vel.setZero();
    }
    all_kp.push_back(pt);
    all_un.push_back(tracked_un[idx]);
    all_vel.push_back(vel);
    all_ids.push_back(tracked_ids[idx]);
    all_age.push_back(tracked_age[idx]);
    all_desc.push_back(std::move(tracked_desc[idx]));
  }
  const int kept_tracked = (int)all_kp.size();

  // Refine newly born SuperPoint keypoints to sub-pixel corners BEFORE they enter Ceres/PyrLK,
  // eliminating the initial ~0.5 px 8x8-grid quantization bias on frame 0 of every new track.
  std::vector<cv::Point2f> new_pts;
  std::vector<size_t> new_det_idx;
  new_pts.reserve(std::max(0, max_tracks - (int)all_kp.size()));
  new_det_idx.reserve(std::max(0, max_tracks - (int)all_kp.size()));
  for (size_t b = 0; b < det_kp.size() && (int)(all_kp.size() + new_pts.size()) < max_tracks; ++b) {
    if (det_used[b]) continue;
    const cv::Point2f &pt = det_kp[b];
    bool near = false;
    for (const auto &q : all_kp) {
      const float dx = pt.x - q.x;
      const float dy = pt.y - q.y;
      if (dx * dx + dy * dy < min_dist2) {
        near = true;
        break;
      }
    }
    if (!near) {
      for (const auto &q : new_pts) {
        const float dx = pt.x - q.x;
        const float dy = pt.y - q.y;
        if (dx * dx + dy * dy < min_dist2) {
          near = true;
          break;
        }
      }
    }
    if (near) continue;
    new_pts.push_back(pt);
    new_det_idx.push_back(b);
  }
  refineCornerSubPixSafe(gray, new_pts, 1.25f);
  int added = 0;
  for (size_t j = 0; j < new_pts.size(); ++j) {
    const cv::Point2f &pt = new_pts[j];
    all_kp.push_back(pt);
    all_un.push_back(cam_.undistort(pt.x, pt.y));
    all_vel.push_back(Eigen::Vector2d::Zero());
    all_ids.push_back(next_id_++);
    all_age.push_back(1);
    all_desc.push_back(det_desc[new_det_idx[j]]);
    added++;
  }

  // Step 5: Build SuperVINS feature packet [un_x, un_y, 1.0, u_px, v_px, vx, vy].
  for (size_t i = 0; i < all_kp.size(); ++i) {
    Eigen::Matrix<double, 7, 1> feat;
    feat << all_un[i].x(), all_un[i].y(), 1.0,
            (double)all_kp[i].x, (double)all_kp[i].y,
            all_vel[i].x(), all_vel[i].y();
    packet[all_ids[i]].emplace_back(0, feat);
  }

  last_tracked_ = kept_tracked;
  last_total_ = (int)all_kp.size();
  if (++log_counter_ % 30 == 0) {
    int age4 = 0;
    for (int a : all_age) if (a >= 4) age4++;
    __android_log_print(ANDROID_LOG_INFO, FTAG,
                        "mode=%s det=%d/%d lg=%d gated=%d ref=%d klt=%d tracked=%d age4=%d added=%d total=%d inlier=%.2f",
                        use_lg ? "LightGlue" : "KLT", (int)det_kp.size(), n_det,
                        lg_matches, lg_gated, lg_refined, klt_carried,
                        kept_tracked, age4, added, last_total_, inlier_hist_);
  }

  prev_gray_ = gray.clone();
  prev_ts_ = timestamp;
  prev_kp_ = std::move(all_kp);
  prev_un_ = std::move(all_un);
  prev_ids_ = std::move(all_ids);
  prev_age_ = std::move(all_age);
  prev_desc_ = std::move(all_desc);
  return packet;
}

}  // namespace supervins
