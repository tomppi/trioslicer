// JNI shim for the Manifold boolean engine (elalish/manifold, Apache-2.0).
//
// This is deliberately small: it loads a triangle soup as a Manifold solid
// (welding the soup's duplicate vertices), builds a box, runs UNION and
// SUBTRACT, reports whether a result really is a closed manifold solid, and
// reads the geometry back out as a triangle soup. Every entry point fails soft:
// a failure is a 0 handle, an empty array or a status string, never a C++
// exception crossing back into Kotlin.
//
// The meshes are held in a registry behind opaque jlong handles so a result can
// be queried (status / closedness / volume / geometry) without re-uploading it,
// and so the caller can release it deterministically.
//
// Build: scripts/build-manifold-android.sh (arm64-v8a, android-29, Manifold
// linked in statically). The Kotlin half is viewer/MeshBoolean.kt, whose JNI
// names this file's symbols must match exactly.
#include <jni.h>

#include <manifold/manifold.h>
#include <manifold/version.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

using namespace manifold;

namespace {

constexpr jlong kNoHandle = 0;

// ------------------------------------------------------------------ registry

// Manifold is not thread-safe, so each entry is a private copy; the lock only
// guards the map, and an operation works on its own copy outside it.
std::mutex& RegistryMutex() {
  static std::mutex* mutex = new std::mutex();
  return *mutex;
}

std::unordered_map<jlong, Manifold>& Registry() {
  static std::unordered_map<jlong, Manifold>* registry = new std::unordered_map<jlong, Manifold>();
  return *registry;
}

std::atomic<jlong>& NextHandle() {
  static std::atomic<jlong>* next = new std::atomic<jlong>(1);
  return *next;
}

jlong Store(const Manifold& solid) {
  const jlong handle = NextHandle().fetch_add(1);
  std::lock_guard<std::mutex> lock(RegistryMutex());
  Registry()[handle] = solid;
  return handle;
}

bool Lookup(jlong handle, Manifold* out) {
  if (handle == kNoHandle) return false;
  std::lock_guard<std::mutex> lock(RegistryMutex());
  const auto found = Registry().find(handle);
  if (found == Registry().end()) return false;
  *out = found->second;
  return true;
}

void Forget(jlong handle) {
  if (handle == kNoHandle) return;
  std::lock_guard<std::mutex> lock(RegistryMutex());
  Registry().erase(handle);
}

// ------------------------------------------------------------------- reports

const char* ErrName(Manifold::Error error) {
  switch (error) {
    case Manifold::Error::NoError: return "NoError";
    case Manifold::Error::NonFiniteVertex: return "NonFiniteVertex";
    case Manifold::Error::NotManifold: return "NotManifold";
    case Manifold::Error::VertexOutOfBounds: return "VertexOutOfBounds";
    case Manifold::Error::PropertiesWrongLength: return "PropertiesWrongLength";
    case Manifold::Error::MissingPositionProperties: return "MissingPositionProperties";
    case Manifold::Error::MergeVectorsDifferentLengths: return "MergeVectorsDifferentLengths";
    case Manifold::Error::MergeIndexOutOfBounds: return "MergeIndexOutOfBounds";
    case Manifold::Error::TransformWrongLength: return "TransformWrongLength";
    case Manifold::Error::RunIndexWrongLength: return "RunIndexWrongLength";
    case Manifold::Error::FaceIDWrongLength: return "FaceIDWrongLength";
    case Manifold::Error::InvalidConstruction: return "InvalidConstruction";
    case Manifold::Error::ResultTooLarge: return "ResultTooLarge";
    case Manifold::Error::InvalidTangents: return "InvalidTangents";
    case Manifold::Error::Cancelled: return "Cancelled";
  }
  return "Unknown";
}

// A watertight triangle mesh has every undirected edge shared by exactly two
// triangles. This is checked independently of Manifold's own status, so
// "closed" is not Manifold agreeing with itself.
bool IsEdgeClosed(const MeshGL& input) {
  MeshGL mesh = input;
  // Merge() applies the property merge vectors, which triVerts still index
  // through; without that the edge pairs do not line up.
  mesh.Merge();
  const size_t triangles = mesh.NumTri();
  if (triangles == 0) return false;
  std::vector<uint64_t> edges;
  edges.reserve(triangles * 3);
  size_t degenerate = 0;
  for (size_t triangle = 0; triangle < triangles; ++triangle) {
    const uint32_t corner[3] = {
        mesh.triVerts[triangle * 3],
        mesh.triVerts[triangle * 3 + 1],
        mesh.triVerts[triangle * 3 + 2],
    };
    for (int k = 0; k < 3; ++k) {
      const uint32_t from = corner[k];
      const uint32_t to = corner[(k + 1) % 3];
      if (from == to) {
        ++degenerate;
        continue;
      }
      const uint32_t low = std::min(from, to);
      const uint32_t high = std::max(from, to);
      edges.push_back((static_cast<uint64_t>(low) << 32) | high);
    }
  }
  std::sort(edges.begin(), edges.end());
  size_t bad = 0;
  for (size_t index = 0; index < edges.size();) {
    size_t run = index;
    while (run < edges.size() && edges[run] == edges[index]) ++run;
    if (run - index != 2) ++bad;
    index = run;
  }
  return bad == 0 && degenerate == 0;
}

// GetMeshGL() is only meaningful for a valid solid; an invalid one keeps the
// caller on the failure path instead of handing it garbage geometry.
MeshGL SafeMesh(const Manifold& solid) {
  if (solid.Status() != Manifold::Error::NoError) return MeshGL();
  return solid.GetMeshGL();
}

std::vector<float> MeshSoup(const MeshGL& mesh) {
  std::vector<float> soup;
  if (mesh.numProp < 3) return soup;
  const size_t triangles = mesh.NumTri();
  soup.resize(triangles * 9);
  for (size_t triangle = 0; triangle < triangles; ++triangle) {
    for (int corner = 0; corner < 3; ++corner) {
      const uint32_t vertex = mesh.triVerts[triangle * 3 + corner];
      const size_t source = static_cast<size_t>(vertex) * mesh.numProp;
      float* target = &soup[triangle * 9 + corner * 3];
      target[0] = mesh.vertProperties[source];
      target[1] = mesh.vertProperties[source + 1];
      target[2] = mesh.vertProperties[source + 2];
    }
  }
  return soup;
}

// A pending JNI exception (an allocation failure, say) must not escape: the
// shim's contract is that the Kotlin side gets null or an empty array.
bool AllocationFailed(JNIEnv* env) {
  if (env->ExceptionCheck() == JNI_FALSE) return false;
  env->ExceptionClear();
  return true;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_tomppi_enderslicer_viewer_MeshBoolean_nativeVersion(JNIEnv* env, jobject /* thiz */) {
  try {
    const std::string version = std::to_string(MANIFOLD_VERSION_MAJOR) + "." +
                                std::to_string(MANIFOLD_VERSION_MINOR) + "." +
                                std::to_string(MANIFOLD_VERSION_PATCH);
    jstring text = env->NewStringUTF(version.c_str());
    if (AllocationFailed(env)) return nullptr;
    return text;
  } catch (...) {
    return nullptr;
  }
}

// Loads a mesh in the viewer's own layout - six floats per vertex, position
// then normal, eighteen per triangle, CCW from outside - and welds it, which is
// what a triangle soup with no shared vertices needs before it is a solid. The
// normals are not read: Manifold carries positions and derives orientation from
// the winding.
JNIEXPORT jlong JNICALL
Java_com_tomppi_enderslicer_viewer_MeshBoolean_nativeLoadMesh(JNIEnv* env, jobject /* thiz */,
                                                              jfloatArray interleaved,
                                                              jint triangleCount) {
  try {
    if (interleaved == nullptr || triangleCount <= 0) return kNoHandle;
    const jsize length = env->GetArrayLength(interleaved);
    if (length != static_cast<jsize>(triangleCount) * 18) return kNoHandle;
    std::vector<float> packed(static_cast<size_t>(length));
    env->GetFloatArrayRegion(interleaved, 0, length, packed.data());
    if (AllocationFailed(env)) return kNoHandle;

    std::vector<float> positions(static_cast<size_t>(triangleCount) * 9);
    for (jsize vertex = 0; vertex < static_cast<jsize>(triangleCount) * 3; ++vertex) {
      const float* source = &packed[static_cast<size_t>(vertex) * 6];
      float* target = &positions[static_cast<size_t>(vertex) * 3];
      target[0] = source[0];
      target[1] = source[1];
      target[2] = source[2];
    }

    MeshGL mesh;
    mesh.numProp = 3;
    mesh.vertProperties = std::move(positions);
    mesh.triVerts.resize(static_cast<size_t>(triangleCount) * 3);
    for (size_t index = 0; index < mesh.triVerts.size(); ++index) {
      mesh.triVerts[index] = static_cast<uint32_t>(index);
    }
    mesh.Merge();
    return Store(Manifold(mesh));
  } catch (...) {
    return kNoHandle;
  }
}

JNIEXPORT jlong JNICALL
Java_com_tomppi_enderslicer_viewer_MeshBoolean_nativeBox(JNIEnv* env, jobject /* thiz */,
                                                         jfloatArray low, jfloatArray high) {
  try {
    if (low == nullptr || high == nullptr) return kNoHandle;
    if (env->GetArrayLength(low) < 3 || env->GetArrayLength(high) < 3) return kNoHandle;
    float lowValues[3] = {0, 0, 0};
    float highValues[3] = {0, 0, 0};
    env->GetFloatArrayRegion(low, 0, 3, lowValues);
    env->GetFloatArrayRegion(high, 0, 3, highValues);
    if (AllocationFailed(env)) return kNoHandle;
    const vec3 size(highValues[0] - lowValues[0], highValues[1] - lowValues[1],
                    highValues[2] - lowValues[2]);
    if (size.x <= 0 || size.y <= 0 || size.z <= 0) return kNoHandle;
    return Store(Manifold::Cube(size, false).Translate(vec3(lowValues[0], lowValues[1], lowValues[2])));
  } catch (...) {
    return kNoHandle;
  }
}

JNIEXPORT jlong JNICALL
Java_com_tomppi_enderslicer_viewer_MeshBoolean_nativeUnion(JNIEnv* /* env */, jobject /* thiz */,
                                                           jlong first, jlong second) {
  try {
    Manifold a, b;
    if (!Lookup(first, &a) || !Lookup(second, &b)) return kNoHandle;
    // Manifold defers booleans: the tree is built here and evaluated by the
    // Status() below, so the handle always carries an evaluated result.
    Manifold result = a + b;
    result.Status();
    return Store(result);
  } catch (...) {
    return kNoHandle;
  }
}

JNIEXPORT jlong JNICALL
Java_com_tomppi_enderslicer_viewer_MeshBoolean_nativeSubtract(JNIEnv* /* env */, jobject /* thiz */,
                                                              jlong first, jlong second) {
  try {
    Manifold a, b;
    if (!Lookup(first, &a) || !Lookup(second, &b)) return kNoHandle;
    Manifold result = a - b;
    result.Status();
    return Store(result);
  } catch (...) {
    return kNoHandle;
  }
}

JNIEXPORT jstring JNICALL
Java_com_tomppi_enderslicer_viewer_MeshBoolean_nativeStatus(JNIEnv* env, jobject /* thiz */,
                                                            jlong handle) {
  try {
    Manifold solid;
    if (!Lookup(handle, &solid)) return nullptr;
    jstring text = env->NewStringUTF(ErrName(solid.Status()));
    if (AllocationFailed(env)) return nullptr;
    return text;
  } catch (...) {
    return nullptr;
  }
}

JNIEXPORT jboolean JNICALL
Java_com_tomppi_enderslicer_viewer_MeshBoolean_nativeIsClosed(JNIEnv* /* env */, jobject /* thiz */,
                                                              jlong handle) {
  try {
    Manifold solid;
    if (!Lookup(handle, &solid)) return JNI_FALSE;
    if (solid.Status() != Manifold::Error::NoError) return JNI_FALSE;
    return IsEdgeClosed(solid.GetMeshGL()) ? JNI_TRUE : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

JNIEXPORT jfloatArray JNICALL
Java_com_tomppi_enderslicer_viewer_MeshBoolean_nativeReadMesh(JNIEnv* env, jobject /* thiz */,
                                                              jlong handle) {
  try {
    Manifold solid;
    if (!Lookup(handle, &solid)) return nullptr;
    const std::vector<float> soup = MeshSoup(SafeMesh(solid));
    if (soup.empty()) return nullptr;
    jfloatArray array = env->NewFloatArray(static_cast<jsize>(soup.size()));
    if (AllocationFailed(env) || array == nullptr) return nullptr;
    env->SetFloatArrayRegion(array, 0, static_cast<jsize>(soup.size()), soup.data());
    if (AllocationFailed(env)) return nullptr;
    return array;
  } catch (...) {
    return nullptr;
  }
}

JNIEXPORT jdouble JNICALL
Java_com_tomppi_enderslicer_viewer_MeshBoolean_nativeVolume(JNIEnv* /* env */, jobject /* thiz */,
                                                            jlong handle) {
  try {
    Manifold solid;
    if (!Lookup(handle, &solid)) return 0.0;
    if (solid.Status() != Manifold::Error::NoError) return 0.0;
    return static_cast<jdouble>(solid.Volume());
  } catch (...) {
    return 0.0;
  }
}

JNIEXPORT jint JNICALL
Java_com_tomppi_enderslicer_viewer_MeshBoolean_nativeLiveHandles(JNIEnv* /* env */,
                                                                 jobject /* thiz */) {
  try {
    std::lock_guard<std::mutex> lock(RegistryMutex());
    return static_cast<jint>(Registry().size());
  } catch (...) {
    return -1;
  }
}

JNIEXPORT void JNICALL
Java_com_tomppi_enderslicer_viewer_MeshBoolean_nativeRelease(JNIEnv* /* env */, jobject /* thiz */,
                                                             jlong handle) {
  try {
    Forget(handle);
  } catch (...) {
    // Releasing must never fail loudly.
  }
}

}  // extern "C"
