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
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    @Test
    fun aWriteKeepsTheHostOwnConfigurationBesideItAndRestarts() = runBlocking {
        val served = "# the computer configuration\n[extruder]\nrotation_distance: 4.643\n"
        val uploads = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/server/files/config/printer.cfg") { exchange ->
            val body = served.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/server/files/upload") { exchange ->
            uploads.add(String(exchange.requestBody.readBytes(), Charsets.UTF_8))
            val answer = """{"action":"create_file","item":{"path":"printer.cfg","root":"config"}}"""
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(201, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            KlipperHostChoiceStore(context).save(
                KlipperHostChoice(mode = KlipperHostMode.PC, host = "127.0.0.1", port = server.address.port),
            )
            val repository = KlipperPrinterRepository(context, scope)
            assertTrue("the write went through", repository.saveRotationDistance(4.700))
            assertTrue(
                "the host own configuration is kept on the host, not only on the phone",
                uploads.any { it.contains("previous.printer.cfg") },
            )
            assertTrue("with what was there", uploads.any { it.contains("rotation_distance: 4.643") })
            assertTrue("and the new value", uploads.any { it.contains("rotation_distance: 4.700") })
            // The restart itself is a G-code RESTART over the transport, not this HTTP path;
            // MoonrakerFiles.restart() is the other one and is not what a write uses.
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun anUnreachableHostIsNotWrittenTo() = runBlocking {
        val uploads = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/server/files/config/printer.cfg") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.createContext("/server/files/upload") { exchange ->
            uploads.incrementAndGet()
            val bytes = "{}".toByteArray()
            exchange.sendResponseHeaders(201, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            KlipperHostChoiceStore(context).save(
                KlipperHostChoice(mode = KlipperHostMode.PC, host = "127.0.0.1", port = server.address.port),
            )
            val repository = KlipperPrinterRepository(context, scope)
            assertFalse("a host that cannot be read is not written to", repository.saveRotationDistance(4.700))
            assertEquals("and nothing was uploaded", 0, uploads.get())
        } finally {
            server.stop(0)
        }
    }
}
