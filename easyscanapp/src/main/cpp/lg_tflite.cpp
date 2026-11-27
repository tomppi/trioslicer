#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <sys/stat.h>

#include "tensorflow/lite/core/c/c_api.h"
#include "QnnTFLiteDelegate.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "LGTFLITE", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "LGTFLITE", __VA_ARGS__)

namespace {
constexpr int MAX_KP = 400;
constexpr int DESC_DIM = 64;
std::atomic<float> g_vio_score_thresh{0.10f};
std::atomic<float> g_loop_score_thresh{0.20f};

struct LGEngine {
  TfLiteModel* model = nullptr;
  TfLiteInterpreter* interp = nullptr;
  TfLiteDelegate* qnn = nullptr;
  TfLiteTensor* kp0 = nullptr; TfLiteTensor* d0 = nullptr; TfLiteTensor* n0 = nullptr;
  TfLiteTensor* kp1 = nullptr; TfLiteTensor* d1 = nullptr; TfLiteTensor* n1 = nullptr;
  TfLiteTensor* matches0 = nullptr;
  TfLiteTensor* mscores0 = nullptr;
  // Pre-allocated heap staging buffers (1+ MB total — MUST NOT live on Android's 1 MB HandlerThread stack).
  std::vector<float> kp0buf = std::vector<float>(MAX_KP * 2, 0.f);
  std::vector<float> kp1buf = std::vector<float>(MAX_KP * 2, 0.f);
  std::vector<float> d0buf = std::vector<float>(MAX_KP * DESC_DIM, 0.f);
  std::vector<float> d1buf = std::vector<float>(MAX_KP * DESC_DIM, 0.f);
  std::vector<int32_t> m0 = std::vector<int32_t>(MAX_KP, -1);
  std::vector<float> ms0 = std::vector<float>(MAX_KP, 0.f);
  bool ready = false;
  bool usingQnn = false;
  std::string backendName = "CPU";
};

std::mutex g_lg_mtx;
LGEngine* g_lg = nullptr;

std::string nameOf(const TfLiteTensor* t) {
  const char* n = TfLiteTensorName(t);
  return n ? std::string(n) : std::string();
}

void mapIO(LGEngine* e) {
  int ni = TfLiteInterpreterGetInputTensorCount(e->interp);
  for (int i = 0; i < ni; i++) {
    auto* t = const_cast<TfLiteTensor*>(TfLiteInterpreterGetInputTensor(e->interp, i));
    std::string n = nameOf(t);
    if (n.find("num_keypoints0") != std::string::npos) e->n0 = t;
    else if (n.find("num_keypoints1") != std::string::npos) e->n1 = t;
    else if (n.find("keypoints0") != std::string::npos) e->kp0 = t;
    else if (n.find("keypoints1") != std::string::npos) e->kp1 = t;
    else if (n.find("descriptors0") != std::string::npos) e->d0 = t;
    else if (n.find("descriptors1") != std::string::npos) e->d1 = t;
  }
  int no = TfLiteInterpreterGetOutputTensorCount(e->interp);
  for (int i = 0; i < no; i++) {
    auto* t = const_cast<TfLiteTensor*>(TfLiteInterpreterGetOutputTensor(e->interp, i));
    std::string n = nameOf(t);
    if (n.find("matches0") != std::string::npos) e->matches0 = t;
    else if (n.find("mscores0") != std::string::npos) e->mscores0 = t;
  }
  // TFLite SavedModel/concrete-function exports name outputs PartitionedCall:0/1;
  // our LighterGlue graph exposes unique output types: int32 matches0 and float32 mscores0.
  for (int i = 0; i < no; i++) {
    auto* t = const_cast<TfLiteTensor*>(TfLiteInterpreterGetOutputTensor(e->interp, i));
    if (!t) continue;
    TfLiteType ty = TfLiteTensorType(t);
    if (!e->matches0 && ty == kTfLiteInt32 && TfLiteTensorByteSize(t) == MAX_KP * sizeof(int32_t)) {
      e->matches0 = t;
    } else if (!e->mscores0 && ty == kTfLiteFloat32 && TfLiteTensorByteSize(t) == MAX_KP * sizeof(float)) {
      e->mscores0 = t;
    }
  }
}

bool verifySizes(const LGEngine* e) {
  return TfLiteTensorByteSize(e->kp0) == MAX_KP * 2 * sizeof(float) &&
         TfLiteTensorByteSize(e->kp1) == MAX_KP * 2 * sizeof(float) &&
         TfLiteTensorByteSize(e->d0) == MAX_KP * DESC_DIM * sizeof(float) &&
         TfLiteTensorByteSize(e->d1) == MAX_KP * DESC_DIM * sizeof(float) &&
         TfLiteTensorByteSize(e->n0) == sizeof(int32_t) &&
         TfLiteTensorByteSize(e->n1) == sizeof(int32_t) &&
         TfLiteTensorByteSize(e->matches0) == MAX_KP * sizeof(int32_t) &&
         TfLiteTensorByteSize(e->mscores0) == MAX_KP * sizeof(float);
}
bool initQnnCascade(LGEngine* e, const char* libDir, const char* modelPath,
                    const std::string& tokenPrefix, std::chrono::steady_clock::time_point t0) {
  std::string skelDir(libDir ? libDir : "");
  std::string modelStr(modelPath ? modelPath : "");
  auto slash = modelStr.find_last_of('/');
  std::string cacheDir = (slash != std::string::npos ? modelStr.substr(0, slash) : ".") + "/qnn-cache";
  mkdir(cacheDir.c_str(), 0755);
  std::string baseName = (slash != std::string::npos ? modelStr.substr(slash + 1) : modelStr);

  auto tryDelegate = [&](const char* label, TfLiteQnnDelegateOptions& qopt) -> bool {
    TfLiteDelegate* del = TfLiteQnnDelegateCreate(&qopt);
    if (!del) return false;
    auto* opts = TfLiteInterpreterOptionsCreate();
    TfLiteInterpreterOptionsSetNumThreads(opts, 4);
    TfLiteInterpreterOptionsAddDelegate(opts, del);
    TfLiteInterpreter* interp = TfLiteInterpreterCreate(e->model, opts);
    TfLiteInterpreterOptionsDelete(opts);
    if (interp && TfLiteInterpreterAllocateTensors(interp) == kTfLiteOk) {
      e->qnn = del;
      e->interp = interp;
      e->usingQnn = true;
      e->backendName = label;
      double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
      LOG("LighterGlue %s delegate ready in %.0f ms", label, ms);
      return true;
    }
    if (interp) TfLiteInterpreterDelete(interp);
    TfLiteQnnDelegateDelete(del);
    return false;
  };

  // Tier 1: QNN Hexagon HTP FP16 (Unsigned PD — V69/V73/V75/V79/V81).
  {
    auto qopt = TfLiteQnnDelegateOptionsDefault();
    qopt.backend_type = kHtpBackend;
    std::string backend = skelDir + "/libQnnHtp.so";
    std::string token = tokenPrefix + baseName + "_400_v1";
    qopt.library_path = backend.c_str();
    qopt.skel_library_dir = skelDir.c_str();
    qopt.cache_dir = cacheDir.c_str();
    qopt.model_token = token.c_str();
    qopt.htp_options.performance_mode = kHtpBurst;
    qopt.htp_options.precision = kHtpFp16;
    qopt.htp_options.pd_session = kHtpUnsignedPd;
    qopt.htp_options.optimization_strategy = kHtpOptimizeForInference;
    qopt.log_level = kLogLevelWarn;
    if (tryDelegate("QNN-HTP", qopt)) return true;
  }
  // Tier 2: QNN Hexagon HTP FP16 (Signed PD fallback).
  {
    auto qopt = TfLiteQnnDelegateOptionsDefault();
    qopt.backend_type = kHtpBackend;
    std::string backend = skelDir + "/libQnnHtp.so";
    std::string token = tokenPrefix + baseName + "_400_v1_signed";
    qopt.library_path = backend.c_str();
    qopt.skel_library_dir = skelDir.c_str();
    qopt.cache_dir = cacheDir.c_str();
    qopt.model_token = token.c_str();
    qopt.htp_options.performance_mode = kHtpBurst;
    qopt.htp_options.precision = kHtpFp16;
    qopt.htp_options.pd_session = kHtpSignedPd;
    qopt.htp_options.optimization_strategy = kHtpOptimizeForInference;
    qopt.log_level = kLogLevelWarn;
    if (tryDelegate("QNN-HTP-Signed", qopt)) return true;
  }
  // Tier 3: QNN Adreno GPU FP16 (SD 888 / 865 / 870 / 7xx / 6xx).
  {
    auto qopt = TfLiteQnnDelegateOptionsDefault();
    qopt.backend_type = kGpuBackend;
    std::string backend = skelDir + "/libQnnGpu.so";
    std::string gpuCache = cacheDir + "/gpu";
    mkdir(gpuCache.c_str(), 0755);
    qopt.library_path = backend.c_str();
    qopt.gpu_options.precision = kGpuFp16;
    qopt.gpu_options.performance_mode = kGpuHigh;
    qopt.gpu_options.kernel_repo_dir = gpuCache.c_str();
    qopt.log_level = kLogLevelWarn;
    if (tryDelegate("QNN-GPU", qopt)) return true;
  }
  return false;
}
}  // namespace

