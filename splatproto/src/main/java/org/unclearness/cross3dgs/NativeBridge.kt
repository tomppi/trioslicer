package org.unclearness.cross3dgs

import android.view.Surface

object NativeBridge {
    init {
        System.loadLibrary("cross3dgs_android")
    }

    interface Listener {
        fun onLog(line: String)
        fun onProgress(iteration: Int, total: Int, loss: Float,
                       elapsedSeconds: Double)
        fun onFinished(exitCode: Int, message: String, plyPath: String)
    }

    external fun queryDeviceCaps(): String
    /** [viewLimit] caps the training views used (0 = all installed). */
    external fun startTraining(sceneDir: String, outputDir: String,
                               imageDir: String, sparseDir: String,
                               iterations: Int, viewLimit: Int,
                               progressEvery: Int,
                               stochasticSamples: Int, ssimWeight: Float,
                               initCheckpoint: String,
                               shDegree: Int, densityStrategy: String,
                               gaussianCapacity: Int,
                               viewOrder: String, adamMoments: String,
                               fullImageSsim: Boolean,
                               mcmcCloneOffset: Float,
                               mcmcGradientSampling: Float,
                               forceAtomicFreeExact: Boolean,
                               backwardCandidate: String): Boolean
    /**
     * Sets the training-view sampling seed for the next run (default 42,
     * the historical compile-time value). Returns false while a run is
     * active.
     */
    external fun setTrainingViewSeed(value: Int): Boolean

    external fun setInitSeed(value: Int): Boolean

    external fun setTileBinCapacity(value: Int): Boolean

    external fun requestStop()
    /**
     * Bounded failure probe (subgroup_radix | cas_scatter |
     * batched_hang | per_splat_scatter) or the safe
     * conformance_<candidate> matrix. Failure probes enable one
     * normally rejected schedule candidate on a
     * minimal synthetic workload; the host harness bounds it with a
     * watchdog. Never part of training.
     */
    external fun runScheduleProbe(probeMode: String,
                                  outputDir: String): Boolean
    external fun isRunning(): Boolean
    external fun setSurface(surface: Surface?)
    external fun selectResult(viewIndex: Int, target: Boolean): Boolean
    external fun setListener(listener: Listener?)
}
