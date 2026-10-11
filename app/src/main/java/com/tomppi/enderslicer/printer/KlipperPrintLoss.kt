package com.tomppi.enderslicer.printer

/**
 * What a connection loss means for a print the app believed was running.
 *
 * The two routes are not the same machine, and the difference is the whole of this
 * file. The host this app owns is a process on this phone: klippy dying, being
 * restarted by the wedge detector, or losing the printer to a USB re-attach takes the
 * print with it, and the next process starts with no file and nothing to say about it -
 * so the app has to name that loss itself. A host on a computer is reached over the
 * network, and the network is the part that breaks: the phone's websocket closing says
 * nothing whatever about the printer, which is very often still printing.
 *
 * Treating the two the same put "The print was interrupted" on the user's phone five
 * times during one print that never stopped - and left five history records and a
 * lastPrintState of standby behind it, so the next snapshot started a fresh record for
 * the same print.
 *
 * So the drop only ever decides anything for the local route, and even there the print
 * is settled by what the NEXT connection shows: still printing is the same print, a
 * state klippy names for itself is reported by the ordinary transition, and a host with
 * no print at all is the one case the app has to name.
 */
internal object KlipperPrintLoss {
    /** True when a dropped connection is the print dying with it: only the app's own host. */
    fun lostWithTheHost(choice: KlipperHostChoice): Boolean = !choice.isRemote

    /**
     * Whether the app still believes a print is running after the connection dropped.
     *
     * The local host takes the belief with it: the socket dying IS the print dying, and
     * leaving the belief standing would have the reconnect report the same loss a second
     * time. A remote host leaves it standing for exactly the opposite reason - nothing
     * about that drop says the print stopped, and the belief is what the reconnect's
     * first snapshot is read against.
     */
    fun beliefAfterDrop(choice: KlipperHostChoice, wasRunning: Boolean): Boolean =
        wasRunning && choice.isRemote

    /** What the first snapshot after a connection means for a print that was left running. */
    enum class AfterReconnect {
        /**
         * Nothing to say: the print is still printing, or the state klippy reports is one
         * it names for itself - complete, cancelled, error - which the ordinary transition
         * reports with klippy's own words and message.
         */
        Nothing,

        /**
         * The host has no print at all - standby with no filename, which is what a
         * restarted klippy leaves behind - so the print went with the restart. Reported
         * once, as interrupted, with the restart named as the reason.
         */
        HostRestarted,
    }

    fun afterReconnect(
        /** True when the app believed a print was running before the connection was lost. */
        printWasRunning: Boolean,
        /** The state the first snapshot reports, in klippy's own words. */
        printState: String?,
        /** The file that snapshot names, empty when it names none. */
        printFileName: String?,
    ): AfterReconnect {
        if (!printWasRunning) return AfterReconnect.Nothing
        if (printState != "standby") return AfterReconnect.Nothing
        if (!printFileName.isNullOrBlank()) return AfterReconnect.Nothing
        return AfterReconnect.HostRestarted
    }
}

/**
 * Which connection the state on screen belongs to.
 *
 * Every connection takes the next number, and every write to the state checks that its
 * number is still the current one before it lands. A host change closes the old client
 * and opens a new one; a reply that was already on its way from the old host arrives
 * after that, and without this it would overwrite the new host's state with the old
 * host's numbers - which is how the Device tab came to show the PC's cancelled print
 * under the phone's name.
 *
 * A class rather than a bare counter so the rule can be tested without a repository or a
 * socket: the token is current until the next connection is asked for, and never after.
 */
internal class KlipperConnectionGeneration {
    private val current = java.util.concurrent.atomic.AtomicLong(0)

    /** The number for a connection that is starting now. Older tokens stop being current. */
    fun next(): Long = current.incrementAndGet()

    /** True while [token] is still the connection the state should describe. */
    fun isCurrent(token: Long): Boolean = current.get() == token

    /**
     * The connection that is current now, for a reader that starts its own work.
     *
     * The camera list is fetched off to one side of the session loop, and it has to check its
     * answer against the connection it was asked for - so it needs the number that is current
     * at the moment it starts, not a new one.
     */
    fun currentToken(): Long = current.get()
}