extern "C" int sp_lightglue_init(const char* libDir, const char* modelPath) {
  std::lock_guard<std::mutex> lck(g_lg_mtx);
  if (g_lg && g_lg->ready) return 0;
  auto t0 = std::chrono::steady_clock::now();
  auto* e = new LGEngine();
  e->model = TfLiteModelCreateFromFile(modelPath);
  if (!e->model) { LOGW("model create failed: %s", modelPath ? modelPath : "null"); delete e; return -1; }

  if (!initQnnCascade(e, libDir, modelPath, "xfeat_lg_", t0)) {
    LOGW("QNN HTP/GPU unavailable, retrying LighterGlue on CPU");
    auto* cpuOpts = TfLiteInterpreterOptionsCreate();
    TfLiteInterpreterOptionsSetNumThreads(cpuOpts, 4);
    e->interp = TfLiteInterpreterCreate(e->model, cpuOpts);
    TfLiteInterpreterOptionsDelete(cpuOpts);
    if (!e->interp) { LOGW("CPU interpreter create failed"); TfLiteModelDelete(e->model); delete e; return -2; }
    if (TfLiteInterpreterAllocateTensors(e->interp) != kTfLiteOk) {
      LOGW("CPU allocate tensors failed");
      TfLiteInterpreterDelete(e->interp); TfLiteModelDelete(e->model); delete e; return -3;
    }
    e->backendName = "CPU";
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    LOG("LighterGlue CPU fallback ready in %.0f ms", ms);
  }

  mapIO(e);
  if (!e->kp0 || !e->d0 || !e->n0 || !e->kp1 || !e->d1 || !e->n1 || !e->matches0 || !e->mscores0 || !verifySizes(e)) {
    LOGW("tensor contract mismatch: kp0=%p d0=%p n0=%p kp1=%p d1=%p n1=%p m0=%p ms0=%p",
         e->kp0, e->d0, e->n0, e->kp1, e->d1, e->n1, e->matches0, e->mscores0);
    if (e->kp0 && e->d0 && e->n0 && e->kp1 && e->d1 && e->n1 && e->matches0 && e->mscores0) {
      LOGW("sizes kp0=%zu d0=%zu n0=%zu kp1=%zu d1=%zu n1=%zu m0=%zu ms0=%zu expect kp=%zu d=%zu n=%zu m=%zu ms=%zu",
           TfLiteTensorByteSize(e->kp0), TfLiteTensorByteSize(e->d0), TfLiteTensorByteSize(e->n0),
           TfLiteTensorByteSize(e->kp1), TfLiteTensorByteSize(e->d1), TfLiteTensorByteSize(e->n1),
           TfLiteTensorByteSize(e->matches0), TfLiteTensorByteSize(e->mscores0),
           (size_t)MAX_KP * 2 * sizeof(float), (size_t)MAX_KP * DESC_DIM * sizeof(float),
           sizeof(int32_t), (size_t)MAX_KP * sizeof(int32_t), (size_t)MAX_KP * sizeof(float));
    }
    if (e->interp) TfLiteInterpreterDelete(e->interp);
    if (e->qnn) TfLiteQnnDelegateDelete(e->qnn);
    TfLiteModelDelete(e->model); delete e; return -4;
  }
  e->ready = true;
  g_lg = e;
  LOG("LighterGlue ready (backend=%s)", e->backendName.c_str());
  return 0;
}

