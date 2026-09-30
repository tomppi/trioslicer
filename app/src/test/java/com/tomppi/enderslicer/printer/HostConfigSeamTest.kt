package com.tomppi.enderslicer.printer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where a configuration operation reads from, against a host that answers like Moonraker.
 *
 * The rule itself is covered in ConfigHostRuleTest. This exercises the seam that applies it,
 * because the bug that reached a release was not the rule but a `?:` at the call site: a
 * computer whose configuration could not be read fell back to this device's own file, and that
 * file was then uploaded over the computer's configuration with a restart behind it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HostConfigSeamTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(Dispatchers.IO)

    @Before
    fun makeTheDeviceDirectory() {
        // The app creates this when its own host starts; nothing else does.
        KlipperHostFiles.directory(context.filesDir).mkdirs()
    }

    @Test
    fun theComputerAnswerIsTheSourceWhenAComputerIsSet() = runBlocking {
        val served = "# the computer configuration\n[printer]\nmax_accel: 3000\n"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/server/files/config/printer.cfg") { exchange ->
            val body = served.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            KlipperHostChoiceStore(context).save(
                KlipperHostChoice(mode = KlipperHostMode.PC, host = "127.0.0.1", port = server.address.port),
            )
            // This device has its own file and it is different: it must not be the answer.
            KlipperHostFiles.config(context.filesDir).writeText("# this device configuration\n")

            val repository = KlipperPrinterRepository(context, scope)
            assertEquals(served, repository.readConfigFile())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aComputerThatCannotBeReadIsNotReplacedByTheDeviceFile() = runBlocking {
        val asked = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/server/files/config/printer.cfg") { exchange ->
            asked.incrementAndGet()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            KlipperHostChoiceStore(context).save(
                KlipperHostChoice(mode = KlipperHostMode.PC, host = "127.0.0.1", port = server.address.port),
            )
            KlipperHostFiles.config(context.filesDir).writeText("# this device configuration\n")

            val repository = KlipperPrinterRepository(context, scope)
            assertNull("a failed read must not hand back this device own file", repository.readConfigFile())
            assertTrue("the computer was actually asked", asked.get() > 0)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun withNoComputerTheDeviceFileIsTheSource() = runBlocking {
        KlipperHostChoiceStore(context).save(KlipperHostChoice(mode = KlipperHostMode.DEVICE))
        val device = "# this device configuration\n"
        KlipperHostFiles.config(context.filesDir).writeText(device)

        val repository = KlipperPrinterRepository(context, scope)
        assertEquals(device, repository.readConfigFile())
    }
}
