#include <jni.h>
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>
#include <random>

#include "tensorflow/lite/core/c/c_api.h"
#include "QnnTFLiteDelegate.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "SPTFLITE", __VA_ARGS__)

namespace {
constexpr int W = 640;
constexpr int H = 480;
constexpr int WH = W * H;
constexpr int DESC = 256 * 60 * 80;
constexpr int TOP_K = 1000;
constexpr float THRESH = 0.001f;
constexpr int NMS = 4;

struct Engine {
  TfLiteModel* model = nullptr;
  TfLiteInterpreter* interp = nullptr;
  TfLiteDelegate* qnn = nullptr;
  TfLiteTensor* in_a = nullptr;
  TfLiteTensor* in_b = nullptr;
  TfLiteTensor* scores_a = nullptr;
  TfLiteTensor* desc_a = nullptr;
  TfLiteTensor* scores_b = nullptr;
  TfLiteTensor* desc_b = nullptr;
  std::vector<float> img_a;
  std::vector<float> img_b;
  std::vector<float> sc_a;
  std::vector<float> de_a;
  std::vector<float> sc_b;
  std::vector<float> de_b;
  bool have_prev = false;
  double fx = 600, fy = 600, cx = 320, cy = 240;
  std::vector<float> kp_a_x, kp_a_y, kp_a_s;
};

std::string tensorName(const TfLiteTensor* t) {
  const char* n = TfLiteTensorName(t);
  return n ? std::string(n) : std::string();
}

bool isScores(const TfLiteTensor* t) { return TfLiteTensorNumDims(t) == 3; }
}


namespace {
// Classic Jacobi eigen decomposition of a symmetric n x n matrix (row-major).
void jacobi(int n, double* A, double* evals, double* evecs) {
  for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) evecs[i * n + j] = (i == j) ? 1.0 : 0.0;
  for (int sweep = 0; sweep < 60; sweep++) {
    double off = 0;
    for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) off += A[i * n + j] * A[i * n + j];
    if (off < 1e-18) break;
    for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) {
      double apq = A[p * n + q];
      if (fabs(apq) < 1e-15) continue;
      double app = A[p * n + p], aqq = A[q * n + q];
      double theta = 0.5 * (aqq - app) / apq;
      double t = (theta >= 0 ? 1.0 : -1.0) / (fabs(theta) + sqrt(1 + theta * theta));
      double c = 1 / sqrt(1 + t * t), s = t * c;
      for (int k = 0; k < n; k++) {
        double akp = A[k * n + p], akq = A[k * n + q];
        A[k * n + p] = c * akp - s * akq; A[k * n + q] = s * akp + c * akq;
      }
      for (int k = 0; k < n; k++) {
        double apk = A[p * n + k], aqk = A[q * n + k];
        A[p * n + k] = c * apk - s * aqk; A[q * n + k] = s * apk + c * aqk;
      }
      for (int k = 0; k < n; k++) {
        double vkp = evecs[k * n + p], vkq = evecs[k * n + q];
        evecs[k * n + p] = c * vkp - s * vkq; evecs[k * n + q] = s * vkp + c * vkq;
      }
    }
  }
  for (int i = 0; i < n; i++) evals[i] = A[i * n + i];
}

