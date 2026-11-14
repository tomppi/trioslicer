package com.tomppi.splatproto

/** Training progress from Brush. stage: 0=new process, 1=train step, 2=done. Return false to cancel. */
interface BrushProgress {
    fun onProgress(stage: Int, iter: Int): Boolean
}

/** JNI surface for Brush's trainer (built from the Brush repo as libbrush_jni.so). */
object BrushTrainer {
    init {
        System.loadLibrary("brush_jni")
    }

    /**
     * Trains a 3DGS model on a COLMAP dataset and exports PLYs into [outputPath].
     * Blocking call: run it on a background thread.
     * Returns 0 on success, 1 on error, 2 when cancelled by the progress callback.
     */
    external fun nativeTrain(
        datasetPath: String,
        totalSteps: Int,
        refineEvery: Int,
        maxResolution: Int,
        exportEvery: Int,
        maxSplats: Int,
        shDegree: Int,
        lrMean: Double,
        lrCoeffsDc: Double,
        lrOpac: Double,
        lrScale: Double,
        growthGradThreshold: Float,
        growthSelectFraction: Float,
        minScaleFactor: Float,
        ssimWeight: Float,
        growthStopIter: Int,
        outputPath: String,
        progress: BrushProgress,
    ): Int
}
