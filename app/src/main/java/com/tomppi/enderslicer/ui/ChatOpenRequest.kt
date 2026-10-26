package com.tomppi.enderslicer.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Open the AI chat" requests from outside the composition.
 *
 * The question notification's tap lands in [com.tomppi.enderslicer.MainActivity] as an intent
 * extra; the activity turns it into a tick here, and the app opens the chat on the next tick. A
 * plain counter rather than a boolean: the same request may arrive twice, and each one should
 * open the chat.
 */
object ChatOpenRequest {
    /** Intent extra: the notification asked for the AI chat to be opened. */
    const val EXTRA_OPEN_AI_CHAT = "com.tomppi.enderslicer.extra.OPEN_AI_CHAT"

    private val _ticks = MutableStateFlow(0)

    /** How many open-chat requests have been made; the app opens the chat on each new tick. */
    val ticks: StateFlow<Int> = _ticks.asStateFlow()

    fun request() {
        _ticks.value = _ticks.value + 1
    }
}
