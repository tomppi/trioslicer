package com.tomppi.splatproto

import android.content.Context

/** User-tunable pipeline settings: Parallax SfM + U2Net NPU masking + Cross3DGS training. */
data class PipelineSettings(
    val sfmFeatureSide: Int = 1280,
    val sfmMaxFeatures: Int = 4000,
    val sfmMatchWindow: Int = 10,
    val sfmRatio: Float = 0.8f,
    val sfmMaxReproj: Float = 4f,
    val sfmMinTriAngle: Float = 1.5f,
    val sfmDense: Boolean = true,
    val sfmDenseMaxSide: Int = 960,
    val sfmDenseMaxImages: Int = 48,
    val sfmRollingShutter: Boolean = false,
    /** Experimental: enable optical image stabilisation while capturing. */
    val ois: Boolean = false,
    val sfmMarkerMm: Float = 0f,
    // Cross3DGS Vulkan trainer parameters.
    val trainSteps: Int = 4_000,
    val maxSplats: Int = 300_000,
    val maxResolution: Int = 640,
    val shDegree: Int = 3,
    /** When true, U2Net masks composite background pixels to pure black for Cross3DGS. */
    val useMasks: Boolean = true,
    val ssimWeight: Float = 0.2f,
    /** Cross3DGS density strategy: "mcmc" or "fixed". */
    val densityStrategy: String = "mcmc",
    val mcmcCloneOffset: Float = 0.5f,
    val mcmcGradientSampling: Float = 1.5f,
    /** U2Net NPU masking knobs; defaults are tuned on captured scans. */
    val maskTta: Boolean = true,
    val maskTwoPass: Boolean = true,
    val maskCropMargin: Float = 1.35f,
    val maskThreshold: Float = 0.55f,
    val maskGuidedRadius: Int = 8,
    val maskClosePx: Int = 3,
    /** Target TSDF voxel size (mm) for post-process Laser + Dense MVS STL fusion. */
    val fusionVoxelMm: Float = 1.0f,
) {
    companion object {
        private const val PREFS = "pipeline_settings"

        fun load(context: Context): PipelineSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = PipelineSettings()
            return PipelineSettings(
                sfmFeatureSide = p.getInt("sfmFeatureSide", d.sfmFeatureSide),
                sfmMaxFeatures = p.getInt("sfmMaxFeatures", d.sfmMaxFeatures),
                sfmMatchWindow = p.getInt("sfmMatchWindow", d.sfmMatchWindow),
                sfmRatio = p.getFloat("sfmRatio", d.sfmRatio),
                sfmMaxReproj = p.getFloat("sfmMaxReproj", d.sfmMaxReproj),
                sfmMinTriAngle = p.getFloat("sfmMinTriAngle", d.sfmMinTriAngle),
                sfmDense = p.getBoolean("sfmDense", d.sfmDense),
                sfmDenseMaxSide = p.getInt("sfmDenseMaxSide", d.sfmDenseMaxSide),
                sfmDenseMaxImages = p.getInt("sfmDenseMaxImages", d.sfmDenseMaxImages),
                sfmRollingShutter = p.getBoolean("sfmRollingShutter", d.sfmRollingShutter),
                ois = p.getBoolean("ois", d.ois),
                sfmMarkerMm = p.getFloat("sfmMarkerMm", d.sfmMarkerMm),
                trainSteps = p.getInt("trainSteps", d.trainSteps),
                maxSplats = p.getInt("maxSplats", d.maxSplats),
                maxResolution = p.getInt("maxResolution", d.maxResolution),
                shDegree = p.getInt("shDegree", d.shDegree),
                useMasks = p.getBoolean("useMasks", d.useMasks),
                ssimWeight = p.getFloat("ssimWeight", d.ssimWeight),
                densityStrategy = p.getString("densityStrategy", d.densityStrategy) ?: d.densityStrategy,
                mcmcCloneOffset = p.getFloat("mcmcCloneOffset", d.mcmcCloneOffset),
                mcmcGradientSampling = p.getFloat("mcmcGradientSampling", d.mcmcGradientSampling),
                maskTta = p.getBoolean("maskTta", d.maskTta),
                maskTwoPass = p.getBoolean("maskTwoPass", d.maskTwoPass),
                maskCropMargin = p.getFloat("maskCropMargin", d.maskCropMargin),
                maskThreshold = p.getFloat("maskThreshold", d.maskThreshold),
                maskGuidedRadius = p.getInt("maskGuidedRadius", d.maskGuidedRadius),
                maskClosePx = p.getInt("maskClosePx", d.maskClosePx),
                fusionVoxelMm = p.getFloat("fusionVoxelMm", d.fusionVoxelMm).coerceIn(0.75f, 6.0f),
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("sfmFeatureSide", sfmFeatureSide)
            .putInt("sfmMaxFeatures", sfmMaxFeatures)
            .putInt("sfmMatchWindow", sfmMatchWindow)
            .putFloat("sfmRatio", sfmRatio)
            .putFloat("sfmMaxReproj", sfmMaxReproj)
            .putFloat("sfmMinTriAngle", sfmMinTriAngle)
            .putBoolean("sfmDense", sfmDense)
            .putInt("sfmDenseMaxSide", sfmDenseMaxSide)
            .putInt("sfmDenseMaxImages", sfmDenseMaxImages)
            .putBoolean("sfmRollingShutter", sfmRollingShutter)
            .putBoolean("ois", ois)
            .putFloat("sfmMarkerMm", sfmMarkerMm)
            .putInt("trainSteps", trainSteps)
            .putInt("maxSplats", maxSplats)
            .putInt("maxResolution", maxResolution)
            .putInt("shDegree", shDegree)
            .putBoolean("useMasks", useMasks)
            .putFloat("ssimWeight", ssimWeight)
            .putString("densityStrategy", densityStrategy)
            .putFloat("mcmcCloneOffset", mcmcCloneOffset)
            .putFloat("mcmcGradientSampling", mcmcGradientSampling)
            .putBoolean("maskTta", maskTta)
            .putBoolean("maskTwoPass", maskTwoPass)
            .putFloat("maskCropMargin", maskCropMargin)
            .putFloat("maskThreshold", maskThreshold)
            .putInt("maskGuidedRadius", maskGuidedRadius)
            .putInt("maskClosePx", maskClosePx)
            .putFloat("fusionVoxelMm", fusionVoxelMm.coerceIn(0.75f, 6.0f))
            .apply()
    }
}
