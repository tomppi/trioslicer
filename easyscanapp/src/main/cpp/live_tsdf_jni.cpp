#include <GLES3/gl3.h>
#include <android/log.h>
#include <jni.h>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "live_tsdf.h"
#include "live_mvs.h"

using namespace livetsdf;

namespace {
constexpr const char* kTag = "LiveTsdfJNI";

std::mutex g_reg_mtx;
std::unordered_map<jlong, std::shared_ptr<NativeTsdfVolume>> g_vols;
std::unordered_map<jlong, std::shared_ptr<livemvs::LiveMvsEngine>> g_mvs;
jlong g_next_id = 1;

inline std::shared_ptr<NativeTsdfVolume> acquireVol(jlong h) {
    if (h == 0) return nullptr;
    std::lock_guard<std::mutex> lck(g_reg_mtx);
    auto it = g_vols.find(h);
    return it != g_vols.end() ? it->second : nullptr;
}

inline std::shared_ptr<livemvs::LiveMvsEngine> acquireMvs(jlong h) {
    if (h == 0) return nullptr;
    std::lock_guard<std::mutex> lck(g_reg_mtx);
    auto it = g_mvs.find(h);
    return it != g_mvs.end() ? it->second : nullptr;
}

inline Pose readPose(JNIEnv* env, jdoubleArray rArr, jdoubleArray tArr) {
    Pose p;
    env->GetDoubleArrayRegion(rArr, 0, 9, p.r);
    env->GetDoubleArrayRegion(tArr, 0, 3, p.t);
    return p;
}

inline Camera readCamera(JNIEnv* env, jdoubleArray camArr) {
    double c[11]{};
    env->GetDoubleArrayRegion(camArr, 0, 11, c);
    Camera cam;
    cam.fx = c[0]; cam.fy = c[1]; cam.cx = c[2]; cam.cy = c[3];
    cam.width = static_cast<int>(c[4]); cam.height = static_cast<int>(c[5]);
    cam.k1 = c[6]; cam.k2 = c[7]; cam.p1 = c[8]; cam.p2 = c[9]; cam.k3 = c[10];
    cam.initLimits();
    return cam;
}
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeCreate(
    JNIEnv*, jclass, jfloat voxelSizeM, jfloat truncationM, jint maxBlocks, jdouble maxDepthM
) {
    auto vol = std::make_shared<NativeTsdfVolume>(voxelSizeM, truncationM, maxBlocks, maxDepthM);
    std::lock_guard<std::mutex> lck(g_reg_mtx);
    jlong id = g_next_id++;
    g_vols.emplace(id, std::move(vol));
    return id;
}

JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    std::shared_ptr<NativeTsdfVolume> released;
    {
        std::lock_guard<std::mutex> lck(g_reg_mtx);
        auto it = g_vols.find(handle);
        if (it != g_vols.end()) {
            released = std::move(it->second);
            g_vols.erase(it);
        }
    }
    // Destructor runs outside g_reg_mtx once any in-flight GL/NPU call releases its shared_ptr.
}

JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeClear(JNIEnv*, jclass, jlong handle) {
    if (auto v = acquireVol(handle)) {
        std::lock_guard<std::mutex> lck(v->mtx);
        v->clear();
    }
}