extern "C" int sp_lightglue_ready() {
  std::lock_guard<std::mutex> lck(g_lg_mtx);
  return (g_lg && g_lg->ready && g_lg->usingQnn) ? 1 : 0;
}

extern "C" int sp_lightglue_match(const float* kp0, const float* desc0, int n0,
                                  const float* kp1, const float* desc1, int n1,
                                  int* pairs, int maxPairs) {
  std::lock_guard<std::mutex> lck(g_lg_mtx);
  if (!g_lg || !g_lg->ready || !kp0 || !desc0 || !kp1 || !desc1 || !pairs || maxPairs <= 0) return 0;
  if (n0 <= 0 || n1 <= 0) return 0;
  auto* e = g_lg;
  if (n0 > MAX_KP) n0 = MAX_KP;
  if (n1 > MAX_KP) n1 = MAX_KP;
  std::fill(e->kp0buf.begin(), e->kp0buf.end(), 0.f);
  std::fill(e->kp1buf.begin(), e->kp1buf.end(), 0.f);
  std::fill(e->d0buf.begin(), e->d0buf.end(), 0.f);
  std::fill(e->d1buf.begin(), e->d1buf.end(), 0.f);
  std::memcpy(e->kp0buf.data(), kp0, (size_t)n0 * 2 * sizeof(float));
  std::memcpy(e->kp1buf.data(), kp1, (size_t)n1 * 2 * sizeof(float));
  std::memcpy(e->d0buf.data(), desc0, (size_t)n0 * DESC_DIM * sizeof(float));
  std::memcpy(e->d1buf.data(), desc1, (size_t)n1 * DESC_DIM * sizeof(float));
  int32_t c0 = n0;
  int32_t c1 = n1;
  if (TfLiteTensorCopyFromBuffer(e->kp0, e->kp0buf.data(), e->kp0buf.size() * sizeof(float)) != kTfLiteOk ||
      TfLiteTensorCopyFromBuffer(e->d0, e->d0buf.data(), e->d0buf.size() * sizeof(float)) != kTfLiteOk ||
      TfLiteTensorCopyFromBuffer(e->n0, &c0, sizeof(c0)) != kTfLiteOk ||
      TfLiteTensorCopyFromBuffer(e->kp1, e->kp1buf.data(), e->kp1buf.size() * sizeof(float)) != kTfLiteOk ||
      TfLiteTensorCopyFromBuffer(e->d1, e->d1buf.data(), e->d1buf.size() * sizeof(float)) != kTfLiteOk ||
      TfLiteTensorCopyFromBuffer(e->n1, &c1, sizeof(c1)) != kTfLiteOk) {
    LOGW("TfLiteTensorCopyFromBuffer failed");
    return 0;
  }
  auto t0 = std::chrono::steady_clock::now();
  if (TfLiteInterpreterInvoke(e->interp) != kTfLiteOk) {
    LOGW("TfLiteInterpreterInvoke failed");
    return 0;
  }
  const double inferMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
  if (TfLiteTensorCopyToBuffer(e->matches0, e->m0.data(), e->m0.size() * sizeof(int32_t)) != kTfLiteOk ||
      TfLiteTensorCopyToBuffer(e->mscores0, e->ms0.data(), e->ms0.size() * sizeof(float)) != kTfLiteOk) {
    LOGW("TfLiteTensorCopyToBuffer failed");
    return 0;
  }
  const float thresh = g_vio_score_thresh.load(std::memory_order_relaxed);
  int count = 0;
  int raw_valid = 0;
  float max_s = 0.f;
  for (int i = 0; i < n0 && count < maxPairs; i++) {
    int32_t j = e->m0[i];
    if (j >= 0 && j < n1) {
      raw_valid++;
      if (e->ms0[i] > max_s) max_s = e->ms0[i];
      if (e->ms0[i] >= thresh) {
        pairs[count * 2] = i;
        pairs[count * 2 + 1] = (int)j;
        count++;
      }
    }
  }
  static int lg_stat_cnt = 0;
  if (++lg_stat_cnt % 30 == 1) {
    LOG("LighterGlue [%s] infer=%.2fms n0=%d n1=%d raw=%d kept=%d thresh=%.2f maxScore=%.2f",
        e->backendName.c_str(), inferMs, n0, n1, raw_valid, count, thresh, max_s);
  }
  return count;
}

