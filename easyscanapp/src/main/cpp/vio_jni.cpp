#include <jni.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>
#include <Eigen/Dense>
#include <opencv2/core.hpp>

#include "supervins/estimator/estimator.h"
#include "supervins/estimator/parameters.h"
#include "supervins_config.h"
#include "supervins_frontend.h"

#define VTAG "SuperVinsJni"

extern "C" int sp_lightglue_init(const char *libDir, const char *modelPath);
extern "C" int sp_lightglue_ready();
extern "C" int sp_lightglue_match(const float *kp0, const float *desc0, int n0,
                                  const float *kp1, const float *desc1, int n1,
                                  int *pairs, int maxPairs);
extern "C" int sp_loop_lightglue_init(const char *libDir, const char *modelPath);
extern "C" int sp_loop_lightglue_ready();
extern "C" int sp_loop_lightglue_match(const float *kp0, const float *desc0, int n0,
                                       const float *kp1, const float *desc1, int n1,
                                       int *pairs, int maxPairs);
extern "C" void sp_lightglue_set_vio_threshold(float v);
extern "C" void sp_lightglue_set_loop_threshold(float v);

namespace {

constexpr int CAM_W = 640;
constexpr int CAM_H = 480;
constexpr int DESC_DIM = 64;

struct ImuSample {
  double ts = 0.0;
  Eigen::Vector3d acc = Eigen::Vector3d::Zero();
  Eigen::Vector3d gyr = Eigen::Vector3d::Zero();
};

struct VioState {
  std::unique_ptr<Estimator> est;
  supervins::SuperVinsFrontend frontend;
  supervins::SuperVinsConfig cfg;

  // Separate locks: imu_mtx is held only for O(1) IMU push & notify so 480 Hz IMU
  // never stalls behind Ceres optimization (which runs under frame_mtx).
  std::mutex imu_mtx;
  std::condition_variable imu_ready;
  double last_imu_ts = -1.0;
  std::deque<ImuSample> recent_imu;

  std::mutex frame_mtx;
  double last_cam_ts = -1.0;
  unsigned skipped_imu_frames = 0;
  unsigned processed_frames = 0;

  // Snapshot of the most recently processed frame's state (read by getPose/getState/initialized).
  std::mutex state_mtx;
  bool initialized = false;
  bool init_ready_to_move = false;
  double pose_cam_ts = -1.0;
  double current_td = 0.0;
  Eigen::Matrix3d R_WI = Eigen::Matrix3d::Identity();
  Eigen::Vector3d p_IinW = Eigen::Vector3d::Zero();
  Eigen::Vector3d v_IinW = Eigen::Vector3d::Zero();
  Eigen::Vector3d bg = Eigen::Vector3d::Zero();
  Eigen::Vector3d ba = Eigen::Vector3d::Zero();
  Eigen::Matrix3d R_IC = Eigen::Matrix3d::Identity();
  Eigen::Vector3d t_IC = Eigen::Vector3d::Zero();

