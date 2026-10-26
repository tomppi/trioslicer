package com.tomppi.enderslicer.cadagent

import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The turn's keeper: a foreground service that pins the process for one turn, with a bounded
 * partial wake lock released on every stop, and no screen-on flag anywhere.
 *
 * Robolectric runs the real Service lifecycle and its own PowerManager, so the lock is observed
 * as the service holds it - acquired with a timeout when the turn starts, released on destroy.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InAppAgentTurnServiceTest {

    @Test
    fun theKeeperHoldsItsBoundedWakeLockForTheTurnAndReleasesItWhenStopped() {
        val controller = Robolectric.buildService(InAppAgentTurnService::class.java)
        val service = controller.create().get()
        service.onStartCommand(null, 0, 1)

        assertTrue("a turn holds a partial wake lock", service.isWakeLockHeld())
        val notification: Notification? = shadowOf(service).lastForegroundNotification
        assertNotNull("the turn is a foreground service, so it has a notification", notification)
        assertEquals(
            "CAD agent working",
            notification!!.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )

        controller.destroy()
        assertFalse("stopping the turn releases the lock", service.isWakeLockHeld())
    }

    @Test
    fun beginningAndEndingATurnStartsAndStopsTheKeeperService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = shadowOf(context as Application)

        InAppTurnKeeper.begin(context)
        val started = app.nextStartedService
        assertNotNull("the turn requests the keeper", started)
        assertEquals(
            InAppAgentTurnService::class.java.name,
            started!!.component?.className,
        )

        InAppTurnKeeper.end(context)
        val stopped = app.nextStoppedService
        assertNotNull("the end of the turn stops the keeper", stopped)
        assertEquals(
            InAppAgentTurnService::class.java.name,
            stopped!!.component?.className,
        )
    }
}
