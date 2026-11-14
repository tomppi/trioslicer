// Parallax: JNI bridge for io.github.foadsf.parallax.NativeCore.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <algorithm>
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <opencv2/core/utility.hpp>

#include <string>

#include "core.hpp"
#include "live.hpp"
#include "probe.hpp"

#define LOG_TAG "Parallax"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using namespace parallax;

namespace {

Tracker gTracker;

bool frameFrom(JNIEnv* env, jobject y, jobject u, jobject v, jint w, jint h, jint ys, jint uvs, jint uvp, YuvFrame& f) {
    f.y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(y));
    f.u = static_cast<const uint8_t*>(env->GetDirectBufferAddress(u));
    f.v = static_cast<const uint8_t*>(env->GetDirectBufferAddress(v));
    f.width = w;
    f.height = h;
    f.yStride = ys;
    f.uvStride = uvs;
    f.uvPixelStride = uvp;
    return f.y && f.u && f.v && w > 0 && h > 0;
}

std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_com_tomppi_splatproto_NativeCore_buildInfo(JNIEnv* env, jobject) {
    const std::string info = "OpenCV " CV_VERSION ", " + std::to_string(cv::getNumberOfCPUs()) + " CPUs, " +
                             std::to_string(cv::getNumThreads()) + " threads";
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jstring JNICALL Java_com_tomppi_splatproto_NativeCore_cpuFeatures(JNIEnv* env, jobject) {
    return env->NewStringUTF(cpuFeatures().c_str());
}

JNIEXPORT jstring JNICALL Java_com_tomppi_splatproto_NativeCore_openclSummary(JNIEnv* env, jobject) {
    return env->NewStringUTF(openclSummary().c_str());
}

JNIEXPORT void JNICALL Java_com_tomppi_splatproto_NativeCore_trackerReset(JNIEnv*, jobject) { gTracker.reset(); }

JNIEXPORT void JNICALL Java_com_tomppi_splatproto_NativeCore_trackerMarkKeyframe(JNIEnv*, jobject) {
    gTracker.markKeyframe();
}

// out: [progress, sharpness, meanY, darkRatio, clipRatio, keyframe(0/1), tracked, sharp(0/1)]
// pts: x, y, anchorX, anchorY per point, normalised, in the sensor frame. Returns the number of points.
JNIEXPORT jint JNICALL Java_com_tomppi_splatproto_NativeCore_trackFrame(
    JNIEnv* env, jobject, jobject y, jobject u, jobject v, jint w, jint h, jint ys, jint uvs, jint uvp, jobject preview,
    jint rotation, jboolean track, jfloatArray out, jfloatArray pts) {
    YuvFrame f;
    if (!frameFrom(env, y, u, v, w, h, ys, uvs, uvp, f)) return 0;
    if (preview) {
        AndroidBitmapInfo info;
        void* pixels = nullptr;
        if (AndroidBitmap_getInfo(env, preview, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
            info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
            AndroidBitmap_lockPixels(env, preview, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS) {
            renderPreview(f, rotation, static_cast<uint8_t*>(pixels), int(info.width), int(info.height), int(info.stride));
            AndroidBitmap_unlockPixels(env, preview);
        }
    }
    if (!track) return 0;
    const TrackResult r = gTracker.process(f);
    const jfloat o[8] = {r.progress, r.sharpness, r.meanY, r.darkRatio, r.clipRatio, r.keyframe ? 1.f : 0.f,
                         float(r.tracked), r.sharp ? 1.f : 0.f};
    env->SetFloatArrayRegion(out, 0, std::min<jsize>(8, env->GetArrayLength(out)), o);
    const int cap = env->GetArrayLength(pts) / 4;
    const int n = std::min(cap, int(r.points.size()));
    std::vector<jfloat> buf(size_t(n) * 4);
    for (int i = 0; i < n; ++i) {
        buf[4 * i] = r.points[i].x; buf[4 * i + 1] = r.points[i].y;
        buf[4 * i + 2] = r.anchors[i].x; buf[4 * i + 3] = r.anchors[i].y;
    }
    if (n > 0) env->SetFloatArrayRegion(pts, 0, 4 * n, buf.data());
    return n;
}

JNIEXPORT jboolean JNICALL Java_com_tomppi_splatproto_NativeCore_saveJpeg(JNIEnv* env, jobject, jobject y, jobject u,
                                                                              jobject v, jint w, jint h, jint ys, jint uvs,
                                                                              jint uvp, jstring path, jint quality) {
    YuvFrame f;
    if (!frameFrom(env, y, u, v, w, h, ys, uvs, uvp, f)) return JNI_FALSE;
    return saveJpeg(f, str(env, path), quality) ? JNI_TRUE : JNI_FALSE;
}

// cameras: 15 doubles per camera:
//   id, width, height, fx, fy, cx, cy, k1, k2, p1, p2, k3, lensX, lensY, lensZ
// cameraFlags: per camera, bit 0 = calibrated, bit 1 = has lens position.
// images: paths; imageCams/imageRig per image; imageUp: 3 floats per image;
// imageMotion: 6 doubles per image: time (s), rolling-shutter skew (s), gyro x y z, has gyro.
// options: [dense(0/1), featureMaxSide, maxFeatures, denseMaxSide, maxDenseImages, markerSizeMm(optional)]
JNIEXPORT jstring JNICALL Java_com_tomppi_splatproto_NativeCore_reconstruct(
    JNIEnv* env, jobject, jdoubleArray cameras, jintArray cameraFlags, jobjectArray images, jintArray imageCams,
    jintArray imageRig, jfloatArray imageUp, jdoubleArray imageMotion, jstring outDir, jstring imageRoot, jintArray options, jobject progress) {
    Options opt;
    {
        jint o[6] = {1, 0, 0, 0, 0, 0};
        env->GetIntArrayRegion(options, 0, std::min<jsize>(6, env->GetArrayLength(options)), o);
        opt.dense = o[0] != 0;
        opt.featureMaxSide = o[1];
        opt.maxFeatures = o[2];
        opt.denseMaxSide = o[3];
        opt.maxDenseImages = o[4];
        opt.markerSizeMm = o[5];
    }
    Reconstructor rec(opt);
    const int nc = env->GetArrayLength(cameraFlags);
    std::vector<jdouble> c(size_t(nc) * 15);
    std::vector<jint> flags(nc);
    env->GetDoubleArrayRegion(cameras, 0, jsize(c.size()), c.data());
    env->GetIntArrayRegion(cameraFlags, 0, nc, flags.data());
    for (int k = 0; k < nc; ++k) {
        const double* p = &c[size_t(k) * 15];
        CameraModel m;
        m.id = int(p[0]);
        m.width = int(p[1]);
        m.height = int(p[2]);
        m.fx = p[3]; m.fy = p[4]; m.cx = p[5]; m.cy = p[6];
        m.k1 = p[7]; m.k2 = p[8]; m.p1 = p[9]; m.p2 = p[10]; m.k3 = p[11];
        m.lensX = p[12]; m.lensY = p[13]; m.lensZ = p[14];
        m.calibrated = flags[k] & 1;
        m.hasLensPosition = flags[k] & 2;
        rec.addCamera(m);
    }
    const int ni = env->GetArrayLength(images);
    std::vector<jint> cams(ni), rig(ni);
    std::vector<jfloat> up(size_t(ni) * 3);
    env->GetIntArrayRegion(imageCams, 0, ni, cams.data());
    env->GetIntArrayRegion(imageRig, 0, ni, rig.data());
    env->GetFloatArrayRegion(imageUp, 0, ni * 3, up.data());
    std::vector<jdouble> motion(size_t(ni) * 6);
    env->GetDoubleArrayRegion(imageMotion, 0, ni * 6, motion.data());
    for (int i = 0; i < ni; ++i) {
        auto js = static_cast<jstring>(env->GetObjectArrayElement(images, i));
        ImageInput in;
        in.path = str(env, js);
        env->DeleteLocalRef(js);
        in.cameraId = cams[i];
        in.rigGroup = rig[i];
        in.upX = up[3 * i];
        in.upY = up[3 * i + 1];
        in.upZ = up[3 * i + 2];
        const double* mo = &motion[size_t(i) * 6];
        in.time = mo[0];
        in.rollingShutterSkew = mo[1];
        in.gyroX = mo[2]; in.gyroY = mo[3]; in.gyroZ = mo[4];
        in.hasGyro = mo[5] > 0.5;
        rec.addImage(in);
    }

    jclass cls = env->GetObjectClass(progress);
    jmethodID cb = env->GetMethodID(cls, "onProgress", "(Ljava/lang/String;F)Z");
    auto fn = [&](const std::string& stage, float frac) -> bool {
        jstring s = env->NewStringUTF(stage.c_str());
        const jboolean keep = env->CallBooleanMethod(progress, cb, s, frac);
        env->DeleteLocalRef(s);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keep == JNI_TRUE;
    };
    LOGI("reconstructing %d images, %d cameras", ni, nc);
    const Result r = rec.run(str(env, outDir), str(env, imageRoot), fn);
    LOGI("done: ok=%d registered=%d dense=%d", r.ok, r.imagesRegistered, r.densePoints);
    return env->NewStringUTF(resultToJson(r).c_str());
}

}  // extern "C"
