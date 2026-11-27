#include <jni.h>
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>
#include <random>
#include <sys/stat.h>

#include "tensorflow/lite/core/c/c_api.h"
#include "QnnTFLiteDelegate.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "SPTFLITE", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "SPTFLITE", __VA_ARGS__)

namespace {
constexpr int W = 640;
constexpr int H = 480;
constexpr int WH = W * H;
constexpr int DW = 80;
constexpr int DH = 60;
constexpr int DESC_DIM = 64;
constexpr int DESC_SIZE = DH * DW * DESC_DIM;
constexpr int HM_SIZE = DH * DW;
static int g_feat_cap = 400;
constexpr float THRESH_PRIMARY = 0.05f;
constexpr float THRESH_LOW_TEX = 0.005f;
constexpr int NMS = 2;       // XFeat official kernel_size=5 (radius 2)
constexpr int BORDER = 4;    // margin from image edge

struct Kp { float x, y, s; };

struct Engine {
  TfLiteModel* model = nullptr;
  TfLiteInterpreter* interp = nullptr;
  TfLiteDelegate* qnn = nullptr;
  TfLiteTensor* in_img = nullptr;
  TfLiteTensor* out_scores = nullptr;   // [1, 480, 640]
  TfLiteTensor* out_hm = nullptr;       // [1, 60, 80]
  TfLiteTensor* out_desc = nullptr;     // [1, 60, 80, 64] NHWC
  std::string backendName = "CPU";
  std::vector<float> img_buf;
  std::vector<float> sc_buf;
  std::vector<float> hm_buf;
  std::vector<float> de_buf;
  // Cached previous frame keypoints + 64-D descriptors for SpTflite_run() self-test
  bool have_prev = false;
  double fx = 600, fy = 600, cx = 320, cy = 240;
  std::vector<Kp> prev_kp;
  std::vector<float> prev_desc;
};

std::string tensorName(const TfLiteTensor* t) {
  const char* n = TfLiteTensorName(t);
  return n ? std::string(n) : std::string();
}

// Convert 1-channel grayscale (W*H) or planar 3-channel RGB (3*W*H) to InstanceNorm2d-normalized float.
// Running InstanceNorm2d in FP64 here matches PyTorch nn.InstanceNorm2d(1) exactly and avoids two
// full-resolution 480x640 global DDR reductions on Hexagon HTP.
bool fillGrayInput(JNIEnv* env, jbyteArray grayJ, std::vector<float>& dst) {
  jsize len = env->GetArrayLength(grayJ);
  if (len != WH && len != 3 * WH) return false;
  jbyte* pix = env->GetByteArrayElements(grayJ, nullptr);
  if (!pix) return false;
  const auto* u = reinterpret_cast<const unsigned char*>(pix);
  double sum = 0.0;
  double sumSq = 0.0;
  if (len == WH) {
    constexpr float kScale = 1.0f / 255.0f;
    for (int i = 0; i < WH; i++) {
      const float v = u[i] * kScale;
      dst[i] = v;
      sum += v;
      sumSq += (double)v * (double)v;
    }
  } else {
    constexpr float kScale = 1.0f / (3.0f * 255.0f);
    for (int i = 0; i < WH; i++) {
      const float v = static_cast<float>(u[i] + u[WH + i] + u[2 * WH + i]) * kScale;
      dst[i] = v;
      sum += v;
      sumSq += (double)v * (double)v;
    }
  }
  env->ReleaseByteArrayElements(grayJ, pix, JNI_ABORT);
  const double mean = sum / (double)WH;
  const double var = std::max(0.0, sumSq / (double)WH - mean * mean);
  const float fMean = (float)mean;
  const float invStd = (float)(1.0 / std::sqrt(var + 1e-5));
  for (int i = 0; i < WH; i++) dst[i] = (dst[i] - fMean) * invStd;
  return true;
}

// Bilinear interpolation on [DH, DW] matching PyTorch InterpolateSparse2d('bilinear', align_corners=False).
inline float sampleHeatmapBilinear(const float* hm, float x, float y) {
  const float gx = 2.0f * x / (float)(W - 1) - 1.0f;
  const float gy = 2.0f * y / (float)(H - 1) - 1.0f;
  float fx = (gx + 1.0f) * 0.5f * DW - 0.5f;
  float fy = (gy + 1.0f) * 0.5f * DH - 0.5f;
  fx = std::min((float)(DW - 1), std::max(0.0f, fx));
  fy = std::min((float)(DH - 1), std::max(0.0f, fy));
  const int x0 = (int)std::floor(fx), y0 = (int)std::floor(fy);
  const int x1 = std::min(DW - 1, x0 + 1), y1 = std::min(DH - 1, y0 + 1);
  const float ax = fx - (float)x0, ay = fy - (float)y0;
  const float v00 = hm[y0 * DW + x0];
  const float v10 = hm[y0 * DW + x1];
  const float v01 = hm[y1 * DW + x0];
  const float v11 = hm[y1 * DW + x1];
  return (1.0f - ay) * ((1.0f - ax) * v00 + ax * v10) + ay * ((1.0f - ax) * v01 + ax * v11);
}

// Keys cubic kernel (a = -0.75) matching PyTorch F.grid_sample(..., mode='bicubic', align_corners=False).
inline float cubicWeight(float x) {
  constexpr float a = -0.75f;
  const float ax = std::fabs(x);
  if (ax <= 1.0f) return ((a + 2.0f) * ax - (a + 3.0f)) * ax * ax + 1.0f;
  if (ax < 2.0f) return ((a * ax - 5.0f * a) * ax + 8.0f * a) * ax - 4.0f * a;
  return 0.0f;
}

// Bicubic interpolation of NHWC [DH, DW, 64] dense descriptors + L2 normalization.
void sampleDescriptorBicubic(const float* descNhwc, float x, float y, float* out) {
  const float gx = 2.0f * x / (float)(W - 1) - 1.0f;
  const float gy = 2.0f * y / (float)(H - 1) - 1.0f;
  const float fx = (gx + 1.0f) * 0.5f * DW - 0.5f;
  const float fy = (gy + 1.0f) * 0.5f * DH - 0.5f;
  const int ix = (int)std::floor(fx);
  const int iy = (int)std::floor(fy);
  const float tx = fx - (float)ix;
  const float ty = fy - (float)iy;

  float wx[4], wy[4];
  for (int k = 0; k < 4; k++) {
    wx[k] = cubicWeight((float)(k - 1) - tx);
    wy[k] = cubicWeight((float)(k - 1) - ty);
  }

  float acc[DESC_DIM] = {0.f};
  for (int j = 0; j < 4; j++) {
    const int yy = iy + j - 1;
    if (yy < 0 || yy >= DH) continue;
    const float wj = wy[j];
    const float* rowPtr = descNhwc + (size_t)yy * DW * DESC_DIM;
    for (int i = 0; i < 4; i++) {
      const int xx = ix + i - 1;
      if (xx < 0 || xx >= DW) continue;
      const float w = wj * wx[i];
      const float* cell = rowPtr + (size_t)xx * DESC_DIM;
      for (int c = 0; c < DESC_DIM; c++) acc[c] += w * cell[c];
    }
  }
  double normSq = 0.0;
  for (int c = 0; c < DESC_DIM; c++) normSq += (double)acc[c] * (double)acc[c];
  const float invNorm = (float)(1.0 / (std::sqrt(normSq) + 1e-9));
  for (int c = 0; c < DESC_DIM; c++) out[c] = acc[c] * invNorm;
}

// Run XFeat NPU inference + 5x5 NMS + reliability ranking + bicubic 64-D descriptors.
bool runXFeat(Engine* e, int cap, std::vector<Kp>& kpsOut, std::vector<float>& descOut, double* inferMsOut) {
  auto t0 = std::chrono::steady_clock::now();
  if (TfLiteTensorCopyFromBuffer(e->in_img, e->img_buf.data(), (size_t)WH * sizeof(float)) != kTfLiteOk) return false;
  if (TfLiteInterpreterInvoke(e->interp) != kTfLiteOk) return false;
  if (TfLiteTensorCopyToBuffer(e->out_scores, e->sc_buf.data(), (size_t)WH * sizeof(float)) != kTfLiteOk) return false;
  if (TfLiteTensorCopyToBuffer(e->out_hm, e->hm_buf.data(), (size_t)HM_SIZE * sizeof(float)) != kTfLiteOk) return false;
  if (TfLiteTensorCopyToBuffer(e->out_desc, e->de_buf.data(), (size_t)DESC_SIZE * sizeof(float)) != kTfLiteOk) return false;
  auto t1 = std::chrono::steady_clock::now();
  const double inferMs = std::chrono::duration<double, std::milli>(t1 - t0).count();
  if (inferMsOut) *inferMsOut = inferMs;

  const float* sc = e->sc_buf.data();
  const float* hm = e->hm_buf.data();
  auto detectAtThresh = [&](float thresh) {
    std::vector<Kp> out;
    out.reserve(1024);
    for (int y = BORDER; y < H - BORDER; y++) {
      const float* row = sc + y * W;
      for (int x = BORDER; x < W - BORDER; x++) {
        const float v = row[x];
        if (v <= thresh) continue;
        bool mx = true;
        for (int dy = -NMS; dy <= NMS && mx; dy++) {
          const float* r2 = sc + (y + dy) * W;
          for (int dx = -NMS; dx <= NMS && mx; dx++) {
            if ((dx || dy) && r2[x + dx] > v) mx = false;
          }
        }
        if (mx) {
          const float rel = sampleHeatmapBilinear(hm, (float)x, (float)y);
          out.push_back({(float)x, (float)y, v * rel});
        }
      }
    }
    return out;
  };

  kpsOut = detectAtThresh(THRESH_PRIMARY);
  if (kpsOut.size() < 40) {
    // Low-texture surface fallback so VIO never starves on smooth objects.
    kpsOut = detectAtThresh(THRESH_LOW_TEX);
  }
  std::sort(kpsOut.begin(), kpsOut.end(), [](const Kp& a, const Kp& b) { return a.s > b.s; });
  if ((int)kpsOut.size() > cap) kpsOut.resize(cap);

  descOut.resize(kpsOut.size() * DESC_DIM);
  for (size_t i = 0; i < kpsOut.size(); i++) {
    sampleDescriptorBicubic(e->de_buf.data(), kpsOut[i].x, kpsOut[i].y, descOut.data() + i * DESC_DIM);
  }
  auto t2 = std::chrono::steady_clock::now();
  static int s_log_cnt = 0;
  if (++s_log_cnt % 60 == 1) {
    const double postMs = std::chrono::duration<double, std::milli>(t2 - t1).count();
    LOG("XFeat [%s] infer=%.2fms post=%.2fms kp=%zu",
        e->backendName.c_str(), inferMs, postMs, kpsOut.size());
  }
  return true;
}

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

void svd3(const double* M, double* U, double* s, double* V) {
  double A[9], ATA[9], ev[3], evec[9];
  for (int i = 0; i < 9; i++) A[i] = M[i];
  for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
    double sum = 0; for (int k = 0; k < 3; k++) sum += A[k * 3 + i] * A[k * 3 + j];
    ATA[i * 3 + j] = sum;
  }
  jacobi(3, ATA, ev, evec);
  int idx[3] = {0, 1, 2};
  for (int a = 0; a < 2; a++) for (int b = 0; b < 2 - a; b++)
    if (ev[idx[b]] < ev[idx[b + 1]]) { int t = idx[b]; idx[b] = idx[b + 1]; idx[b + 1] = t; }
  for (int c = 0; c < 3; c++) s[c] = sqrt(std::max(0.0, ev[idx[c]]));
  for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) V[r * 3 + c] = evec[r * 3 + idx[c]];
  for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
    double sum = 0;
    for (int k = 0; k < 3; k++) sum += A[r * 3 + k] * V[k * 3 + c];
    U[r * 3 + c] = s[c] > 1e-12 ? sum / s[c] : 0.0;
  }
  if (s[2] < 1e-12) {
    U[0 * 3 + 2] = U[1 * 3 + 0] * U[2 * 3 + 1] - U[2 * 3 + 0] * U[1 * 3 + 1];
    U[1 * 3 + 2] = U[2 * 3 + 0] * U[0 * 3 + 1] - U[0 * 3 + 0] * U[2 * 3 + 1];
    U[2 * 3 + 2] = U[0 * 3 + 0] * U[1 * 3 + 1] - U[1 * 3 + 0] * U[0 * 3 + 1];
  }
}

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

