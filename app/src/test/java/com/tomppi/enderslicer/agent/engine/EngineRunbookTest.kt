package com.tomppi.enderslicer.agent.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phone agent's runbooks are the repository's skill documents, staged into assets/agent by
 * :app:copyAgentRunbook, plus the live rules only the device agent needs.
 *
 * Host-side agents read docs/skills through the harness skill catalog. The in-app agent cannot
 * open a file on the computer, so the same documents travel in the APK - and these tests fail
 * the build if the two deliveries drift or the measured rules fall out of the phone prompt.
 */
class EngineRunbookTest {

    private val assets = File("src/main/assets")
    private val skills = File("../docs/skills")

    /** The repository skill each staged APK document is a byte copy of. */
    private fun repositoryDocument(assetPath: String): File = when {
        assetPath.startsWith("engine-runbooks/") -> File(skills, assetPath.removePrefix("engine-runbooks/"))
        assetPath == "agent/solidcraft-engine.md" -> File(skills, "solidcraft-engine.md")
        else -> error("unknown runbook asset: " + assetPath)
    }

    @Test
    fun everyDocumentIsTheRepositorySkillDocument() {
        for (engine in listOf(AgentEngine.CAD, AgentEngine.BLENDER, AgentEngine.SOLIDCRAFT)) {
            for (assetPath in EngineRunbook.documents(engine)) {
                val asset = File(assets, assetPath)
                val skill = repositoryDocument(assetPath)
                assertTrue(engine.label + ": " + assetPath + " is missing from the repository skills", skill.isFile)
                assertTrue(engine.label + ": " + assetPath + " is missing from the APK assets", asset.isFile)
                assertEquals(
                    "the in-app runbook and the external skill drifted: " + assetPath,
                    skill.readText(),
                    asset.readText(),
                )
            }
        }
    }

    @Test
    fun theCadAgentGetsBothItsDocuments() {
        assertEquals(
            listOf(
                "engine-runbooks/cad-engine.md",
                "engine-runbooks/occt-engine-rules.md",
            ),
            EngineRunbook.documents(AgentEngine.CAD),
        )
        assertTrue(File(assets, "engine-runbooks/cad-engine.md").readText().contains("CAD Engine"))
        assertTrue(
            File(assets, "engine-runbooks/occt-engine-rules.md").readText().contains("OCCT engine rules"),
        )
    }

    @Test
    fun theCadLiveRulesCarryTheRulesTheEngineMeasured() {
        val rules = EngineRunbook.liveRules(AgentEngine.CAD)
        assertTrue("the agent is already connected", rules.contains("already yours"))
        assertTrue("no host connection exists", rules.contains("no bridge, no port, no token, no computer"))
        assertTrue("the plate is the user's", rules.contains("plate is the user's viewport"))
        assertTrue("the 300 s gate", rules.contains("answered after 300 s"))
        assertTrue("no cancel", rules.contains("no cancel"))
        assertTrue("never loop point-in-solid", rules.contains("Never loop point-in-solid"))
        assertTrue("the boolean trap", rules.contains("Verify every boolean changed"))
        assertTrue("describe_at is a pick helper", rules.contains("pick helper"))
        assertTrue("prints are the return", rules.contains("returns prints only"))
        assertTrue("no resend after a timeout", rules.contains("do not resend it"))
    }

    @Test
    fun theBlenderLiveRulesSayTheScenePersistsAndThePlateIsTheHandoff() {
        val rules = EngineRunbook.liveRules(AgentEngine.BLENDER)
        assertTrue(rules.contains("scene stays alive"))
        assertTrue(rules.contains("export_stl"))
        assertTrue(rules.contains("hot-loads onto the plate"))
        assertTrue(rules.contains("Do not slice here"))
    }

    @Test
    fun theSystemPromptIsTheLiveRulesThenTheDocuments() {
        val prompt = EngineRunbook.systemPrompt(AgentEngine.CAD, "RUNBOOK-MARKER")
        assertTrue(prompt.startsWith(EngineRunbook.liveRules(AgentEngine.CAD)))
        assertTrue(prompt.contains("RUNBOOK-MARKER"))
        assertTrue(prompt.indexOf("RUNBOOK-MARKER") > prompt.indexOf("already yours"))
        assertTrue("the reference explains the source", prompt.contains("tool-surface reference"))
    }

    @Test
    fun thePlateAgentKeepsItsOwnRunbookAndThePageOwnsScanRuler() {
        assertEquals(listOf("agent/solidcraft-engine.md"), EngineRunbook.documents(AgentEngine.SOLIDCRAFT))
        assertTrue(EngineRunbook.documents(AgentEngine.SCANRULER).isEmpty())
    }
}
