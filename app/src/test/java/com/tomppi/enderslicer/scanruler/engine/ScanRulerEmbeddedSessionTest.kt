package com.tomppi.enderslicer.scanruler.engine

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tomppi.enderslicer.cadagent.AgentToolHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The embedded engine's own lifecycle contracts: a failed or cancelled start releases
 * everything it made, the default workspace is selected once it applied, and the in-app
 * agent works the page's catalogue and wait timeouts exactly as the MCP facade does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScanRulerEmbeddedSessionTest {

    private lateinit var context: Context
    private val sessions = ArrayList<ScanRulerEmbeddedSession>()
    private val pages = ArrayList<FakePage>()
    private val answeredEvaluations = ArrayList<Any>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        pages.forEach { it.close() }
        sessions.forEach { it.close() }
    }

    private fun startSession(log: (String) -> Unit = {}): ScanRulerEmbeddedSession {
        val session = ScanRulerEmbeddedSession(context, CoroutineScope(Dispatchers.Unconfined), log)
        sessions += session
        runBlocking { session.start() }
        return session
    }

    private fun pairPage(
        session: ScanRulerEmbeddedSession,
        commands: JSONArray = FakePage.defaultCommands(),
    ): FakePage {
        val fake = FakePage(session.pageToken).connect(session.state.value.websocketPort)
        pages += fake
        fake.sendJson(fake.hello(commands = commands))
        assertEquals("welcome", JSONObject(fake.awaitText()).keys().next())
        return fake
    }

    /** ShadowWebView records the page call instead of running it; answer the recorded callback. */
    private fun completeEvaluate(session: ScanRulerEmbeddedSession) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val callback = shadowOf(session.webView).lastEvaluatedJavascriptCallback
            if (callback != null && answeredEvaluations.none { it === callback }) {
                answeredEvaluations += callback
                callback.onReceiveValue("null")
                return
            }
            Thread.sleep(10)
        }
        throw AssertionError("the session never asked the page for anything")
    }

    @Test
    fun aFailedStartReleasesTheServersItAlreadyStarted() {
        val squatter = ServerSocket()
        squatter.reuseAddress = true
        squatter.bind(InetSocketAddress("127.0.0.1", ScanRulerPageLink.DEFAULT_PORT))
        try {
            val session = ScanRulerEmbeddedSession(context, CoroutineScope(Dispatchers.Unconfined))
            sessions += session
            runBlocking { session.start() }
            assertEquals(ScanRulerPhase.FAILED, session.state.value.phase)
            assertTrue(session.state.value.message.orEmpty().contains("ScanRuler could not start"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("the failed start destroyed its WebView", shadowOf(session.webView).wasDestroyCalled())
        } finally {
            squatter.close()
        }
        // The asset server the failed start created is gone, so its port binds again.
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(InetSocketAddress("127.0.0.1", ScanRulerAssetServer.DEFAULT_PORT))
        }
    }

    @Test
    fun aCancelledStartRethrowsAfterReleasingTheServers() {
        val job = SupervisorJob()
        val work = AtomicReference<Job?>(null)
        val session = ScanRulerEmbeddedSession(context, CoroutineScope(job), log = { message ->
            // The asset server logs when it is up; cancelling there fails the load below.
            if (message.startsWith("ScanRuler page origin")) work.get()?.cancel()
        })
        sessions += session
        val thrown = runBlocking {
            val start = async { session.start() }
            work.set(start)
            try {
                start.await()
                null
            } catch (error: Throwable) {
                error
            }
        }
        assertTrue("cancellation is propagated, not reported: " + thrown, thrown is CancellationException)
        assertEquals(ScanRulerPhase.IDLE, session.state.value.phase)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the cancelled start destroyed its WebView", shadowOf(session.webView).wasDestroyCalled())
        // The servers the cancelled start made are released, so its port binds again.
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(InetSocketAddress("127.0.0.1", ScanRulerAssetServer.DEFAULT_PORT))
        }
    }

    @Test
    fun aWorkspaceSetThatFailedIsTriedAgainOnTheNextShow() {
        val session = startSession()
        val fake = pairPage(session)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<Boolean> { runBlocking { session.showEmbeddedWorkspace() } }
            val asked = JSONObject(fake.awaitText())
            assertEquals("workspace.set", asked.getString("name"))
            fake.sendJson(JSONObject()
                .put("id", asked.getInt("id"))
                .put("error", JSONObject().put("code", "refused").put("message", "not this workspace")))
            completeEvaluate(session)
            assertTrue(first.get(10, TimeUnit.SECONDS))

            val second = executor.submit<Boolean> { runBlocking { session.showEmbeddedWorkspace() } }
            val retried = JSONObject(fake.awaitText())
            assertEquals("workspace.set", retried.getString("name"))
            fake.sendJson(JSONObject().put("id", retried.getInt("id")).put("result", JSONObject()))
            completeEvaluate(session)
            assertTrue(second.get(10, TimeUnit.SECONDS))

            // Applied means once: the page's own tabs own the workspace from here on.
            val third = executor.submit<Boolean> { runBlocking { session.showEmbeddedWorkspace() } }
            completeEvaluate(session)
            assertTrue(third.get(10, TimeUnit.SECONDS))
            assertNull(
                "the applied workspace is not forced again",
                runCatching { fake.awaitText(500) }.getOrNull(),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun theInAppAgentRunsPageCommandsWithUnderscoresInTheirNames() {
        val session = startSession()
        val fake = pairPage(session, commands = JSONArray().put(JSONObject()
            .put("name", "body.compare_in_deviation")
            .put("input", JSONObject("""{"type":"object","properties":{}}"""))))
        val host = ScanRulerAgentHost(session)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<AgentToolHost.Result> {
                host.call("body_compare_in_deviation", JSONObject())
            }
            val asked = JSONObject(fake.awaitText())
            assertEquals("body.compare_in_deviation", asked.getString("name"))
            fake.sendJson(JSONObject().put("id", asked.getInt("id")).put("result", JSONObject().put("ok", true)))
            val result = future.get(10, TimeUnit.SECONDS)
            assertFalse(result.text, result.failed)
            assertTrue(result.text.contains("\"ok\":true"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun theWaitToolHonorsTheCallersTimeout() {
        val session = startSession()
        val host = ScanRulerAgentHost(session)
        val started = System.currentTimeMillis()
        val result = host.call("scanruler_wait", JSONObject().put("timeoutMs", 300))
        val elapsed = System.currentTimeMillis() - started
        assertTrue("the caller's 300 ms were honored, waited " + elapsed, elapsed >= 150 && elapsed < 5_000)
        assertTrue(result.text.contains("\"ready\":false"))
    }
}
