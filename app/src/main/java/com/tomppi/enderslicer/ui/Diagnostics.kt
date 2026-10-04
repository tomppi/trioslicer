package com.tomppi.enderslicer.ui

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's own log, off until someone turns it on.
 *
 * Everything the app reports as a failure already passes through one of two
 * functions in [MainViewModel], which is why this can be a faithful record
 * without touching thirty call sites: the funnel is the hook. The same is true
 * of the messages the user sees - this keeps what was shown, so a report of
 * "it said something went wrong" can be read back with the text.
 *
 * Nothing is recorded while it is off, so a release build pays nothing for it,
 * and the buffer is bounded because a phone should not grow an unbounded list
 * for a diagnostic nobody is reading.
 */
object Diagnostics {
    /**
     * WARNING exists because the app already reports things that went wrong
     * without the operation failing: a slice that succeeds with a nozzle
     * collision risk is the one that matters. Folding those into FAILURE would
     * call a good slice broken, and folding them into INFO would bury the one
     * line on the screen that a user needs to read.
     */
    enum class Level { INFO, WARNING, FAILURE }

    data class Entry(
        val atMillis: Long,
        val level: Level,
        val source: String,
        val message: String,
    )

    /** Enough to cover a session's work without keeping a whole one in memory. */
    const val MAX_ENTRIES = 400

    private const val TAG = "TrioSlicer"
    private const val PREFERENCES_NAME = "enderslicer-diagnostics"
    private const val KEY_ENABLED = "diagnostics-enabled"

    /**
     * Observable so the switch and the corner banner follow it without the
     * setting being threaded through the whole tree the way a per-screen
     * argument would have to be.
     */
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest last, so the window can scroll to the end and read like a log. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /**
     * The last thing worth shouting about - the newest WARNING or FAILURE - for
     * the corner of the model viewer. Separate from [entries] because it is
     * dismissed on its own: the log keeps the record after the corner has
     * stopped showing it.
     */
    private val _latestAlert = MutableStateFlow<Entry?>(null)
    val latestAlert: StateFlow<Entry?> = _latestAlert.asStateFlow()

    fun initialize(context: Context): Boolean {
        _enabled.value = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
        return _enabled.value
    }

    fun isEnabled(): Boolean = _enabled.value

    /** Turning it off clears what it kept: the log is the feature, not the state. */
    fun setEnabled(context: Context, value: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
        setEnabled(value)
    }

    /** The in-memory half, separate so the buffer can be driven without a Context. */
    internal fun setEnabled(value: Boolean) {
        _enabled.value = value
        if (!value) clear()
    }

    fun info(source: String, message: String) = record(Level.INFO, source, message)

    fun warning(source: String, message: String) = record(Level.WARNING, source, message)

    fun failure(source: String, error: Throwable) {
        val message = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        record(Level.FAILURE, source, message)
    }

    fun clear() {
        _entries.value = emptyList()
        _latestAlert.value = null
    }

    /** Hides the corner banner without touching the record behind it. */
    fun dismissAlert() {
        _latestAlert.value = null
    }

    fun formatTime(atMillis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(atMillis))

    private fun record(level: Level, source: String, message: String) {
        // The platform log is always fed: a crash report or a logcat capture is
        // the other way these lines are read, and it costs nothing to keep it
        // truthful whether or not the in-app window is on. Failures go in at
        // error level and with a banner around them, so they are loud in a
        // logcat capture and impossible to skim past in one.
        when (level) {
            Level.FAILURE -> Log.e(TAG, "==== FAILED: " + source + ": " + message)
            Level.WARNING -> Log.w(TAG, "==== WARNING: " + source + ": " + message)
            Level.INFO -> Log.i(TAG, source + ": " + message)
        }
        val entry = Entry(System.currentTimeMillis(), level, source, message)
        // The corner is not the log. A failure has to reach the model viewer
        // whether or not anyone switched the log on, because the alternative is
        // what the app did before this: the outcome was written to a status line
        // that lives in a card folded down to a chevron, so a slice that failed
        // and a slice that was never asked for looked exactly the same. The log
        // - the record, the list - stays opt-in.
        if (level != Level.INFO) _latestAlert.value = entry
        if (!_enabled.value) return
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
    }
}
