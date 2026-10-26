package com.tomppi.enderslicer.cadagent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The plastic-parts half of the runbook, pinned to the shipped asset.
 *
 * The in-app agent's tools are the engine's catalogue, so the runbook is the only place the phone
 * agent learns that `plastic.*`, `mesh.convert`, `solid.thread`/`rib`/`web`/`emboss`,
 * `inspect.*`, `timeline.rollback`/`suppress`, `material.*` and `surface.*` exist and when to use
 * them. A missing section or a renamed command id silently takes that half of the engine away
 * again, so this fails the build instead. `:app:copyAgentRunbook` stages the asset from
 * docs/skills/solidcraft-engine.md before the unit tests run, so this checks what the APK ships.
 */
class AgentRunbookPlasticTest {

    private val asset = File("src/main/assets/agent/solidcraft-engine.md").readText()

    @Test
    fun thePlasticSectionIsInTheShippedRunbook() {
        assertTrue(
            "the runbook lost its plastic section",
            asset.contains("## 7. Plastic parts and the other half of the engine"),
        )
        assertTrue("the snap-fit recipe is missing", asset.contains("plastic.snap_fit"))
        assertTrue("the measured rule fields are missing", asset.contains("nominal_radius"))
    }

    @Test
    fun theRunbookNamesEveryCommandIdTheHalfCovers() {
        val ids = listOf(
            "plastic.snap_fit", "plastic.boss", "plastic.lip", "plastic.rest",
            "plastic.rules", "plastic.manage_rules", "plastic.assign_rule",
            "mesh.convert",
            "solid.thread", "solid.rib", "solid.web", "solid.emboss",
            "inspect.minimum_radius", "inspect.draft", "inspect.clearance", "inspect.interference",
            "timeline.rollback", "timeline.suppress",
            "material.create", "material.assign",
            "surface.thicken",
            "joint.create", "assembly.bom", "config.table",
        )
        for (id in ids) {
            assertTrue("the runbook stopped naming $id", asset.contains(id))
        }
    }

    @Test
    fun theRunbookKeepsTheRulesThatBoundThoseFamilies() {
        assertTrue(
            "a CAD document must prefer the engine's plastic features over the plate's snap panel",
            asset.contains("snap panel") && asset.contains("imported mesh"),
        )
        assertTrue(
            "the engine's screenshot/render commands must stay refused",
            asset.contains("screenshot") && asset.contains("render.*"),
        )
        assertTrue(
            "sheet metal must be named out of scope",
            asset.contains("sheet.*") && asset.contains("out of scope"),
        )
    }
}
