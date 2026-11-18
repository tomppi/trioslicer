#include <jni.h>

#include <android/log.h>
#include <chrono>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include "tensorflow/lite/core/c/c_api.h"
#include "QnnTFLiteDelegate.h"

#define NPLOG(...) __android_log_print(ANDROID_LOG_INFO, "OVMM", __VA_ARGS__)

namespace {

constexpr int NP = 1024;
constexpr int KD = 256;

struct MmState {
  bool tried = false;
  bool ok = false;
  std::string err;
  TfLiteModel* model = nullptr;
  TfLiteDelegate* qnn = nullptr;
  TfLiteInterpreter* interp = nullptr;
  TfLiteTensor* inA = nullptr;
  TfLiteTensor* inB = nullptr;
  TfLiteTensor* outC = nullptr;
  std::vector<float> aPad, bPad, cOut;
};

MmState g;

bool implInit(const char* libDir, const char* modelPath) {
  if (g.tried) return g.ok;
  g.tried = true;
  g.model = TfLiteModelCreateFromFile(modelPath);
  if (!g.model) { g.err = "matmul model load failed"; NPLOG("init failed: %s", g.err.c_str()); return false; }
  auto* opts = TfLiteInterpreterOptionsCreate();
  TfLiteInterpreterOptionsSetNumThreads(opts, 4);
  auto qopt = TfLiteQnnDelegateOptionsDefault();
  qopt.backend_type = kHtpBackend;
  std::string backendLib = std::string(libDir) + "/libQnnHtp.so";
  qopt.library_path = backendLib.c_str();
  qopt.skel_library_dir = libDir;
  qopt.htp_options.performance_mode = kHtpBurst;
  qopt.htp_options.precision = kHtpFp16;
  qopt.htp_options.pd_session = kHtpUnsignedPd;
  qopt.log_level = kLogLevelWarn;
  g.qnn = TfLiteQnnDelegateCreate(&qopt);
  if (!g.qnn) { g.err = "QNN delegate create failed"; NPLOG("init failed: %s", g.err.c_str()); return false; }
  TfLiteInterpreterOptionsAddDelegate(opts, g.qnn);
  g.interp = TfLiteInterpreterCreate(g.model, opts);
  TfLiteInterpreterOptionsDelete(opts);
  if (!g.interp) { g.err = "matmul interpreter create failed"; NPLOG("init failed: %s", g.err.c_str()); return false; }
  if (TfLiteInterpreterAllocateTensors(g.interp) != kTfLiteOk) { g.err = "matmul allocate failed"; NPLOG("init failed: %s", g.err.c_str()); return false; }
  if (TfLiteInterpreterGetInputTensorCount(g.interp) < 2 || TfLiteInterpreterGetOutputTensorCount(g.interp) < 1) {
    g.err = "matmul tensor count mismatch";
    NPLOG("init failed: %s", g.err.c_str());
    return false;
  }
  g.inA = const_cast<TfLiteTensor*>(TfLiteInterpreterGetInputTensor(g.interp, 0));
  g.inB = const_cast<TfLiteTensor*>(TfLiteInterpreterGetInputTensor(g.interp, 1));
  g.outC = const_cast<TfLiteTensor*>(TfLiteInterpreterGetOutputTensor(g.interp, 0));
  g.aPad.assign((size_t)NP * KD, 0.f);
  g.bPad.assign((size_t)KD * NP, 0.f);
  g.cOut.assign((size_t)NP * NP, 0.f);
  g.ok = true;
  NPLOG("matmul matcher ready");
  return true;
}

bool implMatch(const float* dA, int nA, const float* dB, int nB, float* bestSim, float* secondSim, int* bestA) {
  if (!g.ok || nA <= 0 || nB <= 0 || nA > NP || nB > NP) return false;
  auto t0 = std::chrono::steady_clock::now();
  std::memset(g.aPad.data(), 0, g.aPad.size() * sizeof(float));
  std::memcpy(g.aPad.data(), dB, (size_t)nB * KD * sizeof(float));
  std::memset(g.bPad.data(), 0, g.bPad.size() * sizeof(float));
  for (int a = 0; a < nA; a++) {
    const float* src = dA + (size_t)a * KD;
    for (int k = 0; k < KD; k++) g.bPad[(size_t)k * NP + a] = src[k];
  }
  TfLiteTensorCopyFromBuffer(g.inA, g.aPad.data(), g.aPad.size() * sizeof(float));
  TfLiteTensorCopyFromBuffer(g.inB, g.bPad.data(), g.bPad.size() * sizeof(float));
  if (TfLiteInterpreterInvoke(g.interp) != kTfLiteOk) return false;
  TfLiteTensorCopyToBuffer(g.outC, g.cOut.data(), g.cOut.size() * sizeof(float));
  for (int b = 0; b < nB; b++) {
    const float* row = g.cOut.data() + (size_t)b * NP;
    float best = -2.f, second = -2.f;
    int bi = -1;
    for (int a = 0; a < nA; a++) {
      float s = row[a];
      if (s > best) { second = best; best = s; bi = a; }
      else if (s > second) second = s;
    }
    bestA[b] = bi;
    bestSim[b] = best;
    secondSim[b] = second;
  }
  static int count = 0;
  if ((++count % 120) == 0) {
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    NPLOG("match nA=%d nB=%d %.1f ms", nA, nB, ms);
  }
  return true;
}

} // namespace

extern "C" int ovmm_init(const char* libDir, const char* modelPath) { return implInit(libDir, modelPath) ? 1 : 0; }

extern "C" int ovmm_match(const float* dA, int nA, const float* dB, int nB, float* bestSim, float* secondSim, int* bestA) {
  return implMatch(dA, nA, dB, nB, bestSim, secondSim, bestA) ? 1 : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawmechatronics_easyscanner_SpTflite_matmulInit(JNIEnv* env, jclass, jstring libDirJ, jstring modelPathJ) {
  const char* libDir = env->GetStringUTFChars(libDirJ, nullptr);
  const char* modelPath = env->GetStringUTFChars(modelPathJ, nullptr);
  int ok = ovmm_init(libDir, modelPath);
  env->ReleaseStringUTFChars(libDirJ, libDir);
  env->ReleaseStringUTFChars(modelPathJ, modelPath);
  return ok;
}
