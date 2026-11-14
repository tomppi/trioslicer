package com.tomppi.splatproto

import android.content.Context

/** User-tunable pipeline settings: Parallax SfM options + Brush training options. */
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
    val trainSteps: Int = 4_000,
    val refineEvery: Int = 100,
    val maxSplats: Int = 500_000,
    val maxResolution: Int = 640,
    val exportEvery: Int = 1000,
    val shDegree: Int = 3,
    /** When false, masks in the scan folder are ignored (room/whole-scene scans). */
    val useMasks: Boolean = true,
    val lrMean: Float = 0.00002f,
    val lrCoeffsDc: Float = 0.002f,
    val lrOpac: Float = 0.012f,
    val lrScale: Float = 0.005f,
    /** Brush densification knobs. */
    val growthGradThreshold: Float = 0.0025f,
    val growthSelectFraction: Float = 0.25f,
    val growthStopIter: Int = 15000,
    /** Mip-Splatting scale floor; larger suppresses needle floaters. */
    val minScaleFactor: Float = 0.1f,
    val ssimWeight: Float = 0.2f,
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
                refineEvery = p.getInt("refineEvery", d.refineEvery),
                maxSplats = p.getInt("maxSplats", d.maxSplats),
                maxResolution = p.getInt("maxResolution", d.maxResolution),
                exportEvery = p.getInt("exportEvery", d.exportEvery),
                shDegree = p.getInt("shDegree", d.shDegree),
                useMasks = p.getBoolean("useMasks", d.useMasks),
                lrMean = p.getFloat("lrMean", d.lrMean),
                lrCoeffsDc = p.getFloat("lrCoeffsDc", d.lrCoeffsDc),
                lrOpac = p.getFloat("lrOpac", d.lrOpac),
                lrScale = p.getFloat("lrScale", d.lrScale),
                growthGradThreshold = p.getFloat("growthGradThreshold", d.growthGradThreshold),
                growthSelectFraction = p.getFloat("growthSelectFraction", d.growthSelectFraction),
                growthStopIter = p.getInt("growthStopIter", d.growthStopIter),
                minScaleFactor = p.getFloat("minScaleFactor", d.minScaleFactor),
                ssimWeight = p.getFloat("ssimWeight", d.ssimWeight),
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
            .putInt("refineEvery", refineEvery)
            .putInt("maxSplats", maxSplats)
            .putInt("maxResolution", maxResolution)
            .putInt("exportEvery", exportEvery)
            .putInt("shDegree", shDegree)
            .putBoolean("useMasks", useMasks)
            .putFloat("lrMean", lrMean)
            .putFloat("lrCoeffsDc", lrCoeffsDc)
            .putFloat("lrOpac", lrOpac)
            .putFloat("lrScale", lrScale)
            .putFloat("growthGradThreshold", growthGradThreshold)
            .putFloat("growthSelectFraction", growthSelectFraction)
            .putInt("growthStopIter", growthStopIter)
            .putFloat("minScaleFactor", minScaleFactor)
            .putFloat("ssimWeight", ssimWeight)
            .apply()
    }
}