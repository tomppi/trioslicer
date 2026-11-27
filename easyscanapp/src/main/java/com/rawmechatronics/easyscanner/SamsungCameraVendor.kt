package com.rawmechatronics.easyscanner

import android.annotation.SuppressLint
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Size
import java.lang.reflect.Constructor

/**
 * Samsung's Camera2 HAL accepts the standard manual exposure keys but ignores
 * them in third-party apps. The vendor camera path uses different keys; the
 * same strings work when the hidden CaptureRequest.Key constructor is reached
 * reflectively.
 *
 * These are nullable so a non-Samsung device, or a Samsung build that blocks
 * the hidden constructor, simply falls back to the standard keys.
 */
@SuppressLint("PrivateApi")
object SamsungCameraVendor {
    private const val TAG = "SamsungCameraVendor"

    private fun <T> key(name: String, type: Class<T>): CaptureRequest.Key<T>? = try {
        @Suppress("UNCHECKED_CAST")
        val ctor = CaptureRequest.Key::class.java
            .getDeclaredConstructor(String::class.java, Class::class.java) as Constructor<CaptureRequest.Key<*>>
        ctor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        ctor.newInstance(name, type) as CaptureRequest.Key<T>
    } catch (t: Throwable) {
        Log.w(TAG, "vendor key unavailable: $name", t)
        null
    }

    private val exposureTime = key("samsung.android.sensor.exposureTimeInternal", Long::class.javaObjectType)
    private val gain = key("samsung.android.sensor.gain", Int::class.javaObjectType)
    private val sensitivity = key("samsung.android.sensor.sensitivityInternal", Int::class.javaObjectType)
    private val targetPictureSize = key("samsung.android.scaler.targetPictureSize", Size::class.java)

    @Volatile private var exposureTimeUnsupported = false
    @Volatile private var gainUnsupported = false
    @Volatile private var sensitivityUnsupported = false
    @Volatile private var targetPictureSizeUnsupported = false

    val available: Boolean
        get() = exposureTime != null && gain != null && sensitivity != null

    private fun <T> safeSet(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<T>?,
        value: T,
        unsupported: Boolean,
        markUnsupported: () -> Unit,
    ) {
        if (key == null || unsupported) return
        try {
            builder.set(key, value)
        } catch (_: IllegalArgumentException) {
            markUnsupported()
            Log.i(TAG, "HAL does not advertise vendor key ${key.name}; skipping")
        }
    }

    /** Apply the vendor manual exposure beside the standard keys. */
    fun applyExposure(builder: CaptureRequest.Builder, exposureNs: Long, iso: Int) {
        safeSet(builder, exposureTime, exposureNs, exposureTimeUnsupported) { exposureTimeUnsupported = true }
        safeSet(builder, gain, iso, gainUnsupported) { gainUnsupported = true }
        safeSet(builder, sensitivity, iso, sensitivityUnsupported) { sensitivityUnsupported = true }
    }

    /** Tell the HAL the exact JPEG size for a still request. */
    fun applyTargetSize(builder: CaptureRequest.Builder, size: Size?) {
        if (size != null) {
            safeSet(builder, targetPictureSize, size, targetPictureSizeUnsupported) {
                targetPictureSizeUnsupported = true
            }
        }
    }
}