bool decomposeE(const double* E, double* R1out, double* R2out, double* tout) {
  double U[9], s[3], V[9];
  svd3(E, U, s, V);
  double Wm[9] = {0, -1, 0, 1, 0, 0, 0, 0, 1};
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
  mul(U, Wm, UW);
  double WT[9] = {0, 1, 0, -1, 0, 0, 0, 0, 1};
  mul(U, WT, UWT);
  mul(UW, Vt, R1);
  mul(UWT, Vt, R2);
  auto det3 = [](const double* M) {
    return M[0] * (M[4] * M[8] - M[5] * M[7]) - M[1] * (M[3] * M[8] - M[5] * M[6]) + M[2] * (M[3] * M[7] - M[4] * M[6]);
  };
  if (det3(R1) < 0) for (int i = 0; i < 9; i++) R1[i] = -R1[i];
  if (det3(R2) < 0) for (int i = 0; i < 9; i++) R2[i] = -R2[i];
  memcpy(R1out, R1, 9 * sizeof(double));
  memcpy(R2out, R2, 9 * sizeof(double));
  return true;
}

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
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawmechatronics_easyscanner_SpTflite_create(JNIEnv* env, jclass, jstring libDirJ, jstring modelPathJ, jdouble fxJ, jdouble fyJ, jdouble cxJ, jdouble cyJ) {
  const char* libDir = env->GetStringUTFChars(libDirJ, nullptr);
  const char* modelPath = env->GetStringUTFChars(modelPathJ, nullptr);
  auto t0 = std::chrono::steady_clock::now();
  auto* e = new Engine();
  e->fx = fxJ; e->fy = fyJ; e->cx = cxJ; e->cy = cyJ;
  e->img_buf.assign(WH, 0.f);
  e->sc_buf.assign(WH, 0.f);
  e->hm_buf.assign(HM_SIZE, 0.f);
  e->de_buf.assign(DESC_SIZE, 0.f);

  e->model = TfLiteModelCreateFromFile(modelPath);
  if (!e->model) {
    LOGW("XFeat model create failed: %s", modelPath);
    env->ReleaseStringUTFChars(libDirJ, libDir);
    env->ReleaseStringUTFChars(modelPathJ, modelPath);
    delete e;
    return -1;
  }

  std::string modelStr(modelPath);
  auto slash = modelStr.find_last_of('/');
  std::string cacheDir = (slash != std::string::npos ? modelStr.substr(0, slash) : ".") + "/qnn-cache";
  mkdir(cacheDir.c_str(), 0755);
  std::string baseName = (slash != std::string::npos ? modelStr.substr(slash + 1) : modelStr);
  std::string skelDir(libDir);

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
      e->backendName = label;
      double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
      LOG("XFeat %s delegate ready in %.0f ms", label, ms);
      return true;
    }
    if (interp) TfLiteInterpreterDelete(interp);
    TfLiteQnnDelegateDelete(del);
    return false;
  };

  bool ok = false;
  // Tier 1: QNN Hexagon HTP FP16 (Unsigned PD — SD 8 Gen 1/2/3/Elite/Elite Gen 2: V69/V73/V75/V79/V81).
  {
    auto qopt = TfLiteQnnDelegateOptionsDefault();
    qopt.backend_type = kHtpBackend;
    std::string backendLib = skelDir + "/libQnnHtp.so";
    std::string token = "xfeat_" + baseName + "_v2_opt";
    qopt.library_path = backendLib.c_str();
    qopt.skel_library_dir = skelDir.c_str();
    qopt.cache_dir = cacheDir.c_str();
    qopt.model_token = token.c_str();
    qopt.htp_options.performance_mode = kHtpBurst;
    qopt.htp_options.precision = kHtpFp16;
    qopt.htp_options.pd_session = kHtpUnsignedPd;
    qopt.htp_options.optimization_strategy = kHtpOptimizeForInference;
    qopt.log_level = kLogLevelWarn;
    ok = tryDelegate("QNN-HTP", qopt);
  }
  // Tier 2: QNN Hexagon HTP FP16 (Signed PD fallback for OEM ROMs that restrict Unsigned PD).
  if (!ok) {
    auto qopt = TfLiteQnnDelegateOptionsDefault();
    qopt.backend_type = kHtpBackend;
    std::string backendLib = skelDir + "/libQnnHtp.so";
    std::string token = "xfeat_" + baseName + "_v2_signed";
    qopt.library_path = backendLib.c_str();
    qopt.skel_library_dir = skelDir.c_str();
    qopt.cache_dir = cacheDir.c_str();
    qopt.model_token = token.c_str();
    qopt.htp_options.performance_mode = kHtpBurst;
    qopt.htp_options.precision = kHtpFp16;
    qopt.htp_options.pd_session = kHtpSignedPd;
    qopt.htp_options.optimization_strategy = kHtpOptimizeForInference;
    qopt.log_level = kLogLevelWarn;
    ok = tryDelegate("QNN-HTP-Signed", qopt);
  }
  // Tier 3: QNN Adreno GPU FP16 (SD 888 / 865 / 870 / 7xx / 6xx without FP16 HTP).
  if (!ok) {
    auto qopt = TfLiteQnnDelegateOptionsDefault();
    qopt.backend_type = kGpuBackend;
    std::string backendLib = skelDir + "/libQnnGpu.so";
    std::string gpuCache = cacheDir + "/gpu";
    mkdir(gpuCache.c_str(), 0755);
    qopt.library_path = backendLib.c_str();
    qopt.gpu_options.precision = kGpuFp16;
    qopt.gpu_options.performance_mode = kGpuHigh;
    qopt.gpu_options.kernel_repo_dir = gpuCache.c_str();
    qopt.log_level = kLogLevelWarn;
    ok = tryDelegate("QNN-GPU", qopt);
  }
  // Tier 4: TFLite XNNPACK CPU fallback (all non-Qualcomm ARM64 devices).
  if (!ok) {
    LOGW("XFeat QNN HTP/GPU unavailable, retrying on CPU");
    auto* cpuOpts = TfLiteInterpreterOptionsCreate();
    TfLiteInterpreterOptionsSetNumThreads(cpuOpts, 4);
    e->interp = TfLiteInterpreterCreate(e->model, cpuOpts);
    TfLiteInterpreterOptionsDelete(cpuOpts);
    if (!e->interp) {
      LOGW("XFeat CPU interpreter create failed");
      TfLiteModelDelete(e->model);
      env->ReleaseStringUTFChars(libDirJ, libDir);
      env->ReleaseStringUTFChars(modelPathJ, modelPath);
      delete e;
      return -3;
    }
    if (TfLiteInterpreterAllocateTensors(e->interp) != kTfLiteOk) {
      LOGW("XFeat CPU allocate tensors failed");
      TfLiteInterpreterDelete(e->interp);
      TfLiteModelDelete(e->model);
      env->ReleaseStringUTFChars(libDirJ, libDir);
      env->ReleaseStringUTFChars(modelPathJ, modelPath);
      delete e;
      return -4;
    }
    e->backendName = "CPU";
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    LOG("XFeat CPU fallback ready in %.0f ms", ms);
  }

  int nIn = TfLiteInterpreterGetInputTensorCount(e->interp);
  int nOut = TfLiteInterpreterGetOutputTensorCount(e->interp);
  if (nIn >= 1) {
    e->in_img = const_cast<TfLiteTensor*>(TfLiteInterpreterGetInputTensor(e->interp, 0));
  }
  for (int i = 0; i < nOut; i++) {
    auto* t = const_cast<TfLiteTensor*>(TfLiteInterpreterGetOutputTensor(e->interp, i));
    if (!t) continue;
    int nd = TfLiteTensorNumDims(t);
    LOG("XFeat output %d name=%s nd=%d bytes=%zu", i, tensorName(t).c_str(), nd, TfLiteTensorByteSize(t));
    if (nd == 3 && TfLiteTensorDim(t, 1) == H && TfLiteTensorDim(t, 2) == W) {
      e->out_scores = t;
    } else if (nd == 3 && TfLiteTensorDim(t, 1) == DH && TfLiteTensorDim(t, 2) == DW) {
      e->out_hm = t;
    } else if (nd == 4 && TfLiteTensorDim(t, 1) == DH && TfLiteTensorDim(t, 2) == DW && TfLiteTensorDim(t, 3) == DESC_DIM) {
      e->out_desc = t;
    }
  }

  env->ReleaseStringUTFChars(libDirJ, libDir);
  env->ReleaseStringUTFChars(modelPathJ, modelPath);

  if (!e->in_img || !e->out_scores || !e->out_hm || !e->out_desc ||
      TfLiteTensorByteSize(e->in_img) != (size_t)WH * sizeof(float)) {
    LOGW("XFeat tensor contract mismatch: in=%p sc=%p hm=%p de=%p inBytes=%zu",
         e->in_img, e->out_scores, e->out_hm, e->out_desc,
         e->in_img ? TfLiteTensorByteSize(e->in_img) : (size_t)0);
    TfLiteInterpreterDelete(e->interp);
    if (e->qnn) TfLiteQnnDelegateDelete(e->qnn);
    TfLiteModelDelete(e->model);
    delete e;
    return -4;
  }
  LOG("XFeat ready (backend=%s, 640x480x1 -> scores+hm+desc64)", e->backendName.c_str());
  return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_rawmechatronics_easyscanner_SpTflite_run(JNIEnv* env, jclass, jlong handle, jbyteArray grayJ) {
  { intptr_t hi = reinterpret_cast<intptr_t>(handle); if (hi >= -4 && hi <= 0) return nullptr; }
  auto* e = reinterpret_cast<Engine*>(handle);
  if (!e || !e->interp) return nullptr;
  if (!fillGrayInput(env, grayJ, e->img_buf)) return nullptr;

  double inferMs = 0.0;
  std::vector<Kp> kpB;
  std::vector<float> dB;
  if (!runXFeat(e, g_feat_cap, kpB, dB, &inferMs)) return nullptr;

  if (!e->have_prev) {
    e->prev_kp = kpB;
    e->prev_desc = dB;
    e->have_prev = true;
  }
  const auto& kpA = e->prev_kp;
  const auto& dA = e->prev_desc;

  auto m0 = std::chrono::steady_clock::now();
  struct Match { int a, b; float sim; };
  std::vector<Match> matches;
  std::vector<int> aBest(kpA.size(), -1);
  std::vector<float> aBestSim(kpA.size(), -2.f);
  for (size_t bi = 0; bi < kpB.size(); bi++) {
    float best = -2.f, second = -2.f; int bestA = -1;
    const float* bp = dB.data() + bi * DESC_DIM;
    for (size_t ai = 0; ai < kpA.size(); ai++) {
      float sim = 0.f;
      const float* ap = dA.data() + ai * DESC_DIM;
      for (int c = 0; c < DESC_DIM; c++) sim += bp[c] * ap[c];
      if (sim > best) { second = best; best = sim; bestA = (int)ai; }
      else if (sim > second) second = sim;
    }
    if (bestA >= 0 && best > 0.72f && (1.f - best) < 0.80f * (1.f - second)) {
      matches.push_back({bestA, (int)bi, best});
      if (best > aBestSim[bestA]) { aBestSim[bestA] = best; aBest[bestA] = (int)bi; }
    }
  }
  std::vector<Match> mutual;
  for (auto& m : matches) if (aBest[m.a] == m.b) mutual.push_back(m);
  std::sort(mutual.begin(), mutual.end(), [](const Match& x, const Match& y) { return x.sim > y.sim; });
  if ((int)mutual.size() > g_feat_cap) mutual.resize(g_feat_cap);

  std::vector<double> na, nb;
  for (auto& m : mutual) {
    na.push_back((kpA[m.a].x - e->cx) / e->fx); na.push_back((kpA[m.a].y - e->cy) / e->fy);
    nb.push_back((kpB[m.b].x - e->cx) / e->fx); nb.push_back((kpB[m.b].y - e->cy) / e->fy);
  }

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
        double err = x2 * Ex + y2 * Ey + Ez;
        double den = Ex * Ex + Ey * Ey + Etx * Etx + Ety * Ety;
        if (den < 1e-18) continue;
        if ((err * err) / den < thresh * thresh) { cnt++; mask[i] = 1; }
      }
      if (cnt > inliers) { inliers = cnt; bestMask = mask; }
    }
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

  e->prev_kp = std::move(kpB);
  e->prev_desc = std::move(dB);

  jfloatArray arr = env->NewFloatArray((jsize)out.size());
  if (!arr) return nullptr;
  env->SetFloatArrayRegion(arr, 0, (jsize)out.size(), out.data());
  return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_SpTflite_setFeatureCap(JNIEnv*, jclass, jint cap) {
  g_feat_cap = cap < 20 ? 20 : (cap > 2000 ? 2000 : cap);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_rawmechatronics_easyscanner_SpTflite_features(JNIEnv* env, jclass, jlong handle, jbyteArray grayJ) {
  { intptr_t hi = reinterpret_cast<intptr_t>(handle); if (hi >= -4 && hi <= 0) return nullptr; }
  auto* e = reinterpret_cast<Engine*>(handle);
  if (!e || !e->interp) return nullptr;
  if (!fillGrayInput(env, grayJ, e->img_buf)) return nullptr;

  std::vector<Kp> kps;
  std::vector<float> desc;
  if (!runXFeat(e, g_feat_cap, kps, desc, nullptr)) return nullptr;

  std::vector<float> out;
  out.reserve(1 + kps.size() * 2 + desc.size());
  out.push_back((float)kps.size());
  for (const auto& k : kps) { out.push_back(k.x); out.push_back(k.y); }
  out.insert(out.end(), desc.begin(), desc.end());

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