  std::atomic<bool> shutting_down{false};
};

// Apply SuperVinsConfig to VINS-Fusion global parameter state (under global_init_mtx).
std::mutex g_param_mtx;

void applySuperVinsParametersLocked(const supervins::SuperVinsConfig &cfg) {
  clearParameters();
  USE_IMU = 1;
  NUM_OF_CAM = 1;
  STEREO = 0;
  MULTIPLE_THREAD = 0;
  ROW = cfg.height;
  COL = cfg.width;
  MAX_CNT = cfg.max_cnt;
  MIN_DIST = cfg.min_dist;
  F_THRESHOLD = 1.0;
  ransacReprojThreshold = cfg.ransac_reproj_px;
  MATCHER_THRESHOLD = cfg.matcher_threshold;
  ACC_N = cfg.acc_n;
  ACC_W = cfg.acc_w;
  GYR_N = cfg.gyr_n;
  GYR_W = cfg.gyr_w;
  G = Eigen::Vector3d(0.0, 0.0, cfg.g_norm);
  SOLVER_TIME = cfg.max_solver_time;
  NUM_ITERATIONS = cfg.max_num_iterations;
  MIN_PARALLAX = cfg.keyframe_parallax / FOCAL_LENGTH;
  ESTIMATE_EXTRINSIC = cfg.estimate_extrinsic;
  ESTIMATE_TD = cfg.estimate_td;
  TD = cfg.td;
  RIC.push_back(cfg.R_IC);
  TIC.push_back(cfg.t_IC);
}

bool buildSystem(VioState *st, const std::string &config_dir) {
  supervins::SuperVinsConfig cfg;
  if (!supervins::loadSuperVinsConfigFromDir(config_dir, cfg)) {
    __android_log_print(ANDROID_LOG_ERROR, VTAG, "Failed to load SuperVINS calibration from %s", config_dir.c_str());
    return false;
  }

  std::lock_guard<std::mutex> plk(g_param_mtx);
  applySuperVinsParametersLocked(cfg);

  auto est = std::make_unique<Estimator>();
  est->setParameter();

  supervins::CameraModel cam;
  cam.fx = cfg.fx;
  cam.fy = cfg.fy;
  cam.cx = cfg.cx;
  cam.cy = cfg.cy;
  cam.k1 = cfg.k1;
  cam.k2 = cfg.k2;
  cam.p1 = cfg.p1;
  cam.p2 = cfg.p2;
  cam.width = cfg.width;
  cam.height = cfg.height;

  st->cfg = cfg;
  st->frontend.reset(cam);
  supervins::setTrackCaps(cfg.max_cnt, (float)cfg.min_dist);
  supervins::setRansacThresholdPx((float)cfg.ransac_reproj_px);
  st->est = std::move(est);
  st->current_td = cfg.td;
  st->R_IC = cfg.R_IC;
  st->t_IC = cfg.t_IC;

  __android_log_print(
      ANDROID_LOG_INFO, VTAG,
      "SuperVINS created: K=[%.1f,%.1f,%.1f,%.1f] dist=[%.3f,%.3f,%.4f,%.4f] td=%.4fs(est=%d) "
      "acc_n=%.3f gyr_n=%.4f R_IC=[%.3f,%.3f,%.3f; %.3f,%.3f,%.3f; %.3f,%.3f,%.3f] t_IC=[%.4f,%.4f,%.4f]",
      cfg.fx, cfg.fy, cfg.cx, cfg.cy, cfg.k1, cfg.k2, cfg.p1, cfg.p2,
      cfg.td, cfg.estimate_td, cfg.acc_n, cfg.gyr_n,
      cfg.R_IC(0, 0), cfg.R_IC(0, 1), cfg.R_IC(0, 2),
      cfg.R_IC(1, 0), cfg.R_IC(1, 1), cfg.R_IC(1, 2),
      cfg.R_IC(2, 0), cfg.R_IC(2, 1), cfg.R_IC(2, 2),
      cfg.t_IC.x(), cfg.t_IC.y(), cfg.t_IC.z());
  return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioCreate(JNIEnv *env, jclass, jstring configDirJ) {
  const char *dir = env->GetStringUTFChars(configDirJ, nullptr);
  auto *st = new VioState();
  bool ok = false;
  try {
    ok = buildSystem(st, std::string(dir ? dir : ""));
  } catch (const std::exception &e) {
    __android_log_print(ANDROID_LOG_ERROR, VTAG, "SuperVINS create exception: %s", e.what());
    ok = false;
  }
  env->ReleaseStringUTFChars(configDirJ, dir);
  if (!ok) {
    delete st;
    return 0;
  }
  return reinterpret_cast<jlong>(st);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioDestroy(JNIEnv *, jclass, jlong h) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (!st) return;
  st->shutting_down.store(true);
  st->imu_ready.notify_all();
  {
    std::lock_guard<std::mutex> flk(st->frame_mtx);
    std::lock_guard<std::mutex> ilk(st->imu_mtx);
    std::lock_guard<std::mutex> slk(st->state_mtx);
    st->est.reset();
    st->initialized = false;
  }
  delete st;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioFeedImu(
    JNIEnv *, jclass, jlong h, jfloat ax, jfloat ay, jfloat az,
    jfloat gx, jfloat gy, jfloat gz, jdouble ts) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (!st || st->shutting_down.load(std::memory_order_relaxed) || !std::isfinite(ts)) return;
  if (!std::isfinite(ax) || !std::isfinite(ay) || !std::isfinite(az) ||
      !std::isfinite(gx) || !std::isfinite(gy) || !std::isfinite(gz)) return;
  {
    std::lock_guard<std::mutex> lk(st->imu_mtx);
    if (!st->est || !(ts > st->last_imu_ts)) return;
    Eigen::Vector3d acc(ax, ay, az);
    Eigen::Vector3d gyr(gx, gy, gz);
    st->est->inputIMU(ts, acc, gyr);
    st->last_imu_ts = ts;
    st->recent_imu.push_back(ImuSample{ts, acc, gyr});
    while (st->recent_imu.size() > 600) st->recent_imu.pop_front();
  }
  st->imu_ready.notify_one();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioFeedFrame(
    JNIEnv *env, jclass, jlong h, jbyteArray grayJ, jdouble ts, jfloatArray kpJ, jfloatArray descJ) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (!st || st->shutting_down.load(std::memory_order_relaxed) || !std::isfinite(ts)) return JNI_FALSE;
  if (env->GetArrayLength(grayJ) != CAM_W * CAM_H) return JNI_FALSE;

  const jsize kp_len = env->GetArrayLength(kpJ);
  const jsize desc_len = env->GetArrayLength(descJ);
  const int n = kp_len / 2;
  if (kp_len % 2 != 0 || desc_len % DESC_DIM != 0 || desc_len / DESC_DIM != n) return JNI_FALSE;

  try {
    std::lock_guard<std::mutex> flk(st->frame_mtx);
    if (st->shutting_down.load(std::memory_order_relaxed) || !st->est || !(ts > st->last_cam_ts)) {
      return JNI_FALSE;
    }

    double td_now = 0.0;
    {
      std::lock_guard<std::mutex> slk(st->state_mtx);
      td_now = st->current_td;
    }
    const double target_imu_ts = ts + td_now;

    // Wait briefly for 480 Hz IMU thread to bracket ts + td (releasing imu_mtx while waiting).
    bool covered = false;
    {
      std::unique_lock<std::mutex> ilk(st->imu_mtx);
      covered = st->imu_ready.wait_for(ilk, std::chrono::milliseconds(18), [&] {
        return st->shutting_down.load(std::memory_order_relaxed) || st->last_imu_ts >= target_imu_ts;
      });
      covered = !st->shutting_down.load(std::memory_order_relaxed) && st->last_imu_ts >= target_imu_ts;
    }
    if (!covered) {
      if (++st->skipped_imu_frames % 60 == 1) {
        __android_log_print(ANDROID_LOG_WARN, VTAG,
                            "Skipped SuperVINS frame: waiting for IMU to cover ts+td (total=%u, td=%.4fs)",
                            st->skipped_imu_frames, td_now);
      }
      return JNI_FALSE;
    }

    cv::Mat gray(CAM_H, CAM_W, CV_8UC1);
    std::vector<float> kp(kp_len), desc(desc_len);
    env->GetByteArrayRegion(grayJ, 0, CAM_W * CAM_H, reinterpret_cast<jbyte *>(gray.data));
    if (env->ExceptionCheck()) return JNI_FALSE;
    if (n > 0) {
      env->GetFloatArrayRegion(kpJ, 0, kp_len, kp.data());
      if (env->ExceptionCheck()) return JNI_FALSE;
      env->GetFloatArrayRegion(descJ, 0, desc_len, desc.data());
      if (env->ExceptionCheck()) return JNI_FALSE;
    }

    // Track SuperPoint features with LightGlue + 3D prediction gating + F-RANSAC + velocities.
    auto packet = st->frontend.trackFrame(ts, gray, kp.data(), desc.data(), n, st->est->latest_predicted_pts);
    if (packet.empty()) return JNI_FALSE;

    const double prev_before = st->est->prevTime;
    st->est->inputFeature(ts, packet);
    const bool processed = (st->est->prevTime != prev_before);
    if (!processed) {
      // Drop unprocessed frame from featureBuf so backlog never accumulates.
      std::lock_guard<std::mutex> blk(st->est->mBuf);
      while (!st->est->featureBuf.empty()) st->est->featureBuf.pop();
      return JNI_FALSE;
    }

    st->last_cam_ts = ts;
    st->processed_frames++;

    const bool is_init = (st->est->solver_flag == Estimator::NON_LINEAR);
    const int idx = std::max(0, std::min(WINDOW_SIZE, st->est->frame_count));
    {
      std::lock_guard<std::mutex> slk(st->state_mtx);
      st->initialized = is_init;
      st->init_ready_to_move = (!is_init && st->est->frame_count >= WINDOW_SIZE);
      st->pose_cam_ts = ts;
      st->current_td = st->est->td;
      st->R_WI = st->est->Rs[idx];
      st->p_IinW = st->est->Ps[idx];
      st->v_IinW = st->est->Vs[idx];
      st->bg = st->est->Bgs[idx];
      st->ba = st->est->Bas[idx];
      st->R_IC = st->est->ric[0];
      st->t_IC = st->est->tic[0];
    }

    if (st->processed_frames % 60 == 0) {
      __android_log_print(
          ANDROID_LOG_INFO, VTAG,
          "SuperVINS frame=%u init=%d fc=%d tracks=%d/%d laserF=%d td=%.4fs p=(%.3f,%.3f,%.3f) |v|=%.3f",
          st->processed_frames, is_init ? 1 : 0, st->est->frame_count,
          st->frontend.lastTrackedCount(), st->frontend.lastTotalCount(),
          st->est->last_laser_factors_,
          st->est->td, st->est->Ps[idx].x(), st->est->Ps[idx].y(), st->est->Ps[idx].z(),
          st->est->Vs[idx].norm());
    }
    return JNI_TRUE;
  } catch (const std::exception &e) {
    __android_log_print(ANDROID_LOG_ERROR, VTAG, "feedFrame exception: %s", e.what());
    return JNI_FALSE;
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioInitReadyToMove(JNIEnv *, jclass, jlong h) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (!st) return JNI_FALSE;
  std::lock_guard<std::mutex> slk(st->state_mtx);
  return st->init_ready_to_move ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioSetLaserBand(
    JNIEnv *, jclass, jlong, jdouble cx, jdouble cy, jdouble nx, jdouble ny, jdouble hw, jboolean on) {
  supervins::setLaserBand((float)cx, (float)cy, (float)nx, (float)ny, (float)hw, on ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioFeedLaserPoints(
    JNIEnv *env, jclass, jlong h, jdouble ts, jfloatArray xyzMmJ, jint count) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (!st || st->shutting_down.load(std::memory_order_relaxed) || !xyzMmJ || count <= 0 || !std::isfinite(ts)) return;
  const jsize len = env->GetArrayLength(xyzMmJ);
  const int n = std::min<int>(count, len / 3);
  if (n < 3) return;
  std::vector<float> buf((size_t)n * 3);
  env->GetFloatArrayRegion(xyzMmJ, 0, n * 3, buf.data());
  if (env->ExceptionCheck()) return;
  std::vector<Eigen::Vector3d> ray_depth;
  ray_depth.reserve((size_t)n);
  for (int i = 0; i < n; ++i) {
    const double x = buf[i * 3] * 1e-3;
    const double y = buf[i * 3 + 1] * 1e-3;
    const double z = buf[i * 3 + 2] * 1e-3;
    if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z) || z < 0.06 || z > 2.5) continue;
    ray_depth.emplace_back(x / z, y / z, z);
  }
  if (ray_depth.size() >= 3 && st->est) {
    st->est->inputLaserPoints(ts, ray_depth);
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioInitialized(JNIEnv *, jclass, jlong h) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (!st) return JNI_FALSE;
  std::lock_guard<std::mutex> slk(st->state_mtx);
  return st->initialized ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioGetState(
    JNIEnv *env, jclass, jlong h, jdoubleArray pJ, jdoubleArray vJ, jdoubleArray bgJ, jdoubleArray baJ, jdoubleArray quatJ) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (!st) return JNI_FALSE;
  std::lock_guard<std::mutex> slk(st->state_mtx);
  if (!st->initialized) return JNI_FALSE;
  if (!st->p_IinW.allFinite() || !st->v_IinW.allFinite() || !st->bg.allFinite() ||
      !st->ba.allFinite() || !st->R_WI.allFinite()) {
    return JNI_FALSE;
  }
  Eigen::Quaterniond q(st->R_WI);
  q.normalize();
  jdouble pd[3] = {st->p_IinW.x(), st->p_IinW.y(), st->p_IinW.z()};
  jdouble vd[3] = {st->v_IinW.x(), st->v_IinW.y(), st->v_IinW.z()};
  jdouble bgd[3] = {st->bg.x(), st->bg.y(), st->bg.z()};
  jdouble bad[3] = {st->ba.x(), st->ba.y(), st->ba.z()};
  jdouble qd[4] = {q.w(), q.x(), q.y(), q.z()};
  env->SetDoubleArrayRegion(pJ, 0, 3, pd);
  env->SetDoubleArrayRegion(vJ, 0, 3, vd);
  env->SetDoubleArrayRegion(bgJ, 0, 3, bgd);
  env->SetDoubleArrayRegion(baJ, 0, 3, bad);
  env->SetDoubleArrayRegion(quatJ, 0, 4, qd);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioGetPoseAtFrame(
    JNIEnv *env, jclass, jlong h, jdouble ts, jdoubleArray posJ, jdoubleArray quatJ) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (!st || !std::isfinite(ts)) return JNI_FALSE;

  bool is_init = false;
  double base_cam_ts = -1.0;
  double td_now = 0.0;
  Eigen::Matrix3d R_WI;
  Eigen::Vector3d p_IinW, v_IinW, bg, ba;
  Eigen::Matrix3d R_IC;
  Eigen::Vector3d t_IC;
  Eigen::Vector3d g_vec(0.0, 0.0, 9.81);
  {
    std::lock_guard<std::mutex> slk(st->state_mtx);
    is_init = st->initialized;
    base_cam_ts = st->pose_cam_ts;
    td_now = st->current_td;
    R_WI = st->R_WI;
    p_IinW = st->p_IinW;
    v_IinW = st->v_IinW;
    bg = st->bg;
    ba = st->ba;
    R_IC = st->R_IC;
    t_IC = st->t_IC;
    g_vec = Eigen::Vector3d(0.0, 0.0, st->cfg.g_norm);
  }
  if (!is_init || base_cam_ts <= 0.0 || ts < base_cam_ts || (ts - base_cam_ts) > 0.25) {
    return JNI_FALSE;
  }
  if (!p_IinW.allFinite() || !R_WI.allFinite() || !v_IinW.allFinite()) return JNI_FALSE;

  // On intermediate camera frames between ~12 Hz visual solves, propagate the latest
  // optimized state forward to exact camera timestamp ts + td using buffered 480 Hz IMU.
  if (ts > base_cam_ts + 1e-6) {
    const double t_start = base_cam_ts + td_now;
    const double t_end = ts + td_now;
    std::vector<ImuSample> seg;
    {
      std::lock_guard<std::mutex> ilk(st->imu_mtx);
      if (st->last_imu_ts < t_end) return JNI_FALSE;
      for (const auto &s : st->recent_imu) {
        if (s.ts > t_start && s.ts <= t_end) seg.push_back(s);
        else if (s.ts > t_end) {
          seg.push_back(s);
          break;
        }
      }
    }
    if (!seg.empty()) {
      double prev_t = t_start;
      Eigen::Vector3d prev_acc = seg.front().acc;
      Eigen::Vector3d prev_gyr = seg.front().gyr;
      Eigen::Quaterniond q(R_WI);
      for (size_t i = 0; i < seg.size(); ++i) {
        const double cur_t = std::min(seg[i].ts, t_end);
        const double dt = cur_t - prev_t;
        if (dt > 0.0 && dt < 0.05) {
          const Eigen::Vector3d un_acc_0 = q * (prev_acc - ba) - g_vec;
          const Eigen::Vector3d un_gyr = 0.5 * (prev_gyr + seg[i].gyr) - bg;
          q = q * Utility::deltaQ(un_gyr * dt);
          q.normalize();
          const Eigen::Vector3d un_acc_1 = q * (seg[i].acc - ba) - g_vec;
          const Eigen::Vector3d un_acc = 0.5 * (un_acc_0 + un_acc_1);
          p_IinW += dt * v_IinW + 0.5 * dt * dt * un_acc;
          v_IinW += dt * un_acc;
        }
        prev_t = cur_t;
        prev_acc = seg[i].acc;
        prev_gyr = seg[i].gyr;
        if (cur_t >= t_end) break;
      }
      R_WI = q.toRotationMatrix();
    }
  }

  Eigen::Vector3d p_cam;
  Eigen::Vector4d q_cam;
  supervins::superVinsBodyToEasyScanCamera(R_WI, p_IinW, R_IC, t_IC, p_cam, q_cam);
  if (!p_cam.allFinite() || !q_cam.allFinite()) {
    __android_log_print(ANDROID_LOG_WARN, VTAG, "SuperVINS pose non-finite, suppressing");
    return JNI_FALSE;
  }
  jdouble pos[3] = {p_cam.x(), p_cam.y(), p_cam.z()};
  jdouble quat[4] = {q_cam(0), q_cam(1), q_cam(2), q_cam(3)};
  env->SetDoubleArrayRegion(posJ, 0, 3, pos);
  env->SetDoubleArrayRegion(quatJ, 0, 4, quat);
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioSetCaps(JNIEnv *, jclass, jint maxTracks, jfloat minDist) {
  supervins::setTrackCaps((int)maxTracks, (float)minDist);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioSetReacq(JNIEnv *, jclass, jint enabled) {
  supervins::setReacqEnabled((int)enabled);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioLightGlueInit(JNIEnv *env, jclass, jstring libDirJ, jstring modelPathJ) {
  const char *libDir = env->GetStringUTFChars(libDirJ, nullptr);
  const char *modelPath = env->GetStringUTFChars(modelPathJ, nullptr);
  int rc = sp_lightglue_init(libDir, modelPath);
  env->ReleaseStringUTFChars(libDirJ, libDir);
  env->ReleaseStringUTFChars(modelPathJ, modelPath);
  return rc;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioLightGlueReady(JNIEnv *, jclass) {
  return sp_lightglue_ready() != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioSetLightGlue(JNIEnv *, jclass, jint enabled) {
  supervins::setLightGlueEnabled((int)enabled);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioLightGlueMatch(
    JNIEnv *env, jclass, jfloatArray kp0J, jfloatArray d0J, jfloatArray kp1J, jfloatArray d1J) {
  if (!kp0J || !d0J || !kp1J || !d1J) return nullptr;
  int n0 = env->GetArrayLength(kp0J) / 2;
  int n1 = env->GetArrayLength(kp1J) / 2;
  if (n0 <= 0 || n1 <= 0) return nullptr;
  if (env->GetArrayLength(d0J) < n0 * DESC_DIM || env->GetArrayLength(d1J) < n1 * DESC_DIM) return nullptr;
  std::vector<float> kp0((size_t)n0 * 2), kp1((size_t)n1 * 2);
  std::vector<float> d0((size_t)n0 * DESC_DIM), d1((size_t)n1 * DESC_DIM);
  env->GetFloatArrayRegion(kp0J, 0, (jsize)kp0.size(), kp0.data());
  env->GetFloatArrayRegion(kp1J, 0, (jsize)kp1.size(), kp1.data());
  env->GetFloatArrayRegion(d0J, 0, (jsize)d0.size(), d0.data());
  env->GetFloatArrayRegion(d1J, 0, (jsize)d1.size(), d1.data());
  std::vector<int> pairs((size_t)n0 * 2, -1);
  int np = sp_lightglue_match(kp0.data(), d0.data(), n0, kp1.data(), d1.data(), n1, pairs.data(), n0);
  jintArray out = env->NewIntArray(1 + np * 2);
  if (!out) return nullptr;
  std::vector<jint> vals(1 + np * 2);
  vals[0] = np;
  for (int i = 0; i < np; i++) {
    vals[1 + i * 2] = pairs[i * 2];
    vals[2 + i * 2] = pairs[i * 2 + 1];
  }
  env->SetIntArrayRegion(out, 0, (jsize)vals.size(), vals.data());
  return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioSetKltFallback(JNIEnv *, jclass, jint enabled) {
  supervins::setKltFallbackEnabled((int)enabled);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeLoopLightGlueInit(
    JNIEnv *env, jclass, jstring libDirJ, jstring modelPathJ) {
  if (!libDirJ || !modelPathJ) return -1;
  const char *d = env->GetStringUTFChars(libDirJ, nullptr);
  const char *p = env->GetStringUTFChars(modelPathJ, nullptr);
  int rc = sp_loop_lightglue_init(d, p);
  env->ReleaseStringUTFChars(modelPathJ, p);
  env->ReleaseStringUTFChars(libDirJ, d);
  return rc;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeLoopLightGlueReady(JNIEnv *, jclass) {
  return sp_loop_lightglue_ready() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeLoopLightGlueMatch(
    JNIEnv *env, jclass, jfloatArray kp0J, jfloatArray d0J, jfloatArray kp1J, jfloatArray d1J) {
  if (!kp0J || !d0J || !kp1J || !d1J) return nullptr;
  int n0 = env->GetArrayLength(kp0J) / 2;
  int n1 = env->GetArrayLength(kp1J) / 2;
  if (n0 <= 0 || n1 <= 0) return nullptr;
  if (env->GetArrayLength(d0J) < n0 * DESC_DIM || env->GetArrayLength(d1J) < n1 * DESC_DIM) return nullptr;
  std::vector<float> kp0((size_t)n0 * 2), kp1((size_t)n1 * 2);
  std::vector<float> d0((size_t)n0 * DESC_DIM), d1((size_t)n1 * DESC_DIM);
  env->GetFloatArrayRegion(kp0J, 0, (jsize)kp0.size(), kp0.data());
  env->GetFloatArrayRegion(kp1J, 0, (jsize)kp1.size(), kp1.data());
  env->GetFloatArrayRegion(d0J, 0, (jsize)d0.size(), d0.data());
  env->GetFloatArrayRegion(d1J, 0, (jsize)d1.size(), d1.data());
  std::vector<int> pairs((size_t)n0 * 2, -1);
  int np = sp_loop_lightglue_match(kp0.data(), d0.data(), n0, kp1.data(), d1.data(), n1, pairs.data(), n0);
  jintArray out = env->NewIntArray(1 + np * 2);
  if (!out) return nullptr;
  std::vector<jint> vals(1 + np * 2);
  vals[0] = np;
  for (int i = 0; i < np; i++) {
    vals[1 + i * 2] = pairs[i * 2];
    vals[2 + i * 2] = pairs[i * 2 + 1];
  }
  env->SetIntArrayRegion(out, 0, (jsize)vals.size(), vals.data());
  return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioSetLightGlueThreshold(JNIEnv *, jclass, jfloat vio, jfloat loop) {
  sp_lightglue_set_vio_threshold((float)vio);
  sp_lightglue_set_loop_threshold((float)loop);
}
