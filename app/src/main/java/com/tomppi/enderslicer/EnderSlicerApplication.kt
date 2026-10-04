package com.tomppi.enderslicer

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import com.tomppi.enderslicer.smartinfill.SmartInfillActivity
import com.tomppi.enderslicer.texturizer.BumpMeshActivity
import com.tomppi.enderslicer.window.ToolWindowOrientationPolicy
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Applies adaptive orientation policy without coupling either WebView host to it. */
class EnderSlicerApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var resumedToolActivity: WeakReference<Activity>? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        installCrashLog()
        // The Blender engine is NOT started here. Booting it at launch loaded a
        // 1.3 GB library, extracted its assets and armed a 10-minute partial
        // wake lock for every user who opened the app - including the ones who
        // never touch Blender - and the lock outlived the screen going off. It
        // starts when something asks for it: handing a model over, opening the
        // modelling view, or a command to the preview client.
    }

    /**
     * Captures uncaught exceptions to filesDir/crash.log so device-side crashes
     * (intermittent nozzle-path issues included) can be diagnosed: pull it with
     * adb pull /data/data/com.tomppi.enderslicercura/files/crash.log
     */
    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val log = File(filesDir, "crash.log")
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val trace = throwable.stackTraceToString()
                // Keep the most recent incidents only.
                val existing = if (log.isFile) log.readText().takeLast(48_000) else ""
                log.writeText(existing + "\n==== " + stamp + " thread=" + thread.name + " ====\n" + trace + "\n")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resumedToolActivity
            ?.get()
            ?.takeIf(::isAdaptiveToolActivity)
            ?.let(ToolWindowOrientationPolicy::apply)
    }

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (isAdaptiveToolActivity(activity)) ToolWindowOrientationPolicy.apply(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        if (isAdaptiveToolActivity(activity)) {
            resumedToolActivity = WeakReference(activity)
            ToolWindowOrientationPolicy.apply(activity)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedToolActivity?.get() === activity) resumedToolActivity = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (resumedToolActivity?.get() === activity) resumedToolActivity = null
    }

    private fun isAdaptiveToolActivity(activity: Activity): Boolean =
        activity is SmartInfillActivity || activity is BumpMeshActivity
}