extern "C" void sp_lightglue_set_vio_threshold(float v) {
  g_vio_score_thresh.store(std::max(0.01f, std::min(0.9f, v)));
}

extern "C" void sp_lightglue_set_loop_threshold(float v) {
  g_loop_score_thresh.store(std::max(0.01f, std::min(0.9f, v)));
}

extern "C" void sp_lightglue_close() {
  std::lock_guard<std::mutex> lck(g_lg_mtx);
  if (!g_lg) return;
  auto* e = g_lg; g_lg = nullptr;
  if (e->interp) TfLiteInterpreterDelete(e->interp);
  if (e->qnn) TfLiteQnnDelegateDelete(e->qnn);
  if (e->model) TfLiteModelDelete(e->model);
  delete e;
}

// ---------------------------------------------------------------------------
// Independent CPU LightGlue instance for live-mesh patch matching.
//
// VIO owns the NPU instance above. Live-mesh patch verification must never
// contend for that engine or block the camera path, so this is a separate
// interpreter with its own mutex. It runs at patch cadence, not every frame.
// ---------------------------------------------------------------------------
namespace {
std::mutex g_loop_mtx;
LGEngine* g_loop = nullptr;

int loopInit(const char* libDir, const char* modelPath) {
  std::lock_guard<std::mutex> lck(g_loop_mtx);
  if (g_loop && g_loop->ready) return 0;
  if (!libDir || !modelPath) return -1;
  auto t0 = std::chrono::steady_clock::now();
  auto* e = new LGEngine();
  e->model = TfLiteModelCreateFromFile(modelPath);
  if (!e->model) { LOGW("loop model create failed: %s", modelPath); delete e; return -1; }

  if (!initQnnCascade(e, libDir, modelPath, "xfeat_lg_loop_", t0)) {
    LOGW("loop QNN HTP/GPU unavailable, retrying LighterGlue on CPU");
    auto* opts = TfLiteInterpreterOptionsCreate();
    TfLiteInterpreterOptionsSetNumThreads(opts, 4);
    e->interp = TfLiteInterpreterCreate(e->model, opts);
    TfLiteInterpreterOptionsDelete(opts);
    if (!e->interp || TfLiteInterpreterAllocateTensors(e->interp) != kTfLiteOk) {
      LOGW("loop CPU interpreter create/allocate failed");
      if (e->interp) TfLiteInterpreterDelete(e->interp);
      TfLiteModelDelete(e->model); delete e;
      return -3;
    }
    e->backendName = "CPU";
  }
  mapIO(e);
  if (!e->kp0 || !e->d0 || !e->n0 || !e->kp1 || !e->d1 || !e->n1 || !e->matches0 || !e->mscores0 || !verifySizes(e)) {
    LOGW("loop tensor contract mismatch");
    if (e->interp) TfLiteInterpreterDelete(e->interp);
    if (e->qnn) TfLiteQnnDelegateDelete(e->qnn);
    TfLiteModelDelete(e->model); delete e; return -4;
  }
  e->ready = true;
  g_loop = e;
  LOG("LoopMatcher ready (backend=%s)", e->backendName.c_str());
  return 0;
}
} // namespace

