package com.tomppi.enderslicer.ui

/**
 * The floating chat's size: a reachable bubble, the floating panel, or the whole screen.
 *
 * Three sizes, one surface. The state lives with the caller (EnderSlicerApp) so an expanded chat
 * is still expanded when it is reopened, and so the plate underneath keeps running while the
 * chat changes size - resizing is a state change, never a restart of the agent or the turn.
 */
enum class ChatSize { BUBBLE, PANEL, FULL }

/** What the system Back gesture does to an open chat. */
enum class ChatBackAction { TO_PANEL, CLOSE }

/**
 * The size transitions, in one place.
 *
 * Every control and the Back gesture go through these functions, so the panel, the full-screen
 * header, the bubble and the back handler cannot disagree about what a tap does.
 */
object ChatSizes {

    /** The expand button in the header: the floating panel becomes the full screen. */
    fun expanded(): ChatSize = ChatSize.FULL

    /** The collapse button, and Back from full screen: the full screen becomes the panel. */
    fun collapsed(): ChatSize = ChatSize.PANEL

    /** The minimise button in either header: the chat becomes its bubble, never closed. */
    fun minimised(): ChatSize = ChatSize.BUBBLE

    /**
     * Tapping the bubble: back to the size the user was at before minimising.
     *
     * A chat minimised from full screen comes back full screen; one minimised from the panel
     * comes back as a panel. It never opens into the bubble the user just tapped.
     */
    fun fromBubble(lastExpanded: ChatSize): ChatSize =
        if (lastExpanded == ChatSize.FULL) ChatSize.FULL else ChatSize.PANEL

    /** What system Back does while the chat is open: full screen collapses, anything else closes. */
    fun back(size: ChatSize): ChatBackAction =
        if (size == ChatSize.FULL) ChatBackAction.TO_PANEL else ChatBackAction.CLOSE

    /**
     * The size the chat opens at when it is reopened.
     *
     * A chat that was left as a panel opens as a panel, one that was left full screen opens full
     * screen, and one that was minimised opens where the user was before the bubble - reopening
     * never lands on the bubble.
     */
    fun reopened(size: ChatSize, lastExpanded: ChatSize): ChatSize =
        if (size == ChatSize.BUBBLE) fromBubble(lastExpanded) else size

    /**
     * The "last expanded size" to remember after a transition.
     *
     * The bubble is not an expanded size: minimising from full screen must not make the next
     * bubble tap open the panel, and leaving the chat as a panel must never reopen it full.
     */
    fun rememberExpanded(lastExpanded: ChatSize, next: ChatSize): ChatSize =
        if (next == ChatSize.BUBBLE) lastExpanded else next
}
