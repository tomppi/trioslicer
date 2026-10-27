package com.tomppi.enderslicer.ui

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Open the AI chat" requests from outside the composition.
 *
 * The question notification's tap lands in [com.tomppi.enderslicer.MainActivity] as an intent
 * extra; the activity turns it into a tick here, and the app opens the chat for the newest tick
 * it has not acted on yet. A plain counter rather than a boolean: the same request may arrive
 * twice, and each one should open the chat.
 *
 * A request is **consumed exactly once** through [takeUnhandled]. The counter alone made the
 * request sticky state: the composition "opened the chat whenever the counter was above zero",
 * so after one notification tap every later Activity recreation opened the chat again - even
 * after the user had closed it. The composition now opens the chat only for a tick newer than
 * the newest one already handled, and the handled tick lives here, in process-wide state just
 * like the counter: an Activity recreation (a rotation) re-runs the composition's effects with
 * the same state and gets nothing to replay, while a real new tap advances the counter again.
 */
object ChatOpenRequest {
    /** Intent extra: the notification asked for the AI chat to be opened. */
    const val EXTRA_OPEN_AI_CHAT = "com.tomppi.enderslicer.extra.OPEN_AI_CHAT"

    private val _ticks = MutableStateFlow(0)

    /** How many open-chat requests have been made; the app handles each new tick once. */
    val ticks: StateFlow<Int> = _ticks.asStateFlow()

    /** The newest tick some composition has already acted on. */
    private val handled = AtomicInteger(0)

    /** Ask for the chat to be opened. */
    fun request() {
        _ticks.value = _ticks.value + 1
    }

    /**
     * The newest request no composition has acted on yet, or null when there is none.
     *
     * Exactly once per tick: the caller must open the chat when this returns a value. A new
     * composition that runs without a new request (an Activity recreation) gets null, so it
     * leaves the chat open or closed exactly as the user left it.
     */
    fun takeUnhandled(): Int? {
        while (true) {
            val newest = _ticks.value
            val last = handled.get()
            if (newest <= last) return null
            if (handled.compareAndSet(last, newest)) return newest
        }
    }

    /**
     * Forgets all requests. Tests only: the process that owns the notification owns this state,
     * and a test needs a clean slate from the previous test's requests.
     */
    internal fun resetForTest() {
        handled.set(0)
        _ticks.value = 0
    }
}