extern "C" int sp_loop_lightglue_init(const char* libDir, const char* modelPath) { return loopInit(libDir, modelPath); }
extern "C" int sp_loop_lightglue_ready() {
  std::lock_guard<std::mutex> lck(g_loop_mtx);
  return (g_loop && g_loop->ready) ? 1 : 0;
}
extern "C" int sp_loop_lightglue_match(const float* kp0, const float* desc0, int n0,
                                       const float* kp1, const float* desc1, int n1,
                                       int* pairs, int maxPairs) {
  std::lock_guard<std::mutex> lck(g_loop_mtx);
  if (!g_loop || !g_loop->ready || !kp0 || !desc0 || !kp1 || !desc1 || !pairs || maxPairs <= 0) return 0;
  if (n0 <= 0 || n1 <= 0) return 0;
  auto* e = g_loop;
  if (n0 > MAX_KP) n0 = MAX_KP;
  if (n1 > MAX_KP) n1 = MAX_KP;
  std::fill(e->kp0buf.begin(), e->kp0buf.end(), 0.f);
  std::fill(e->kp1buf.begin(), e->kp1buf.end(), 0.f);
  std::fill(e->d0buf.begin(), e->d0buf.end(), 0.f);
  std::fill(e->d1buf.begin(), e->d1buf.end(), 0.f);
  std::memcpy(e->kp0buf.data(), kp0, (size_t)n0 * 2 * sizeof(float));
  std::memcpy(e->kp1buf.data(), kp1, (size_t)n1 * 2 * sizeof(float));
  std::memcpy(e->d0buf.data(), desc0, (size_t)n0 * DESC_DIM * sizeof(float));
  std::memcpy(e->d1buf.data(), desc1, (size_t)n1 * DESC_DIM * sizeof(float));
  int32_t c0 = n0, c1 = n1;
  if (TfLiteTensorCopyFromBuffer(e->kp0, e->kp0buf.data(), e->kp0buf.size() * sizeof(float)) != kTfLiteOk ||
      TfLiteTensorCopyFromBuffer(e->d0, e->d0buf.data(), e->d0buf.size() * sizeof(float)) != kTfLiteOk ||
      TfLiteTensorCopyFromBuffer(e->n0, &c0, sizeof(c0)) != kTfLiteOk ||
      TfLiteTensorCopyFromBuffer(e->kp1, e->kp1buf.data(), e->kp1buf.size() * sizeof(float)) != kTfLiteOk ||
      TfLiteTensorCopyFromBuffer(e->d1, e->d1buf.data(), e->d1buf.size() * sizeof(float)) != kTfLiteOk ||
      TfLiteTensorCopyFromBuffer(e->n1, &c1, sizeof(c1)) != kTfLiteOk) return 0;
  if (TfLiteInterpreterInvoke(e->interp) != kTfLiteOk) return 0;
  if (TfLiteTensorCopyToBuffer(e->matches0, e->m0.data(), e->m0.size() * sizeof(int32_t)) != kTfLiteOk ||
      TfLiteTensorCopyToBuffer(e->mscores0, e->ms0.data(), e->ms0.size() * sizeof(float)) != kTfLiteOk) return 0;
  int count = 0;
  for (int i = 0; i < n0 && count < maxPairs; i++) {
    int32_t j = e->m0[i];
    if (j >= 0 && j < n1 && e->ms0[i] >= g_loop_score_thresh.load(std::memory_order_relaxed)) {
      pairs[count * 2] = i;
      pairs[count * 2 + 1] = (int)j;
      count++;
    }
  }
  return count;
}
extern "C" void sp_loop_lightglue_close() {
  std::lock_guard<std::mutex> lck(g_loop_mtx);
  if (!g_loop) return;
  auto* e = g_loop; g_loop = nullptr;
  if (e->interp) TfLiteInterpreterDelete(e->interp);
  if (e->qnn) TfLiteQnnDelegateDelete(e->qnn);
  if (e->model) TfLiteModelDelete(e->model);
  delete e;
}