// SVD of a 3x3 matrix (row-major): M = U diag(s) V^T
void svd3(const double* M, double* U, double* s, double* V) {
  double A[9], ATA[9], ev[3], evec[9];
  for (int i = 0; i < 9; i++) A[i] = M[i];
  // M^T M = V diag(s^2) V^T
  for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
    double sum = 0; for (int k = 0; k < 3; k++) sum += A[k * 3 + i] * A[k * 3 + j];
    ATA[i * 3 + j] = sum;
  }
  jacobi(3, ATA, ev, evec);
  // sort descending
  int idx[3] = {0, 1, 2};
  for (int a = 0; a < 2; a++) for (int b = 0; b < 2 - a; b++)
    if (ev[idx[b]] < ev[idx[b + 1]]) { int t = idx[b]; idx[b] = idx[b + 1]; idx[b + 1] = t; }
  for (int c = 0; c < 3; c++) s[c] = sqrt(std::max(0.0, ev[idx[c]]));
  for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) V[r * 3 + c] = evec[r * 3 + idx[c]];
  // U = M V diag(1/s)
  for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
    double sum = 0;
    for (int k = 0; k < 3; k++) sum += A[r * 3 + k] * V[k * 3 + c];
    U[r * 3 + c] = s[c] > 1e-12 ? sum / s[c] : 0.0;
  }
  // fill a possible zero column of U from the cross product
  if (s[2] < 1e-12) {
    U[0 * 3 + 2] = U[1 * 3 + 0] * U[2 * 3 + 1] - U[2 * 3 + 0] * U[1 * 3 + 1];
    U[1 * 3 + 2] = U[2 * 3 + 0] * U[0 * 3 + 1] - U[0 * 3 + 0] * U[2 * 3 + 1];
    U[2 * 3 + 2] = U[0 * 3 + 0] * U[1 * 3 + 1] - U[1 * 3 + 0] * U[0 * 3 + 1];
  }
}

// Eight-point essential matrix from normalized point pairs.
bool essential8(const std::vector<double>& a, const std::vector<double>& b, double* E) {
  double AtA[81] = {0}, ev[9], evec[81];
  for (size_t i = 0; i < a.size(); i += 2) {
    double x1 = a[i], y1 = a[i + 1], x2 = b[i], y2 = b[i + 1];
    double row[9] = {x2 * x1, x2 * y1, x2, y2 * x1, y2 * y1, y2, x1, y1, 1.0};
    for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++) AtA[r * 9 + c] += row[r] * row[c];
  }
  jacobi(9, AtA, ev, evec);
  int best = 0; for (int i = 1; i < 9; i++) if (ev[i] < ev[best]) best = i;
  for (int i = 0; i < 9; i++) E[i] = evec[i * 9 + best];
  return true;
}

// Rank-2 projection of E and decomposition into (R, t).
bool decomposeE(const double* E, double* R1out, double* R2out, double* tout) {
  double U[9], s[3], V[9];
  svd3(E, U, s, V);
  // enforce rank 2: E = U diag(s0, s1, 0) V^T
  double W[9] = {0, -1, 0, 1, 0, 0, 0, 0, 1};
  // R1 = U W V^T, R2 = U W^T V^T
  for (int c = 0; c < 3; c++) tout[c] = U[c * 3 + 2];
  double R1[9], R2[9];
  auto mul = [](const double* A, const double* B, double* C) {
    for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
      double sum = 0; for (int k = 0; k < 3; k++) sum += A[i * 3 + k] * B[k * 3 + j];
      C[i * 3 + j] = sum;
    }
  };
  double Vt[9]; for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) Vt[i * 3 + j] = V[j * 3 + i];
  double UW[9], UWT[9];
  mul(U, W, UW);
  double WT[9] = {0, 1, 0, -1, 0, 0, 0, 0, 1};
  mul(U, WT, UWT);
  mul(UW, Vt, R1);
  mul(UWT, Vt, R2);
  // pick R with determinant +1, both are rotations if det ok
  auto det3 = [](const double* M) { return M[0] * (M[4] * M[8] - M[5] * M[7]) - M[1] * (M[3] * M[8] - M[5] * M[6]) + M[2] * (M[3] * M[7] - M[4] * M[6]); };
  if (det3(R1) < 0) for (int i = 0; i < 9; i++) R1[i] = -R1[i];
  if (det3(R2) < 0) for (int i = 0; i < 9; i++) R2[i] = -R2[i];
  memcpy(R1out, R1, 9 * sizeof(double));
  memcpy(R2out, R2, 9 * sizeof(double));
  return true;
}
}