JNIEXPORT jfloat JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeEstimateNovelty(
    JNIEnv* env, jclass, jlong handle,
    jdoubleArray poseR, jdoubleArray poseT, jdoubleArray cam11,
    jfloatArray rawDepth, jdouble scale, jdouble offset, jbooleanArray validMask518
) {
    auto vol = acquireVol(handle);
    if (!vol || !rawDepth) return 1.0f;
    Pose pose = readPose(env, poseR, poseT);
    Camera cam = readCamera(env, cam11);
    jfloat* dPtr = env->GetFloatArrayElements(rawDepth, nullptr);
    jboolean* maskPtr = validMask518 ? env->GetBooleanArrayElements(validMask518, nullptr) : nullptr;
    float frac = 1.0f;
    {
        std::lock_guard<std::mutex> lck(vol->mtx);
        frac = vol->estimateNoveltyFraction(
            pose, cam, dPtr, scale, offset,
            reinterpret_cast<const uint8_t*>(maskPtr));
    }
    if (maskPtr) env->ReleaseBooleanArrayElements(validMask518, maskPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(rawDepth, dPtr, JNI_ABORT);
    return frac;
}

JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeIntegrateDepth(
    JNIEnv* env, jclass, jlong handle,
    jdoubleArray poseR, jdoubleArray poseT, jdoubleArray cam11,
    jfloatArray rawDepth, jdouble scale, jdouble offset,
    jbyteArray rgb518, jbooleanArray validMask518, jint stride, jfloat da3Weight
) {
    auto vol = acquireVol(handle);
    if (!vol || !rawDepth || !rgb518) return;
    Pose pose = readPose(env, poseR, poseT);
    Camera cam = readCamera(env, cam11);
    jfloat* dPtr = env->GetFloatArrayElements(rawDepth, nullptr);
    jbyte* rgbPtr = env->GetByteArrayElements(rgb518, nullptr);
    jboolean* maskPtr = validMask518 ? env->GetBooleanArrayElements(validMask518, nullptr) : nullptr;
    {
        std::lock_guard<std::mutex> lck(vol->mtx);
        vol->integrateDepthMap(
            pose, cam, dPtr, scale, offset,
            reinterpret_cast<const uint8_t*>(rgbPtr),
            reinterpret_cast<const uint8_t*>(maskPtr),
            stride, da3Weight);
    }
    if (maskPtr) env->ReleaseBooleanArrayElements(validMask518, maskPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(rgb518, rgbPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(rawDepth, dPtr, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeIntegrateLaser(
    JNIEnv* env, jclass, jlong handle,
    jdoubleArray poseR, jdoubleArray poseT, jfloatArray laserCamM, jfloat laserWeight
) {
    auto vol = acquireVol(handle);
    if (!vol || !laserCamM) return 0;
    jsize len = env->GetArrayLength(laserCamM);
    if (len < 3) return 0;
    Pose pose = readPose(env, poseR, poseT);
    jfloat* ptr = env->GetFloatArrayElements(laserCamM, nullptr);
    int count = 0;
    {
        std::lock_guard<std::mutex> lck(vol->mtx);
        count = vol->integrateLaserFrame(pose, ptr, len / 3, laserWeight);
    }
    env->ReleaseFloatArrayElements(laserCamM, ptr, JNI_ABORT);
    return count;
}

JNIEXPORT jint JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeIntegrateOrientedPoints(
    JNIEnv* env, jclass, jlong handle,
    jfloatArray xyzNxyz, jbyteArray rgb, jint numPoints, jfloat pointWeight
) {
    auto vol = acquireVol(handle);
    if (!vol || !xyzNxyz || numPoints <= 0) return 0;
    jsize flen = env->GetArrayLength(xyzNxyz);
    if (flen < numPoints * 6) return 0;
    jfloat* ptr = env->GetFloatArrayElements(xyzNxyz, nullptr);
    jbyte* rgbPtr = rgb ? env->GetByteArrayElements(rgb, nullptr) : nullptr;
    int count = 0;
    {
        std::lock_guard<std::mutex> lck(vol->mtx);
        count = vol->integrateOrientedPointCloud(
            ptr, reinterpret_cast<const uint8_t*>(rgbPtr), numPoints, pointWeight);
    }
    if (rgbPtr) env->ReleaseByteArrayElements(rgb, rgbPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(xyzNxyz, ptr, JNI_ABORT);
    return count;
}

JNIEXPORT jint JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeWriteMeshStl(
    JNIEnv* env, jclass, jlong handle, jstring path, jboolean toMillimetres
) {
    auto vol = acquireVol(handle);
    if (!vol || !path) return -1;
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (!cpath) return -1;
    std::string spath(cpath);
    env->ReleaseStringUTFChars(path, cpath);
    std::lock_guard<std::mutex> lck(vol->mtx);
    return vol->writeMeshStl(spath, toMillimetres == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeWriteMeshStlDualContouring(
    JNIEnv* env, jclass, jlong handle, jstring path, jboolean toMillimetres
) {
    auto vol = acquireVol(handle);
    if (!vol || !path) return -1;
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (!cpath) return -1;
    std::string spath(cpath);
    env->ReleaseStringUTFChars(path, cpath);
    std::lock_guard<std::mutex> lck(vol->mtx);
    return vol->writeMeshStlDualContouring(spath, toMillimetres == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeWriteMeshPlyDualContouring(
    JNIEnv* env, jclass, jlong handle, jstring path, jboolean toMillimetres
) {
    auto vol = acquireVol(handle);
    if (!vol || !path) return -1;
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (!cpath) return -1;
    std::string spath(cpath);
    env->ReleaseStringUTFChars(path, cpath);
    std::lock_guard<std::mutex> lck(vol->mtx);
    return vol->writeMeshPlyDualContouring(spath, toMillimetres == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeUploadToGpu(
    JNIEnv* env, jclass, jlong handle, jint vboId, jint iboId,
    jintArray outStats6, jfloatArray outBounds6
) {
    auto vol = acquireVol(handle);
    if (!vol || vboId <= 0 || iboId <= 0) return JNI_FALSE;
    std::lock_guard<std::mutex> lck(vol->mtx);
    const ExtractedMesh& m = vol->extractMesh();
    const GLsizeiptr vBytes = static_cast<GLsizeiptr>(m.verts.size() * sizeof(GpuVertex));
    const GLsizeiptr iBytes = static_cast<GLsizeiptr>(m.indices.size() * sizeof(uint32_t));
    if (vBytes > 0 && iBytes > 0) {
        glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(vboId));
        glBufferData(GL_ARRAY_BUFFER, vBytes, m.verts.data(), GL_DYNAMIC_DRAW);
        GLenum err1 = glGetError();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, static_cast<GLuint>(iboId));
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, iBytes, m.indices.data(), GL_DYNAMIC_DRAW);
        GLenum err2 = glGetError();
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        if (err1 == GL_OUT_OF_MEMORY || err2 == GL_OUT_OF_MEMORY) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "GPU OOM uploading %ld + %ld bytes", (long)vBytes, (long)iBytes);
            return JNI_FALSE;
        }
    }
    if (outStats6) {
        jint st[6] = {
            static_cast<jint>(m.indices.size()),
            static_cast<jint>(m.verts.size()),
            m.activeBlocks,
            m.laserBlocks,
            m.laserCells,
            m.totalLaserHits,
        };
        env->SetIntArrayRegion(outStats6, 0, 6, st);
    }
    if (outBounds6) {
        jfloat bd[6] = {
            m.boundsMin[0], m.boundsMin[1], m.boundsMin[2],
            m.boundsMax[0], m.boundsMax[1], m.boundsMax[2],
        };
        env->SetFloatArrayRegion(outBounds6, 0, 6, bd);
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativePrepareMesh(JNIEnv*, jclass, jlong handle) {
    auto vol = acquireVol(handle);
    if (!vol) return 0;
    std::lock_guard<std::mutex> lck(vol->mtx);
    return static_cast<jint>(vol->extractMesh().indices.size() / 3);
}

JNIEXPORT jint JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeSampleSurface(
    JNIEnv* env, jclass, jlong handle, jint maxPoints, jfloatArray outPos, jfloatArray outNorm
) {
    auto vol = acquireVol(handle);
    if (!vol || !outPos || !outNorm || maxPoints <= 0) return 0;
    int cap = std::min(static_cast<int>(maxPoints),
                       std::min(env->GetArrayLength(outPos) / 3, env->GetArrayLength(outNorm) / 3));
    if (cap <= 0) return 0;
    jfloat* pPtr = env->GetFloatArrayElements(outPos, nullptr);
    jfloat* nPtr = env->GetFloatArrayElements(outNorm, nullptr);
    int written = 0;
    {
        std::lock_guard<std::mutex> lck(vol->mtx);
        written = vol->sampleSurface(cap, pPtr, nPtr);
    }
    env->ReleaseFloatArrayElements(outPos, pPtr, 0);
    env->ReleaseFloatArrayElements(outNorm, nPtr, 0);
    return written;
}

JNIEXPORT jint JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeWriteMeshPly(
    JNIEnv* env, jclass, jlong handle, jstring pathStr, jboolean toMillimetres
) {
    auto vol = acquireVol(handle);
    if (!vol || !pathStr) return -1;
    const char* cpath = env->GetStringUTFChars(pathStr, nullptr);
    std::string path(cpath ? cpath : "");
    env->ReleaseStringUTFChars(pathStr, cpath);
    std::lock_guard<std::mutex> lck(vol->mtx);
    return vol->writeMeshPly(path, toMillimetres == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeGetStats(
    JNIEnv* env, jclass, jlong handle, jintArray outStats5
) {
    auto vol = acquireVol(handle);
    if (!vol || !outStats5) return;
    VolumeStats s;
    {
        std::lock_guard<std::mutex> lck(vol->mtx);
        s = vol->stats();
    }
    jint out[5] = { s.activeBlocks, s.laserBlocks, s.totalLaserHits, s.integratedPatches, s.lastDirtyRebuilt };
    env->SetIntArrayRegion(outStats5, 0, 5, out);
}

JNIEXPORT jfloat JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeRaycastCenterDepthM(
    JNIEnv* env, jclass, jlong handle, jdoubleArray poseR, jdoubleArray poseT
) {
    auto vol = acquireVol(handle);
    if (!vol) return -1.0f;
    Pose pose = readPose(env, poseR, poseT);
    std::lock_guard<std::mutex> lck(vol->mtx);
    return vol->raycastCenterDepthM(pose);
}

// ---------------- Coarse Plane-Sweep Multi-View Stereo (LiveMvsEngine) ----------------

JNIEXPORT jlong JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeMvsCreate(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lck(g_reg_mtx);
    jlong id = g_next_id++;
    g_mvs[id] = std::make_shared<livemvs::LiveMvsEngine>();
    return id;
}

JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeMvsDestroy(JNIEnv*, jclass, jlong mvsHandle) {
    std::lock_guard<std::mutex> lck(g_reg_mtx);
    g_mvs.erase(mvsHandle);
}

JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeMvsReset(JNIEnv*, jclass, jlong mvsHandle) {
    auto eng = acquireMvs(mvsHandle);
    if (eng) eng->reset();
}

JNIEXPORT jboolean JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeMvsShouldAccept(
    JNIEnv* env, jclass, jlong mvsHandle,
    jdoubleArray poseR, jdoubleArray poseT, jdouble minTransM, jdouble minRotDeg
) {
    auto eng = acquireMvs(mvsHandle);
    if (!eng) return JNI_FALSE;
    Pose pose = readPose(env, poseR, poseT);
    return eng->shouldAcceptKeyframe(pose, minTransM, minRotDeg) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeMvsDownscaleYuv(
    JNIEnv* env, jclass,
    jobject yBuf, jint yStride,
    jobject uBuf, jobject vBuf, jint uvStride, jint uvPixStride,
    jint srcW, jint srcH,
    jbyteArray outGray, jbyteArray outRgb
) {
    if (!yBuf || !uBuf || !vBuf || !outGray || !outRgb) return;
    auto* yPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuf));
    auto* uPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuf));
    auto* vPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!yPtr || !uPtr || !vPtr) return;
    jbyte* gPtr = env->GetByteArrayElements(outGray, nullptr);
    jbyte* rgbPtr = env->GetByteArrayElements(outRgb, nullptr);
    livemvs::LiveMvsEngine::downscaleYuvTo160x120(
        yPtr, yStride, uPtr, vPtr, uvStride, uvPixStride, srcW, srcH,
        reinterpret_cast<uint8_t*>(gPtr), reinterpret_cast<uint8_t*>(rgbPtr));
    env->ReleaseByteArrayElements(outGray, gPtr, 0);
    env->ReleaseByteArrayElements(outRgb, rgbPtr, 0);
}

JNIEXPORT void JNICALL
Java_com_rawmechatronics_easyscanner_NativeLiveTsdf_nativeMvsPushAndFuse(
    JNIEnv* env, jclass,
    jlong mvsHandle, jlong tsdfHandle,
    jbyteArray gray160x120, jbyteArray rgb160x120,
    jdoubleArray poseR, jdoubleArray poseT, jdoubleArray cam11,
    jdouble focusDepthM, jintArray outStats4
) {
    auto eng = acquireMvs(mvsHandle);
    auto vol = acquireVol(tsdfHandle);
    if (!eng || !gray160x120 || !rgb160x120) return;
    Pose pose = readPose(env, poseR, poseT);
    Camera cam = readCamera(env, cam11);
    jbyte* gPtr = env->GetByteArrayElements(gray160x120, nullptr);
    jbyte* rgbPtr = env->GetByteArrayElements(rgb160x120, nullptr);

    auto t0 = std::chrono::steady_clock::now();
    // Plane-sweep runs completely outside vol->mtx; pushKeyframeAndFuse locks vol->mtx only during TSDF fusion.
    livemvs::MvsStats st = eng->pushKeyframeAndFuse(
        reinterpret_cast<const uint8_t*>(gPtr),
        reinterpret_cast<const uint8_t*>(rgbPtr),
        pose, cam, focusDepthM, vol ? vol.get() : nullptr);
    auto t1 = std::chrono::steady_clock::now();
    st.elapsedMs = static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count());

    env->ReleaseByteArrayElements(gray160x120, gPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(rgb160x120, rgbPtr, JNI_ABORT);

    if (outStats4) {
        jint out[4] = { st.keyframeCount, st.validPixels, st.elapsedMs, st.usedNeighbors };
        env->SetIntArrayRegion(outStats4, 0, 4, out);
    }
}

} // extern "C"
