package com.rawmechatronics.easyscanner

import android.util.Log

/**
 * JNI bridge to `liblivemesh_jni.so` (off-heap native C++ sparse TSDF + Marching Cubes +
 * direct OpenGL ES 3.0 GPU VBO/IBO upload). Keeps voxel blocks and extracted meshes
 * completely off the 512 MB Android Java heap.
 */
object NativeLiveTsdf {
    private const val TAG = "NativeLiveTsdf"

    val available: Boolean = runCatching {
        System.loadLibrary("livemesh_jni")
        true
    }.getOrElse { e ->
        // Host JVM unit tests have no Android arm64 .so; fall back cleanly to Kotlin TsdfVolume.
        runCatching { Log.i(TAG, "livemesh_jni unavailable (JVM fallback): " + e.message) }
        false
    }

    @JvmStatic external fun nativeCreate(voxelSizeM: Float, truncationM: Float, maxBlocks: Int, maxDepthM: Double): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeClear(handle: Long)
    @JvmStatic external fun nativeEstimateNovelty(
        handle: Long,
        poseR: DoubleArray, poseT: DoubleArray, cam11: DoubleArray,
        rawDepth: FloatArray, scale: Double, offset: Double, validMask518: BooleanArray?,
    ): Float
    @JvmStatic external fun nativeIntegrateDepth(
        handle: Long,
        poseR: DoubleArray, poseT: DoubleArray, cam11: DoubleArray,
        rawDepth: FloatArray, scale: Double, offset: Double,
        rgb518: ByteArray, validMask518: BooleanArray?, stride: Int, da3Weight: Float,
    )
    @JvmStatic external fun nativeIntegrateLaser(
        handle: Long,
        poseR: DoubleArray, poseT: DoubleArray, laserCamM: FloatArray, laserWeight: Float,
    ): Int
    @JvmStatic external fun nativeIntegrateOrientedPoints(
        handle: Long,
        xyzNxyz: FloatArray, rgb: ByteArray?, numPoints: Int, pointWeight: Float,
    ): Int
    @JvmStatic external fun nativePrepareMesh(handle: Long): Int
    @JvmStatic external fun nativeUploadToGpu(
        handle: Long, vboId: Int, iboId: Int, outStats6: IntArray, outBounds6: FloatArray,
    ): Boolean
    @JvmStatic external fun nativeSampleSurface(
        handle: Long, maxPoints: Int, outPos: FloatArray, outNorm: FloatArray,
    ): Int
    @JvmStatic external fun nativeWriteMeshPly(handle: Long, path: String, toMillimetres: Boolean): Int
    @JvmStatic external fun nativeWriteMeshStl(handle: Long, path: String, toMillimetres: Boolean): Int
    @JvmStatic external fun nativeWriteMeshPlyDualContouring(handle: Long, path: String, toMillimetres: Boolean): Int
    @JvmStatic external fun nativeWriteMeshStlDualContouring(handle: Long, path: String, toMillimetres: Boolean): Int
    @JvmStatic external fun nativeGetStats(handle: Long, outStats5: IntArray)
    @JvmStatic external fun nativeRaycastCenterDepthM(handle: Long, poseR: DoubleArray, poseT: DoubleArray): Float

    // Coarse Plane-Sweep Multi-View Stereo (LiveMvsEngine)
    @JvmStatic external fun nativeMvsCreate(): Long
    @JvmStatic external fun nativeMvsDestroy(mvsHandle: Long)
    @JvmStatic external fun nativeMvsReset(mvsHandle: Long)
    @JvmStatic external fun nativeMvsShouldAccept(
        mvsHandle: Long,
        poseR: DoubleArray, poseT: DoubleArray, minTransM: Double, minRotDeg: Double,
    ): Boolean
    @JvmStatic external fun nativeMvsDownscaleYuv(
        yBuf: java.nio.ByteBuffer, yStride: Int,
        uBuf: java.nio.ByteBuffer, vBuf: java.nio.ByteBuffer, uvStride: Int, uvPixStride: Int,
        srcW: Int, srcH: Int,
        outGray: ByteArray, outRgb: ByteArray,
    )
    @JvmStatic external fun nativeMvsPushAndFuse(
        mvsHandle: Long, tsdfHandle: Long,
        gray160x120: ByteArray, rgb160x120: ByteArray,
        poseR: DoubleArray, poseT: DoubleArray, cam11: DoubleArray,
        focusDepthM: Double, outStats4: IntArray,
    )
}
