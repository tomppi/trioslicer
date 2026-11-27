package com.tomppi.splatproto

import android.util.Log
import org.unclearness.cross3dgs.NativeBridge
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs Cross3DGS's Vulkan trainer on one scan.
 *
 * The JNI entry starts a native thread and returns immediately; the listener
 * completes the latch when that thread finishes. Cross3DGS selects its GPU
 * schedule automatically from its per-device catalog (per-splat CAS on Adreno).
 */
object Cross3dgsTrainer {
    data class Result(val exitCode: Int, val message: String, val plyPath: String)

    fun train(
        scanDir: File,
        outputDir: File,
        initCheckpoint: String,
        settings: PipelineSettings,
        useMasks: Boolean = settings.useMasks,
        onProgress: (iter: Int, total: Int, loss: Float, elapsedSeconds: Double) -> Boolean,
    ): Result {
        val sparseDir = File(scanDir, "sparse/0")
        val sparseCount = ColmapBinaryWriter.ensureBinaryWithCount(sparseDir)
        if (sparseCount < 0) {
            return Result(1, "Could not convert COLMAP model to binary", "")
        }
        val checkpointCount = if (initCheckpoint.isNotEmpty()) {
            Cross3dgsCheckpoint.readCount(File(initCheckpoint))
        } else 0
        val initialCount = if (initCheckpoint.isNotEmpty()) checkpointCount else sparseCount
        if (initialCount <= 0) {
            return Result(1, "No sparse 3D points or checkpoint to initialize Cross3DGS", "")
        }
        if (!Cross3dgsUndistort.ensure(Scan(scanDir), settings.maxResolution, useMasks)) {
            return Result(1, "Could not prepare undistorted training images", "")
        }

        val strategy = if (settings.densityStrategy == "fixed") "fixed" else "mcmc"
        // Cross3DGS throws if capacity < initialCount, or if strategy=="mcmc" and capacity == initialCount.
        val resolvedCapacity = if (strategy == "mcmc") {
            maxOf(settings.maxSplats, initialCount + 4096, (initialCount * 1.25).toInt())
        } else {
            maxOf(settings.maxSplats, initialCount)
        }
        val shDegree = settings.shDegree.coerceIn(0, 3)
        val ssimWeight = settings.ssimWeight.coerceIn(0f, 1f)
        val iterations = settings.trainSteps.coerceAtLeast(100)

        Log.i(
            "Cross3dgs",
            "startTraining steps=$iterations init=$initialCount cap=$resolvedCapacity " +
                "sh=$shDegree strategy=$strategy ssim=$ssimWeight res=${settings.maxResolution} " +
                "masks=$useMasks checkpoint='${initCheckpoint.substringAfterLast('/')}'",
        )
        val latch = CountDownLatch(1)
        var result = Result(1, "Training did not finish", "")
        val listener = object : NativeBridge.Listener {
            override fun onLog(line: String) {
                Log.i("Cross3dgs", line)
            }

            override fun onProgress(iteration: Int, total: Int, loss: Float, elapsedSeconds: Double) {
                if (!onProgress(iteration, total, loss, elapsedSeconds)) NativeBridge.requestStop()
            }

            override fun onFinished(exitCode: Int, message: String, plyPath: String) {
                result = Result(exitCode, message, plyPath)
                latch.countDown()
            }
        }
        NativeBridge.setListener(listener)
        NativeBridge.setTrainingViewSeed(42)
        NativeBridge.setInitSeed(42)
        NativeBridge.setTileBinCapacity(2048)
        val started = try {
            NativeBridge.startTraining(
                scanDir.absolutePath,
                outputDir.absolutePath,
                "cross3dgs/images",
                "sparse/0",
                iterations,
                0,
                maxOf(1, iterations / 100),
                0,
                ssimWeight,
                initCheckpoint,
                shDegree,
                strategy,
                resolvedCapacity,
                "random",
                "fp32",
                true,
                settings.mcmcCloneOffset.coerceIn(0.05f, 2.0f),
                settings.mcmcGradientSampling.coerceIn(0.1f, 5.0f),
                false,
                "auto",
            )
        } catch (t: Throwable) {
            Log.e("Cross3dgs", "startTraining failed", t)
            NativeBridge.setListener(null)
            return Result(1, t.message ?: t.javaClass.simpleName, "")
        }
        if (!started) {
            NativeBridge.setListener(null)
            return Result(2, "Cross3DGS is already running", "")
        }
        while (!latch.await(1, TimeUnit.SECONDS)) {
            if (!NativeBridge.isRunning() && latch.count == 1L) {
                break
            }
        }
        NativeBridge.setListener(null)
        return result
    }
}