namespace {
// Count inlier points that triangulate in front of both cameras for a pose.
int frontCount(const double* R, const double* t, const std::vector<double>& a, const std::vector<double>& b,
               const std::vector<char>& mask) {
  int front = 0;
  double P2[12];
  for (int r = 0; r < 3; r++) {
    for (int c = 0; c < 3; c++) P2[r * 4 + c] = R[r * 3 + c];
    P2[r * 4 + 3] = t[r];
  }
  double P1[12] = {1,0,0,0, 0,1,0,0, 0,0,1,0};
  for (size_t i = 0; i < mask.size(); i++) {
    if (!mask[i]) continue;
    double u1 = a[i * 2], v1 = a[i * 2 + 1], u2 = b[i * 2], v2 = b[i * 2 + 1];
    double ATA[16] = {0};
    auto addRow = [&](const double* row) {
      for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) ATA[r * 4 + c] += row[r] * row[c];
    };
    double r1[4], r2[4], r3[4], r4[4];
    for (int k = 0; k < 4; k++) {
      r1[k] = u1 * P1[8 + k] - P1[0 + k];
      r2[k] = v1 * P1[8 + k] - P1[4 + k];
      r3[k] = u2 * P2[8 + k] - P2[0 + k];
      r4[k] = v2 * P2[8 + k] - P2[4 + k];
    }
    addRow(r1); addRow(r2); addRow(r3); addRow(r4);
    double ev[4], evec[16];
    jacobi(4, ATA, ev, evec);
    int best = 0; for (int k = 1; k < 4; k++) if (ev[k] < ev[best]) best = k;
    double X[4]; for (int k = 0; k < 4; k++) X[k] = evec[k * 4 + best];
    if (fabs(X[3]) < 1e-12) continue;
    double w = X[3];
    double z1 = X[2] / w;
    double z2 = (P2[8] * X[0] + P2[9] * X[1] + P2[10] * X[2] + P2[11] * X[3]) / w;
    if (z1 > 0 && z2 > 0) front++;
  }
  return front;
}

