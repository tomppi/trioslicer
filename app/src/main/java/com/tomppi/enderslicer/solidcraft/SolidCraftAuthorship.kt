package com.tomppi.enderslicer.solidcraft

/**
 * Who may edit the CAD document: exactly one author at a time.
 *
 * The document has two doors - this app's own in-app agent, running in this process, and external
 * agents through [SolidCraftBridge] on 127.0.0.1:9878. Two designers interleaved into one design
 * produce a document neither can explain, so the app arbitrates with connection state:
 *
 *  - while an external agent is **attached** (a live bridge connection, not a past one), the
 *    in-app agent is paused and the sheet offers **Take over**;
 *  - while the in-app agent has a **turn in flight**, the bridge refuses new external
 *    connections by name and never disconnects an existing one to make room;
 *  - everyone else is nobody: the sheet says so, and either door may open.
 *
 * The rule lives here, in one object with one lock, rather than in the UI: a screen can only
 * read a [State], and the two author states are made mutually exclusive by the transitions
 * below. [state] asserts the invariant as well, so a future transition that breaks it fails
 * loudly in a test instead of producing a document with two authors.
 */
class SolidCraftAuthorship {

    /** Who is allowed to change the document right now. */
    enum class Author { NOBODY, IN_APP, EXTERNAL }

    /** The ownership, as one consistent snapshot - what the sheet renders. */
    data class State(
        val author: Author,
        val externalClients: Int,
        val inAppTurn: Boolean,
        val bridgePort: Int,
    ) {
        /** An external agent is connected, so the in-app agent is paused and Take over is offered. */
        val inAppPaused: Boolean get() = author == Author.EXTERNAL

        /** The in-app composer may be used: nothing external is attached. */
        val canInAppEdit: Boolean get() = author != Author.EXTERNAL

        /** The one line the sheet always shows, in every state. */
        val authorLine: String get() = when (author) {
            Author.IN_APP -> "Author: in-app agent"
            Author.EXTERNAL -> if (bridgePort > 0) {
                "Author: external agent on " + bridgePort
            } else {
                "Author: external agent on the bridge"
            }
            Author.NOBODY -> "Author: nobody"
        }

        val pausedLine: String get() = "external agent connected - in-app agent paused"
    }

    private val lock = Any()

    private var inAppTurn = false

    private var externalClients = 0

    private var takingOver = false

    private var bridgePort = 0

    /** The bridge's own way to close its live external sockets, bound while it runs. */
    private var closeExternal: (() -> Int)? = null

    /** The current ownership. Never reports two authors at once. */
    fun state(): State = synchronized(lock) {
        check(!(inAppTurn && externalClients > 0)) {
            "authorship invariant broken: the in-app agent and " + externalClients +
                " external client(s) are authors at the same time"
        }
        State(
            author = when {
                externalClients > 0 -> Author.EXTERNAL
                inAppTurn -> Author.IN_APP
                else -> Author.NOBODY
            },
            externalClients = externalClients,
            inAppTurn = inAppTurn,
            bridgePort = bridgePort,
        )
    }

    /**
     * One external connection opened.
     *
     * False while the in-app agent is driving, and while a Take over is closing the previous
     * connections: the caller answers with [refusalMessage] and closes, never queueing and never
     * disconnecting anyone to make room.
     */
    fun attachExternal(): Boolean = synchronized(lock) {
        if (inAppTurn || takingOver) return false
        externalClients++
        true
    }

    /** One external connection ended; idempotent per connection because the caller tracks it. */
    fun detachExternal() {
        synchronized(lock) { if (externalClients > 0) externalClients-- }
    }

    /**
     * The in-app agent starts a turn.
     *
     * False while an external agent is attached: the composer should not have sent, and the
     * sheet's paused state is what told the user why.
     */
    fun beginInAppTurn(): Boolean = synchronized(lock) {
        if (externalClients > 0 || inAppTurn) return false
        inAppTurn = true
        true
    }

    /** The in-app agent's turn ended, whatever it did. */
    fun endInAppTurn() {
        synchronized(lock) { inAppTurn = false }
    }

    /** True when a bridge message may be forwarded right now. */
    fun externalAllowed(): Boolean = synchronized(lock) { !inAppTurn && !takingOver }

    /** The named sentence the bridge refuses a connection with. */
    fun refusalMessage(): String = synchronized(lock) {
        when {
            takingOver ->
                "the app is taking this document back from an external agent; retry in a moment"
            inAppTurn ->
                "the in-app agent is driving this document right now; retry when its turn finishes"
            else -> "an external agent may not attach right now"
        }
    }

    /** The named reason the in-app agent cannot start, or null when it may. */
    fun inAppBlockedReason(): String? = synchronized(lock) {
        when {
            externalClients > 0 ->
                "an external agent is connected" + (if (bridgePort > 0) " on " + bridgePort else "") +
                    " - press Take over in the CAD agent sheet first"
            else -> null
        }
    }

    /**
     * Take over: closes the live external connections and makes the in-app agent the available
     * author. Refused while the in-app agent is mid-turn (it is already the author then).
     *
     * The closer detaches each connection through [detachExternal] before closing it, so the
     * state flips to nobody the moment this returns; the outbound agent has to reconnect.
     */
    fun takeOver(): Int = synchronized(lock) {
        check(!inAppTurn) { "the in-app agent is already the author" }
        val closer = closeExternal ?: return 0
        takingOver = true
        try {
            closer()
        } finally {
            takingOver = false
        }
    }

    /** The bridge binds its close function while it runs and unbinds it when it closes. */
    fun bindExternalCloser(closer: (() -> Int)?) {
        synchronized(lock) { closeExternal = closer }
    }

    /** The port the author line names; 0 while the bridge is not listening. */
    fun noteBridgePort(port: Int) {
        synchronized(lock) { bridgePort = port }
    }
}
