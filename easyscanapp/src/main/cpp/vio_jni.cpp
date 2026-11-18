#include <jni.h>
#include <atomic>

#include <android/log.h>
#include <memory>
#include <mutex>
#include <string>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#include "core/VioManager.h"
#include "core/VioManagerOptions.h"
#include "utils/print.h"
#include "utils/quat_ops.h"
#include "utils/sensor_data.h"
#include "state/State.h"
#include "types/IMU.h"
#include "superpoint_track.h"

#define VTAG "VioJni"

namespace {

struct VioState {
  std::shared_ptr<ov_msckf::VioManager> sys;
  std::mutex mtx;
  bool ready = false;
  double last_imu_ts = -1.0;
  double last_cam_ts = -1.0;
};

static const int CAM_W = 640;
static const int CAM_H = 480;

static bool build_system(VioState *st, const std::string &config_dir) {
  std::string config_path = config_dir + "/estimator_config.yaml";
  auto parser = std::make_shared<ov_core::YamlParser>(config_path, true);
  ov_msckf::VioManagerOptions params;
  params.print_and_load(parser);
  ov_core::Printer::setPrintLevel(ov_core::Printer::PrintLevel::ALL);
  auto tracker = std::make_shared<ov_core::TrackSuperPoint>(
      params.camera_intrinsics, params.num_pts, params.state_options.max_aruco_features,
      params.use_stereo, params.histogram_method);
  st->sys = std::make_shared<ov_msckf::VioManager>(params, tracker);
  st->ready = true;
  __android_log_print(ANDROID_LOG_INFO, VTAG, "VIO created from %s", config_path.c_str());
  return true;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioCreate(JNIEnv *env, jclass, jstring configDirJ) {
  const char *dir = env->GetStringUTFChars(configDirJ, nullptr);
  auto *st = new VioState();
  superpoint_reset();
  try {
    build_system(st, std::string(dir));
  } catch (const std::exception &e) {
    __android_log_print(ANDROID_LOG_ERROR, VTAG, "create failed: %s", e.what());
    delete st;
    env->ReleaseStringUTFChars(configDirJ, dir);
    return 0;
  }
  env->ReleaseStringUTFChars(configDirJ, dir);
  return reinterpret_cast<jlong>(st);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioDestroy(JNIEnv *, jclass, jlong h) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (st) {
    {
      std::lock_guard<std::mutex> lck(st->mtx);
      st->sys.reset();
    }
    delete st;
  }
  superpoint_reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioSetFeatures(
    JNIEnv *env, jclass, jlong h, jint camId, jdouble ts, jfloatArray kpJ, jfloatArray descJ, jint n) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (!st || !st->sys || n <= 0) return;
  jfloat *kp = env->GetFloatArrayElements(kpJ, nullptr);
  jfloat *desc = env->GetFloatArrayElements(descJ, nullptr);
  if (kp && desc) ov_core::superpoint_submit((int)camId, (double)ts, kp, desc, (int)n);
  if (kp) env->ReleaseFloatArrayElements(kpJ, kp, JNI_ABORT);
  if (desc) env->ReleaseFloatArrayElements(descJ, desc, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioFeedImu(
    JNIEnv *, jclass, jlong h, jfloat ax, jfloat ay, jfloat az,
    jfloat gx, jfloat gy, jfloat gz, jdouble ts) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (!st || !st->sys) return;
  if (!(ts > st->last_imu_ts)) return;  // reject duplicate/reversed samples (negative dt poisons the filter)
  st->last_imu_ts = ts;
  ov_core::ImuData msg;
  msg.timestamp = (double)ts;
  msg.am = Eigen::Vector3d(ax, ay, az);
  msg.wm = Eigen::Vector3d(gx, gy, gz);
  st->sys->feed_measurement_imu(msg);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioFeedFrame(
    JNIEnv *env, jclass, jlong h, jbyteArray grayJ, jint w, jint hgt, jdouble ts) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (!st || !st->sys) return JNI_FALSE;
  if (!(ts > st->last_cam_ts)) return JNI_FALSE;  // drop duplicate/reversed frames
  st->last_cam_ts = ts;
  jsize len = env->GetArrayLength(grayJ);
  if (len != (jsize)(w * hgt)) return JNI_FALSE;
  jbyte *gray = env->GetByteArrayElements(grayJ, nullptr);
  if (!gray) return JNI_FALSE;
  try {
    cv::Mat img((int)hgt, (int)w, CV_8UC1, gray);
    cv::Mat img640;
    if (w != CAM_W || hgt != CAM_H) cv::resize(img, img640, cv::Size(CAM_W, CAM_H), 0, 0, cv::INTER_AREA);
    else img640 = img.clone();
    ov_core::CameraData message;
    message.timestamp = (double)ts;
    message.sensor_ids.push_back(0);
    message.images.push_back(img640);
    message.masks.push_back(cv::Mat::zeros(CAM_H, CAM_W, CV_8UC1));
    st->sys->feed_measurement_camera(message);
  } catch (const std::exception &e) {
    __android_log_print(ANDROID_LOG_ERROR, VTAG, "feedFrame failed: %s", e.what());
    env->ReleaseByteArrayElements(grayJ, gray, JNI_ABORT);
    return JNI_FALSE;
  }
  env->ReleaseByteArrayElements(grayJ, gray, JNI_ABORT);
  return JNI_TRUE;
}

extern std::atomic<bool> g_init_ready_to_move;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioInitReadyToMove(JNIEnv *, jclass, jlong h) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (!st || !st->sys) return JNI_FALSE;
  return g_init_ready_to_move.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioInitialized(JNIEnv *, jclass, jlong h) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (!st || !st->sys) return JNI_FALSE;
  return st->sys->initialized() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioGetPose(
    JNIEnv *env, jclass, jlong h, jdoubleArray posJ, jdoubleArray quatJ) {
  auto *st = reinterpret_cast<VioState *>(h);
  if (!st || !st->sys || !st->sys->initialized()) return JNI_FALSE;
  try {
    auto state = st->sys->get_state();
    auto q_GtoI = state->_imu->quat();
    auto p_IinG = state->_imu->pos();
    auto calib = state->_calib_IMUtoCAM.at(0);
    Eigen::Vector4d q_ItoC = calib->quat();
    Eigen::Vector3d p_IinC = calib->pos();
    Eigen::Vector4d q_cam = ov_core::quat_multiply(q_ItoC, q_GtoI);
    Eigen::Vector3d p_cam = p_IinG - ov_core::quat_2_Rot(q_cam).transpose() * p_IinC;
    if (!p_cam.allFinite() || !q_cam.allFinite()) {
      __android_log_print(ANDROID_LOG_WARN, VTAG, "VIO pose non-finite, suppressing");
      return JNI_FALSE;
    }
    jdouble pos[3] = {p_cam(0), p_cam(1), p_cam(2)};
    // JPL [x,y,z,w] -> Hamilton [w,x,y,z]
    jdouble quat[4] = {q_cam(3), q_cam(0), q_cam(1), q_cam(2)};
    env->SetDoubleArrayRegion(posJ, 0, 3, pos);
    env->SetDoubleArrayRegion(quatJ, 0, 4, quat);
    return JNI_TRUE;
  } catch (const std::exception &e) {
    __android_log_print(ANDROID_LOG_ERROR, VTAG, "getPose failed: %s", e.what());
    return JNI_FALSE;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioSetCaps(JNIEnv*, jclass, jint maxTracks, jfloat minDist) {
  setTrackCaps((int)maxTracks, (float)minDist);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLib_nativeVioSetReacq(JNIEnv*, jclass, jint enabled) {
  setReacqEnabled((int)enabled);
}
