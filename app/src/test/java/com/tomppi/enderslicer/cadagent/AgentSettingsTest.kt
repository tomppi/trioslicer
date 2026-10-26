package com.tomppi.enderslicer.cadagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.data.AppStateStore
import com.tomppi.enderslicer.storage.SealedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The in-app endpoint's settings, and the key at rest.
 *
 * The Keystore itself is Android's, so the sealing is injected here: a store built with a fake
 * sealer proves what the store does with the value - writes the sealed form only, opens it for a
 * run, reports it as a mask, and keeps it out of every toString. The real arrangement is
 * [SealedValue], the same object the Moonraker key uses.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentSettingsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun preferences() = context.getSharedPreferences(
        AppStateStore.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Before
    fun clearPreferences() {
        preferences().edit().clear().commit()
    }

    /**
     * A reversible stand-in for the Keystore: it marks the value, so the test can prove the
     * plaintext never reaches the preference file.
     */
    private fun fakeStore(): AgentSettingsStore {
        val prefix = SealedValue.FORMAT_PREFIX
        return AgentSettingsStore(
            preferences = preferences(),
            seal = { value -> prefix + java.util.Base64.getEncoder().encodeToString(value.toByteArray()) },
            open = { stored ->
                if (!stored.startsWith(prefix)) null else runCatching {
                    java.util.Base64.getDecoder().decode(stored.removePrefix(prefix)).toString(Charsets.UTF_8)
                }.getOrNull()
            },
        )
    }

    @Test
    fun theShippedDefaultsAreDeepSeeksEndpointAndModelWithNoKey() {
        val settings = fakeStore().loadSettings()
        assertEquals(AgentMode.IN_APP, settings.mode)
        assertEquals("https://api.deepseek.com", settings.baseUrl)
        assertEquals("deepseek-chat", settings.model)
        assertEquals("not set", settings.keyMask)
        assertFalse(settings.hasApiKey)
        assertTrue("an endpoint plus a model is enough to call", settings.isConfigured)
        assertTrue("search is on by default", settings.webSearch)
    }

    @Test
    fun theKeyIsStoredSealedAndComesBackOnlyForARun() {
        val store = fakeStore()
        val saved = AgentSettings(
            baseUrl = "https://api.example.test/v1",
            model = "some-model",
            webSearch = false,
        )
        assertTrue(store.save(saved, "sk-the-secret-key"))

        val stored = preferences().getString(AgentSettingsStore.KEY_API_KEY, null).orEmpty()
        assertTrue("the value at rest is sealed", stored.startsWith(SealedValue.FORMAT_PREFIX))
        assertFalse("the plaintext is never written", stored.contains("sk-the-secret-key"))

        val loaded = store.load()
        assertEquals("sk-the-secret-key", loaded.apiKey)
        assertEquals("some-model", loaded.settings.model)
        assertFalse("the search switch persisted", loaded.settings.webSearch)
        assertTrue(loaded.settings.hasApiKey)
        assertEquals(AgentSettings.MASKED_KEY, loaded.settings.keyMask)

        // Nothing renderable or printable carries the key.
        val renderable = loaded.settings.toString()
        assertFalse(renderable.contains("sk-the-secret-key"))
        assertFalse(loaded.toString().contains("sk-the-secret-key"))
        assertTrue(loaded.toString().contains("present"))
    }

    @Test
    fun anEmptyKeyFieldKeepsTheSavedKeyAndAnEmptyStringClearsIt() {
        val store = fakeStore()
        store.save(AgentSettings(model = "m"), "sk-keep-me")
        // Saving the endpoint with a null key (the sheet's field left empty) keeps the key.
        store.save(AgentSettings(model = "m2"), null)
        assertEquals("sk-keep-me", store.load().apiKey)
        assertEquals("m2", store.loadSettings().model)
        // Saving with an explicit empty string clears it.
        store.save(AgentSettings(model = "m2"), "")
        assertEquals("", store.load().apiKey)
        assertFalse(store.loadSettings().hasApiKey)
    }

    @Test
    fun clearApiKeyRemovesItWithoutTouchingTheEndpoint() {
        val store = fakeStore()
        store.save(AgentSettings(baseUrl = "http://127.0.0.1:8080/v1", model = "local"), "sk-secret")
        assertTrue(store.clearApiKey())
        assertEquals("", store.load().apiKey)
        val settings = store.loadSettings()
        assertEquals("http://127.0.0.1:8080/v1", settings.baseUrl)
        assertEquals("local", settings.model)
    }

    @Test
    fun aSealedBlobThisDeviceCannotOpenIsNotSentAsTheKey() {
        // A restored backup or a rotated Keystore entry: the blob is not the key. Empty is the
        // honest answer; sending the blob earns a 401 and re-sealing it would destroy it.
        preferences().edit()
            .putString(AgentSettingsStore.KEY_API_KEY, SealedValue.FORMAT_PREFIX + "AAAA")
            .commit()
        val store = AgentSettingsStore(
            preferences = preferences(),
            seal = { "unused" },
            open = { null },
        )
        assertEquals("", store.load().apiKey)
        assertTrue("the key is still reported as stored, so the sheet can ask again", store.loadSettings().hasApiKey)
    }

    @Test
    fun theModeSurvivesTheNextRead() {
        val store = fakeStore()
        assertTrue(store.saveMode(AgentMode.HARNESS))
        assertEquals(AgentMode.HARNESS, fakeStore().loadSettings().mode)
        assertTrue(store.saveMode(AgentMode.IN_APP))
        assertEquals(AgentMode.IN_APP, fakeStore().loadSettings().mode)
    }

    @Test
    fun aBaseUrlThatIsNotAUrlIsNamed() {
        assertEquals(null, AgentSettings(baseUrl = "https://api.deepseek.com").baseUrlProblem())
        assertTrue(AgentSettings(baseUrl = "").baseUrlProblem().orEmpty().contains("base URL"))
        assertTrue(AgentSettings(baseUrl = "ftp://example.com").baseUrlProblem().orEmpty().contains("http"))
        assertFalse(AgentSettings(baseUrl = "not a url").isConfigured)
    }

    @Test
    fun theMaskedKeyIsTheOnlyRenderingOfTheKey() {
        val settings = AgentSettings(hasApiKey = true)
        assertEquals(AgentSettings.MASKED_KEY, settings.keyMask)
        assertFalse(settings.drivingSentence().contains("secret"))
        assertTrue(settings.drivingSentence().contains("this phone"))
    }
}
