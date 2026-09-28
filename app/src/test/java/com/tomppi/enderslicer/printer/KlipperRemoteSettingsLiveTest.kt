package com.tomppi.enderslicer.printer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether the settings really work from here to a host, checked in code rather than by looking.
 *
 * Skipped unless KLIPPER_REMOTE_HOST names the machine running klippy:
 *
 *   KLIPPER_REMOTE_HOST=192.168.3.212 ./gradlew :app:testDebugUnitTest --tests
 *       "com.tomppi.enderslicer.printer.KlipperRemoteSettingsLiveTest"
 *
 * It answers the question a screenshot cannot: is the configuration the app reads, changes and
 * writes the one the printer is actually running? Three values are compared - the extruder
 * rotation distance and two limits - as the file has them and as klippy reports them. Then the
 * same file is sent back, unchanged, to prove the write path lands: a non-destructive round trip,
 * so this is safe against a printer that is set up and working.
 */
class KlipperRemoteSettingsLiveTest {
    private val host: String? = System.getenv("KLIPPER_REMOTE_HOST")

    @Test
    fun theHostsConfigurationIsTheOneThePrinterRuns() {
        assumeTrue("set KLIPPER_REMOTE_HOST to run this", !host.isNullOrBlank())
        val files = MoonrakerFiles(host!!.trim())
        val text = files.configText()
        assertNotNull("the host answered with its configuration", text)

        val running = JSONObject(get("http://" + host.trim() + ":7125/printer/objects/query?configfile"))
            .getJSONObject("result")
            .getJSONObject("status")
            .getJSONObject("configfile")
            .getJSONObject("settings")

        for ((section, option) in listOf(
            "extruder" to "rotation_distance",
            "printer" to "max_accel",
            "printer" to "square_corner_velocity",
        )) {
            val inFile = optionIn(text!!, section, option)
            val inKlippy = running.optJSONObject(section)?.optDouble(option)
            assertNotNull("the file has " + section + "." + option, inFile)
            assertNotNull("klippy reports " + section + "." + option, inKlippy)
            assertEquals(
                section + "." + option + ": what the app writes is what the printer runs",
                inKlippy!!,
                inFile!!.toDouble(),
                1e-6,
            )
        }

        val staged = File.createTempFile("printer", ".cfg")
        staged.writeText(text!!)
        assertTrue("the host took its own configuration back", files.uploadConfig(staged))
        assertEquals("and it is the same file it was", text, files.configText())
        staged.delete()
    }

    /** An option as klippy would read it: uncommented, in the section, last one wins. */
    private fun optionIn(text: String, section: String, option: String): String? {
        var current = ""
        var value: String? = null
        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (current.equals(section, ignoreCase = true)) break
                current = trimmed.substring(1, trimmed.length - 1).trim()
                continue
            }
            if (!current.equals(section, ignoreCase = true)) continue
            if (trimmed.startsWith("#") || trimmed.isEmpty()) continue
            val name = trimmed.substringBefore(":").substringBefore("=").trim()
            if (name.equals(option, ignoreCase = true)) {
                value = trimmed.substringAfter(":").substringAfter("=").trim()
            }
        }
        return value?.takeIf { it.isNotEmpty() }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 15000
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}