void choosePose(const double* E, const std::vector<double>& a, const std::vector<double>& b,
                const std::vector<char>& mask, double* R, double* t) {
  double R1[9], R2[9], tv[3];
  decomposeE(E, R1, R2, tv);
  int best = -1; double bestR[9], bestT[3];
  double cand[4][12];
  memcpy(cand[0], R1, 9 * sizeof(double));
  for (int i = 0; i < 3; i++) cand[0][9 + i] = tv[i];
  memcpy(cand[1], R1, 9 * sizeof(double));
  for (int i = 0; i < 3; i++) cand[1][9 + i] = -tv[i];
  memcpy(cand[2], R2, 9 * sizeof(double));
  for (int i = 0; i < 3; i++) cand[2][9 + i] = tv[i];
  memcpy(cand[3], R2, 9 * sizeof(double));
  for (int i = 0; i < 3; i++) cand[3][9 + i] = -tv[i];
  for (int c = 0; c < 4; c++) {
    int n = frontCount(cand[c], cand[c] + 9, a, b, mask);
    if (n > best) { best = n; memcpy(bestR, cand[c], 9 * sizeof(double)); memcpy(bestT, cand[c] + 9, 3 * sizeof(double)); }
  }
  if (best > 0) { memcpy(R, bestR, 9 * sizeof(double)); memcpy(t, bestT, 3 * sizeof(double)); }
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawmechatronics_easyscanner_SpTflite_create(JNIEnv* env, jclass, jstring libDirJ, jstring modelPathJ, jdouble fxJ, jdouble fyJ, jdouble cxJ, jdouble cyJ) {
  const char* libDir = env->GetStringUTFChars(libDirJ, nullptr);
  const char* modelPath = env->GetStringUTFChars(modelPathJ, nullptr);
  auto* e = new Engine();
  e->fx = fxJ; e->fy = fyJ; e->cx = cxJ; e->cy = cyJ;
  e->img_a.assign(3 * WH, 0.f);
  e->img_b.assign(3 * WH, 0.f);
  e->sc_a.assign(WH, 0.f);
  e->sc_b.assign(WH, 0.f);
  e->de_a.assign(DESC, 0.f);
  e->de_b.assign(DESC, 0.f);

  e->model = TfLiteModelCreateFromFile(modelPath);
  if (!e->model) { LOG("model create failed"); delete e; return -1; }
  auto* opts = TfLiteInterpreterOptionsCreate();
  TfLiteInterpreterOptionsSetNumThreads(opts, 4);

  auto qopt = TfLiteQnnDelegateOptionsDefault();
  qopt.backend_type = kHtpBackend;
  std::string backendLib = std::string(libDir) + "/libQnnHtp.so";
  std::string skelDir = std::string(libDir);
  qopt.library_path = backendLib.c_str();
  qopt.skel_library_dir = skelDir.c_str();
  qopt.htp_options.performance_mode = kHtpBurst;
  qopt.htp_options.precision = kHtpFp16;
  qopt.htp_options.pd_session = kHtpUnsignedPd;
  qopt.log_level = kLogLevelInfo;
  qopt.profiling = kBasicProfiling;
  e->qnn = TfLiteQnnDelegateCreate(&qopt);
  if (!e->qnn) { LOG("QNN delegate create failed"); TfLiteInterpreterOptionsDelete(opts); TfLiteModelDelete(e->model); delete e; return -2; }
  TfLiteInterpreterOptionsAddDelegate(opts, e->qnn);

  e->interp = TfLiteInterpreterCreate(e->model, opts);
  TfLiteInterpreterOptionsDelete(opts);
  if (!e->interp) { LOG("interpreter create failed"); TfLiteQnnDelegateDelete(e->qnn); TfLiteModelDelete(e->model); delete e; return -3; }
  if (TfLiteInterpreterAllocateTensors(e->interp) != kTfLiteOk) {
    LOG("allocate tensors failed");
    TfLiteInterpreterDelete(e->interp); TfLiteQnnDelegateDelete(e->qnn); TfLiteModelDelete(e->model); delete e; return -4;
  }

  int nIn = TfLiteInterpreterGetInputTensorCount(e->interp);
  int nOut = TfLiteInterpreterGetOutputTensorCount(e->interp);
  LOG("tflite ready: inputs=%d outputs=%d", nIn, nOut);
  for (int i = 0; i < nIn; i++) {
    auto* t = const_cast<TfLiteTensor*>(TfLiteInterpreterGetInputTensor(e->interp, i));
    LOG("input %d name=%s dims=%d", i, tensorName(t).c_str(), TfLiteTensorNumDims(t));
    if (i == 0) e->in_a = t; else e->in_b = t;
  }
  for (int i = 0; i < nOut; i++) {
    auto* t = const_cast<TfLiteTensor*>(TfLiteInterpreterGetOutputTensor(e->interp, i));
    std::string n = tensorName(t);
    LOG("output %d name=%s dims=%d", i, n.c_str(), TfLiteTensorNumDims(t));
    if (n.find("scores_a") != std::string::npos) e->scores_a = t;
    else if (n.find("scores_b") != std::string::npos) e->scores_b = t;
    else if (n.find("descriptors_a") != std::string::npos) e->desc_a = t;
    else if (n.find("descriptors_b") != std::string::npos) e->desc_b = t;
  }
  // Quantized/renamed graphs may not keep scores_a/descriptors_a names: map by tensor shape.
  {
    std::vector<TfLiteTensor*> scoreT, descT;
    for (int i = 0; i < nOut; i++) {
      auto* t = const_cast<TfLiteTensor*>(TfLiteInterpreterGetOutputTensor(e->interp, i));
      if (!t) continue;
      int nd = TfLiteTensorNumDims(t);
      if (nd == 3 && TfLiteTensorDim(t, 1) == H && TfLiteTensorDim(t, 2) == W) scoreT.push_back(t);
      else if (nd == 4 && TfLiteTensorDim(t, 1) == 256) descT.push_back(t);
    }
    if (!e->scores_a && !scoreT.empty()) e->scores_a = scoreT[0];
    if (!e->scores_b && scoreT.size() > 1) e->scores_b = scoreT[1];
    if (!e->desc_a && !descT.empty()) e->desc_a = descT[0];
    if (!e->desc_b && descT.size() > 1) e->desc_b = descT[1];
  }

  env->ReleaseStringUTFChars(libDirJ, libDir);
  env->ReleaseStringUTFChars(modelPathJ, modelPath);
  return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_rawmechatronics_easyscanner_SpTflite_run(JNIEnv* env, jclass, jlong handle, jbyteArray grayJ) {
  auto* e = reinterpret_cast<Engine*>(handle);
  if (!e || !e->interp) return nullptr;
  jsize len = env->GetArrayLength(grayJ);
  if (len != WH) return nullptr;
  jbyte* gray = env->GetByteArrayElements(grayJ, nullptr);
  for (int c = 0; c < 3; c++) {
    float* dst = e->img_b.data() + c * WH;
    for (int i = 0; i < WH; i++) dst[i] = (static_cast<unsigned char>(gray[i])) * (1.0f / 255.0f);
  }
  env->ReleaseByteArrayElements(grayJ, gray, JNI_ABORT);
  if (!e->have_prev) { e->img_a = e->img_b; e->have_prev = true; }

  auto inBytes = static_cast<size_t>(3 * WH) * sizeof(float);
  auto t0 = std::chrono::steady_clock::now();
  double inferMs = 0.0;
  TfLiteStatus st = kTfLiteOk;
  if (e->in_b) {
    TfLiteTensorCopyFromBuffer(e->in_a, e->img_a.data(), inBytes);
    TfLiteTensorCopyFromBuffer(e->in_b, e->img_b.data(), inBytes);
    st = TfLiteInterpreterInvoke(e->interp);
    auto t1 = std::chrono::steady_clock::now();
    inferMs = std::chrono::duration<double, std::milli>(t1 - t0).count();
    if (st != kTfLiteOk) { LOG("invoke failed"); return nullptr; }
    if (e->scores_a) TfLiteTensorCopyToBuffer(e->scores_a, e->sc_a.data(), static_cast<size_t>(WH) * sizeof(float));
    if (e->desc_a) TfLiteTensorCopyToBuffer(e->desc_a, e->de_a.data(), static_cast<size_t>(DESC) * sizeof(float));
    if (e->scores_b) TfLiteTensorCopyToBuffer(e->scores_b, e->sc_b.data(), static_cast<size_t>(WH) * sizeof(float));
    if (e->desc_b) TfLiteTensorCopyToBuffer(e->desc_b, e->de_b.data(), static_cast<size_t>(DESC) * sizeof(float));
  } else {
    // Single-image SuperPoint model (the matched double-branch graph reduced to one branch):
    // run it twice, once per image, to keep the two-image matcher API unchanged.
    TfLiteTensorCopyFromBuffer(e->in_a, e->img_a.data(), inBytes);
    st = TfLiteInterpreterInvoke(e->interp);
    auto t1 = std::chrono::steady_clock::now();
    inferMs = std::chrono::duration<double, std::milli>(t1 - t0).count();
    if (st != kTfLiteOk) { LOG("invoke failed"); return nullptr; }
    TfLiteTensorCopyToBuffer(e->scores_a, e->sc_a.data(), static_cast<size_t>(WH) * sizeof(float));
    TfLiteTensorCopyToBuffer(e->desc_a, e->de_a.data(), static_cast<size_t>(DESC) * sizeof(float));
    TfLiteTensorCopyFromBuffer(e->in_a, e->img_b.data(), inBytes);
    st = TfLiteInterpreterInvoke(e->interp);
    if (st != kTfLiteOk) { LOG("invoke failed"); return nullptr; }
    TfLiteTensorCopyToBuffer(e->scores_a, e->sc_b.data(), static_cast<size_t>(WH) * sizeof(float));
    TfLiteTensorCopyToBuffer(e->desc_a, e->de_b.data(), static_cast<size_t>(DESC) * sizeof(float));
  }

  struct Kp { float x, y, s; };
  auto extract = [](const float* sc, int cap) {
    std::vector<Kp> out; out.reserve(2048);
    for (int y = NMS; y < H - NMS; y++) for (int x = NMS; x < W - NMS; x++) {
      float v = sc[y * W + x];
      if (v <= THRESH) continue;
      bool mx = true;
      for (int dy = -NMS; dy <= NMS && mx; dy++) for (int dx = -NMS; dx <= NMS && mx; dx++)
        if ((dx || dy) && sc[(y + dy) * W + (x + dx)] > v) mx = false;
      if (mx) out.push_back({(float)x, (float)y, v});
    }
    std::sort(out.begin(), out.end(), [](const Kp& a, const Kp& b) { return a.s > b.s; });
    if ((int)out.size() > cap) out.resize(cap);
    return out;
  };
  auto kpA = extract(e->sc_a.data(), 400);
  auto kpB = extract(e->sc_b.data(), 400);

  // descriptor per keypoint, sampled from the stride-8 descriptor map
  const int DW = 80, DH = 60;
  auto descAt = [&](const std::vector<float>& d, const Kp& k, float* out) {
    int col = std::min(DW - 1, std::max(0, (int)(k.x) / 8));
    int row = std::min(DH - 1, std::max(0, (int)(k.y) / 8));
    int base = row * DW + col;
    double norm = 0;
    for (int c = 0; c < 256; c++) { float v = d[c * DW * DH + base]; out[c] = v; norm += v * v; }
    norm = std::sqrt(norm) + 1e-9;
    for (int c = 0; c < 256; c++) out[c] = (float)(out[c] / norm);
  };
  std::vector<float> dA(kpA.size() * 256), dB(kpB.size() * 256);
  for (size_t i = 0; i < kpA.size(); i++) descAt(e->de_a, kpA[i], dA.data() + i * 256);
  for (size_t i = 0; i < kpB.size(); i++) descAt(e->de_b, kpB[i], dB.data() + i * 256);

  auto m0 = std::chrono::steady_clock::now();
  struct Match { int a, b; float sim; };
  std::vector<Match> matches;
  std::vector<int> aBest(kpA.size(), -1);
  std::vector<float> aBestSim(kpA.size(), -2.f);
  for (size_t bi = 0; bi < kpB.size(); bi++) {
    float best = -2.f, second = -2.f; int bestA = -1;
    for (size_t ai = 0; ai < kpA.size(); ai++) {
      float sim = 0;
      const float* bp = dB.data() + bi * 256; const float* ap = dA.data() + ai * 256;
      for (int c = 0; c < 256; c++) sim += bp[c] * ap[c];
      if (sim > best) { second = best; best = sim; bestA = (int)ai; }
      else if (sim > second) second = sim;
    }
    if (bestA >= 0 && (1.f - best) < 0.75f * (1.f - second)) {
      matches.push_back({bestA, (int)bi, best});
      if (best > aBestSim[bestA]) { aBestSim[bestA] = best; aBest[bestA] = (int)bi; }
    }
  }
  // mutual check
  std::vector<Match> mutual;
  for (auto& m : matches) if (aBest[m.a] == m.b) mutual.push_back(m);
  std::sort(mutual.begin(), mutual.end(), [](const Match& x, const Match& y) { return x.sim > y.sim; });
  if (mutual.size() > 300) mutual.resize(300);

  // normalized image coordinates
  std::vector<double> na, nb;
  for (auto& m : mutual) {
    na.push_back((kpA[m.a].x - e->cx) / e->fx); na.push_back((kpA[m.a].y - e->cy) / e->fy);
    nb.push_back((kpB[m.b].x - e->cx) / e->fx); nb.push_back((kpB[m.b].y - e->cy) / e->fy);
  }

  // RANSAC eight-point essential matrix
  int inliers = 0; double rotDeg = 0; std::vector<char> bestMask(mutual.size(), 0);
  if (mutual.size() >= 8) {
    const double thresh = 0.01;
    std::mt19937 rng(12345);
    for (int iter = 0; iter < 300; iter++) {
      int idx[8];
      bool ok = true;
      for (int i = 0; i < 8; i++) {
        idx[i] = (int)(rng() % mutual.size());
        for (int j = 0; j < i; j++) if (idx[j] == idx[i]) { ok = false; break; }
        if (!ok) break;
      }
      if (!ok) continue;
      std::vector<double> sa, sb;
      for (int i = 0; i < 8; i++) {
        sa.push_back(na[idx[i] * 2]); sa.push_back(na[idx[i] * 2 + 1]);
        sb.push_back(nb[idx[i] * 2]); sb.push_back(nb[idx[i] * 2 + 1]);
      }
      double E[9];
      essential8(sa, sb, E);
      int cnt = 0; std::vector<char> mask(mutual.size(), 0);
      for (size_t i = 0; i < mutual.size(); i++) {
        double x1 = na[i * 2], y1 = na[i * 2 + 1], x2 = nb[i * 2], y2 = nb[i * 2 + 1];
        double Ex = E[0] * x1 + E[1] * y1 + E[2];
        double Ey = E[3] * x1 + E[4] * y1 + E[5];
        double Ez = E[6] * x1 + E[7] * y1 + E[8];
        double Etx = E[0] * x2 + E[3] * y2 + E[6];
        double Ety = E[1] * x2 + E[4] * y2 + E[7];
        double e = x2 * Ex + y2 * Ey + Ez;
        double den = Ex * Ex + Ey * Ey + Etx * Etx + Ety * Ety;
        if (den < 1e-18) continue;
        if ((e * e) / den < thresh * thresh) { cnt++; mask[i] = 1; }
      }
      if (cnt > inliers) { inliers = cnt; bestMask = mask; }
    }
    // relative rotation from the best inlier set
    if (inliers >= 8) {
      std::vector<double> sa, sb;
      for (size_t i = 0; i < mutual.size(); i++) if (bestMask[i]) {
        sa.push_back(na[i * 2]); sa.push_back(na[i * 2 + 1]);
        sb.push_back(nb[i * 2]); sb.push_back(nb[i * 2 + 1]);
      }
      double E[9]; essential8(sa, sb, E);
      double R[9], t[3];
      std::vector<char> all(sa.size() / 2, 1);
      choosePose(E, sa, sb, all, R, t);
      double tr = R[0] + R[4] + R[8];
      rotDeg = std::acos(std::min(1.0, std::max(-1.0, (tr - 1) / 2))) * 180.0 / M_PI;
    }
  }
  auto m1 = std::chrono::steady_clock::now();
  double matchMs = std::chrono::duration<double, std::milli>(m1 - m0).count();

  e->img_a = e->img_b;

  // result: header, then current keypoints, then inlier matches (current -> previous)
  int shownMatches = 0;
  for (size_t i = 0; i < mutual.size(); i++) if (bestMask[i]) shownMatches++;
  if (shownMatches > 200) shownMatches = 200;
  std::vector<float> out;
  out.reserve(7 + kpB.size() * 3 + shownMatches * 4);
  out.push_back((float)inferMs);
  out.push_back((float)matchMs);
  out.push_back((float)kpB.size());
  out.push_back((float)mutual.size());
  out.push_back((float)inliers);
  out.push_back((float)rotDeg);
  out.push_back((float)shownMatches);
  for (auto& k : kpB) { out.push_back(k.x); out.push_back(k.y); out.push_back(k.s); }
  int added = 0;
  for (size_t i = 0; i < mutual.size() && added < shownMatches; i++) {
    if (!bestMask[i]) continue;
    auto& m = mutual[i];
    out.push_back(kpB[m.b].x); out.push_back(kpB[m.b].y);
    out.push_back(kpA[m.a].x); out.push_back(kpA[m.a].y);
    added++;
  }
  jfloatArray arr = env->NewFloatArray((jsize)out.size());
  if (!arr) return nullptr;
  env->SetFloatArrayRegion(arr, 0, (jsize)out.size(), out.data());
  return arr;
}

static int g_feat_cap = 300;

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_SpTflite_setFeatureCap(JNIEnv*, jclass, jint cap) {
  g_feat_cap = cap < 20 ? 20 : (cap > 2000 ? 2000 : cap);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_rawmechatronics_easyscanner_SpTflite_features(JNIEnv* env, jclass, jlong handle, jbyteArray grayJ) {
  { intptr_t hi = reinterpret_cast<intptr_t>(handle); if (hi >= -4 && hi <= 0) return nullptr; }
  auto* e = reinterpret_cast<Engine*>(handle);
  if (!e || !e->interp) return nullptr;
  jsize len = env->GetArrayLength(grayJ);
  if (len != WH) return nullptr;
  jbyte* gray = env->GetByteArrayElements(grayJ, nullptr);
  for (int c = 0; c < 3; c++) {
    float* dst = e->img_b.data() + c * WH;
    for (int i = 0; i < WH; i++) dst[i] = (static_cast<unsigned char>(gray[i])) * (1.0f / 255.0f);
  }
  env->ReleaseByteArrayElements(grayJ, gray, JNI_ABORT);
  e->img_a = e->img_b;
  auto inBytes = static_cast<size_t>(3 * WH) * sizeof(float);
  TfLiteTensorCopyFromBuffer(e->in_a, e->img_a.data(), inBytes);
  if (e->in_b) TfLiteTensorCopyFromBuffer(e->in_b, e->img_b.data(), inBytes);
  if (TfLiteInterpreterInvoke(e->interp) != kTfLiteOk) return nullptr;
  TfLiteTensorCopyToBuffer(e->scores_a, e->sc_a.data(), static_cast<size_t>(WH) * sizeof(float));
  TfLiteTensorCopyToBuffer(e->desc_a, e->de_a.data(), static_cast<size_t>(DESC) * sizeof(float));
  struct FKp { float x, y, s; };
  std::vector<FKp> kps; kps.reserve(2048);
  for (int y = NMS; y < H - NMS; y++) for (int x = NMS; x < W - NMS; x++) {
    float v = e->sc_a[y * W + x];
    if (v <= THRESH) continue;
    bool mx = true;
    for (int dy = -NMS; dy <= NMS && mx; dy++) for (int dx = -NMS; dx <= NMS && mx; dx++)
      if ((dx || dy) && e->sc_a[(y + dy) * W + (x + dx)] > v) mx = false;
    if (mx) kps.push_back({(float)x, (float)y, v});
  }
  std::sort(kps.begin(), kps.end(), [](const FKp& a, const FKp& b) { return a.s > b.s; });
  if ((int)kps.size() > g_feat_cap) kps.resize(g_feat_cap);
  const int DW = 80, DH = 60;
  std::vector<float> out;
  out.reserve(1 + kps.size() * 2 + kps.size() * 256);
  out.push_back((float)kps.size());
  for (auto& k : kps) { out.push_back(k.x); out.push_back(k.y); }
  for (auto& k : kps) {
    int col = std::min(DW - 1, std::max(0, (int)(k.x) / 8));
    int row = std::min(DH - 1, std::max(0, (int)(k.y) / 8));
    int bidx = row * DW + col;
    double norm = 0;
    float d[256];
    for (int c = 0; c < 256; c++) { d[c] = e->de_a[c * DW * DH + bidx]; norm += d[c] * d[c]; }
    norm = std::sqrt(norm) + 1e-9;
    for (int c = 0; c < 256; c++) out.push_back((float)(d[c] / norm));
  }
  jfloatArray arr = env->NewFloatArray((jsize)out.size());
  if (!arr) return nullptr;
  env->SetFloatArrayRegion(arr, 0, (jsize)out.size(), out.data());
  return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_SpTflite_close(JNIEnv*, jclass, jlong handle) {
  auto* e = reinterpret_cast<Engine*>(handle);
  if (!e) return;
  if (e->interp) TfLiteInterpreterDelete(e->interp);
  if (e->qnn) TfLiteQnnDelegateDelete(e->qnn);
  if (e->model) TfLiteModelDelete(e->model);
  delete e;
}
