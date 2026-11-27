#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>
#include <vector>

#define LOG_TAG "LoopDb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int DESC_DIM = 64;
constexpr int MAX_INDEX_DESC = 128;     // Top-128 highest-reliability XFeat descriptors per keyframe
constexpr float MNN_COS_THRESH = 0.72f; // XFeat unit-norm cosine threshold for local correspondence

struct KeyframeEntry {
  int entryId = 0;
  int n = 0;
  std::vector<float> desc;  // n * DESC_DIM, L2-normalized
};

std::mutex g_mtx;
bool g_ready = false;
std::vector<KeyframeEntry> g_entries;

// Score visual overlap between two sets of L2-normalized 64-D XFeat descriptors via
// mutual-nearest-neighbor cosine similarity (zero vocabulary training bias, works on any 3D object).
float scoreKeyframePair(const float* q, int nq, const float* d, int nd) {
  if (nq <= 0 || nd <= 0) return 0.0f;
  // Cap to top-MAX_INDEX_DESC features for O(1) sub-millisecond scoring per keyframe.
  const int Q = std::min(nq, MAX_INDEX_DESC);
  const int D = std::min(nd, MAX_INDEX_DESC);

  int qBestIdx[MAX_INDEX_DESC];
  float qBestSim[MAX_INDEX_DESC];
  int dBestIdx[MAX_INDEX_DESC];
  float dBestSim[MAX_INDEX_DESC];
  for (int j = 0; j < D; ++j) {
    dBestIdx[j] = -1;
    dBestSim[j] = -2.0f;
  }

  for (int i = 0; i < Q; ++i) {
    const float* qi = q + (size_t)i * DESC_DIM;
    float best = -2.0f;
    int bestJ = -1;
    for (int j = 0; j < D; ++j) {
      const float* dj = d + (size_t)j * DESC_DIM;
      float sim = 0.0f;
      for (int c = 0; c < DESC_DIM; ++c) sim += qi[c] * dj[c];
      if (sim > best) {
        best = sim;
        bestJ = j;
      }
      if (sim > dBestSim[j]) {
        dBestSim[j] = sim;
        dBestIdx[j] = i;
      }
    }
    qBestIdx[i] = bestJ;
    qBestSim[i] = best;
  }

  float scoreSum = 0.0f;
  int mnnCount = 0;
  for (int i = 0; i < Q; ++i) {
    const int j = qBestIdx[i];
    if (j >= 0 && dBestIdx[j] == i && qBestSim[i] >= MNN_COS_THRESH) {
      scoreSum += (qBestSim[i] - 0.65f);
      mnnCount++;
    }
  }
  if (mnnCount < 4) return 0.0f;
  return scoreSum / (float)std::max(1, std::min(Q, D));
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawmechatronics_easyscanner_LoopDb_nativeLoad(JNIEnv *, jclass, jstring) {
  std::lock_guard<std::mutex> lck(g_mtx);
  g_entries.clear();
  g_ready = true;
  LOGI("XFeat 64-D local-descriptor MNN loop index ready");
  return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawmechatronics_easyscanner_LoopDb_nativeAdd(JNIEnv *env, jclass, jint, jfloatArray desc, jint n) {
  std::lock_guard<std::mutex> lck(g_mtx);
  if (!g_ready || !desc || n <= 0) return -1;
  const jsize len = env->GetArrayLength(desc);
  if (len < n * DESC_DIM) return -1;
  const int keep = std::min((int)n, MAX_INDEX_DESC);
  KeyframeEntry e;
  e.entryId = (int)g_entries.size();
  e.n = keep;
  e.desc.resize((size_t)keep * DESC_DIM);
  env->GetFloatArrayRegion(desc, 0, keep * DESC_DIM, e.desc.data());
  if (env->ExceptionCheck()) return -1;
  g_entries.push_back(std::move(e));
  return (jint)g_entries.back().entryId;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawmechatronics_easyscanner_LoopDb_nativeQuery(JNIEnv *env, jclass, jfloatArray desc, jint n,
                                                        jint maxResults, jint maxId,
                                                        jintArray outIds, jfloatArray outScores) {
  std::lock_guard<std::mutex> lck(g_mtx);
  if (!g_ready || g_entries.empty() || !desc || n <= 0 || !outIds || !outScores) return 0;
  const jsize len = env->GetArrayLength(desc);
  if (len < n * DESC_DIM) return 0;
  const int keep = std::min((int)n, MAX_INDEX_DESC);
  std::vector<float> q((size_t)keep * DESC_DIM);
  env->GetFloatArrayRegion(desc, 0, keep * DESC_DIM, q.data());
  if (env->ExceptionCheck()) return 0;

  struct Cand { int id; float score; };
  std::vector<Cand> cands;
  cands.reserve(g_entries.size());
  for (const auto& e : g_entries) {
    if (maxId >= 0 && e.entryId > maxId) continue;
    const float sc = scoreKeyframePair(q.data(), keep, e.desc.data(), e.n);
    if (sc > 0.0f) cands.push_back({e.entryId, sc});
  }
  std::sort(cands.begin(), cands.end(), [](const Cand& a, const Cand& b) { return a.score > b.score; });

  int count = std::min((int)cands.size(), (int)maxResults);
  count = std::min(count, (int)env->GetArrayLength(outIds));
  count = std::min(count, (int)env->GetArrayLength(outScores));
  count = std::min(count, 64);
  if (count <= 0) return 0;

  jint ids[64];
  jfloat scores[64];
  for (int i = 0; i < count; ++i) {
    ids[i] = cands[i].id;
    scores[i] = cands[i].score;
  }
  env->SetIntArrayRegion(outIds, 0, count, ids);
  env->SetFloatArrayRegion(outScores, 0, count, scores);
  return count;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_LoopDb_nativeClear(JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lck(g_mtx);
  g_entries.clear();
}
