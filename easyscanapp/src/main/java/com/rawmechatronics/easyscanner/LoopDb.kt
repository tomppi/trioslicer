package com.rawmechatronics.easyscanner

import android.util.Log

/**
 * On-device XFeat 64-D mutual-nearest-neighbor loop database. Indexes L2-normalized
 * 64-D XFeat descriptors directly in native C++ without an external vocabulary file.
 */
object LoopDb {
    private const val TAG = "LoopDb"
    const val DESC_DIM = 64

    private external fun nativeLoad(path: String): Boolean
    private external fun nativeAdd(id: Int, desc: FloatArray, n: Int): Int
    private external fun nativeQuery(
        desc: FloatArray, n: Int, maxResults: Int, maxId: Int,
        outIds: IntArray, outScores: FloatArray,
    ): Int
    private external fun nativeClear()

    private var loaded = false

    init {
        try {
            System.loadLibrary("supervins_jni")
        } catch (t: Throwable) {
            // JVM unit tests have no Android native libraries.
            try { Log.e(TAG, "native lib load failed", t) } catch (_: Throwable) { }
        }
    }

    fun load(vocabularyPath: String): Boolean {
        loaded = nativeLoad(vocabularyPath)
        Log.i(TAG, "load " + vocabularyPath + " -> " + loaded)
        return loaded
    }

    fun isLoaded(): Boolean = loaded

    fun clear() {
        if (loaded) nativeClear()
    }

    /** Adds one keyframe; returns the database entry id, or -1. */
    fun add(id: Int, descriptors: FloatArray, count: Int): Int {
        if (!loaded || count <= 0 || descriptors.size < count * DESC_DIM) return -1
        return nativeAdd(id, descriptors, count)
    }

    /**
     * Returns up to [maxResults] candidate keyframe ids and similarity scores.
     * [maxId] restricts the search to keyframes at or before that id.
     */
    fun query(descriptors: FloatArray, count: Int, maxResults: Int = 8, maxId: Int = -1): Array<Pair<Int, Float>> {
        if (!loaded || count <= 0 || descriptors.size < count * DESC_DIM) return emptyArray()
        val ids = IntArray(maxResults)
        val scores = FloatArray(maxResults)
        val n = nativeQuery(descriptors, count, maxResults, maxId, ids, scores)
        if (n <= 0) return emptyArray()
        return Array(n) { i -> Pair(ids[i], scores[i]) }
    }
}
