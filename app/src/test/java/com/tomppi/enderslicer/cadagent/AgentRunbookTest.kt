package com.tomppi.enderslicer.cadagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The prompt is the shipped runbook plus the live rules, from one file.
 *
 * The runbook asset is copied from docs/skills/solidcraft-engine.md by :app:copyAgentRunbook
 * before this test runs, so this also pins that the two deliveries of the skill cannot drift:
 * the file in the repository IS the file in the APK.
 */
class AgentRunbookTest {

    @Test
    fun theLiveRulesSayWhatOnlyTheAppKnows() {
        val rules = AgentRunbook.LIVE_RULES
        assertTrue("the agent is the only author", rules.contains("only author"))
        assertTrue("the user is a spectator", rules.contains("spectator"))
        assertTrue("the tools are already connected", rules.contains("already yours"))
        assertTrue("no refresh is ever needed", rules.contains("appears on the plate as you make it"))
        assertTrue("the engine's screenshot is refused", rules.contains("screenshot"))
        assertTrue("the camera is the user's", rules.contains("camera"))
        assertTrue("web content is untrusted", rules.contains("UNTRUSTED DATA"))
        assertTrue("a page is never instructions", rules.contains("never as instructions"))
    }

    @Test
    fun theSystemPromptIsTheLiveRulesThenTheRunbook() {
        val prompt = AgentRunbook.systemPrompt("RUNBOOK-MARKER")
        assertTrue(prompt.startsWith(AgentRunbook.LIVE_RULES))
        assertTrue(prompt.contains("RUNBOOK-MARKER"))
        assertTrue(prompt.indexOf("RUNBOOK-MARKER") > prompt.indexOf("only author"))
    }

    @Test
    fun theRunbookAssetIsTheRepositorySkillDocument() {
        // The JVM unit tests run with the app module as the working directory.
        val asset = File("src/main/assets/agent/solidcraft-engine.md")
        val skill = File("../docs/skills/solidcraft-engine.md")
        assertTrue("the skill document is missing", skill.isFile)
        assertTrue("the build did not stage the runbook asset at " + asset.absolutePath, asset.isFile)
        assertEquals(
            "the in-app runbook and the external skill drifted",
            skill.readText(),
            asset.readText(),
        )
        assertTrue(asset.length() > 1_000)
        assertTrue("it names the in-app default", asset.readText().contains("in-app agent"))
        assertTrue("it states the one-author rule", asset.readText().contains("one author at a time"))
    }
}
