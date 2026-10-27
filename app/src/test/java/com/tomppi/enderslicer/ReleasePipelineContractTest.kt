package com.tomppi.enderslicer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release pipeline is part of the product: the broken releases of 2026-10-26 (1.9.5 without
 * the SolidCraft engine, main and 1.9.8 blocked by a flaky unit test, v1.9.5 tagged off main)
 * all came from here, not from the app. These assertions are coarse on purpose - they pin the
 * guards the audit found missing, in the files that carry them, so a later edit cannot quietly
 * remove one. The workflow itself is validated by GitHub on every push; this test is the JVM
 * suite's half of the contract.
 */
class ReleasePipelineContractTest {

    private val workflow = repoFile(".github/workflows/build.yml")
    private val appBuild = repoFile("app/build.gradle.kts")
    private val fetchScript = repoFile("scripts/fetch-solidcraft-engine-android.sh")
    private val solidcraftDoc = repoFile("docs/SOLIDCRAFT_ANDROID.md")
    private val changelog = repoFile("CHANGELOG.md")

    @Test
    fun aReleaseTagMustBeOnMainAndMatchTheVersion() {
        assertTrue("full history is fetched for the ancestry check", workflow.contains("fetch-depth: 0"))
        assertTrue("the tagged commit must be on main", workflow.contains("merge-base --is-ancestor"))
        assertTrue("the tag must match versionName", workflow.contains("does not match versionName"))
        assertTrue("the changelog must carry the version", workflow.contains("CHANGELOG.md has no"))
        assertTrue("the check is a workflow step", workflow.contains("Validate the release tag"))
    }

    @Test
    fun aMissingSigningSecretFailsReleaseRefsButNotBranchBuilds() {
        assertTrue("release refs are recognised", workflow.contains("RELEASE_BUILD_REF=1"))
        assertTrue(
            "a release ref without the secret fails instead of signing with a throwaway key",
            workflow.contains("refusing to sign a release APK with a throwaway key"),
        )
        assertTrue(
            "branch and pull-request builds keep the warning and the throwaway key",
            workflow.contains("(branch/pull-request build): signing with a throwaway key"),
        )
        assertTrue(
            "a throwaway-signed APK is never uploaded as a release artifact",
            workflow.contains("env.RELEASE_KEY_IS_REAL == '1' || env.RELEASE_BUILD_REF != '1'"),
        )
    }

    @Test
    fun theSolidCraftEngineGateChecksAarch64() {
        assertTrue("the Gradle task reads the ELF header", appBuild.contains("fun isAarch64Elf"))
        assertTrue("EM_AARCH64 is 0xB7", appBuild.contains("0xB7"))
        assertTrue("the CI fetch step checks e_machine", workflow.contains("b700"))
        assertTrue("the CI fetch step names the architecture", workflow.contains("AArch64 ELF"))
        assertTrue("the fetch script checks e_machine too", fetchScript.contains("e_machine bytes"))
    }

    @Test
    fun artifactsAreNamedPerRefAndEngineTagsSkipTheAppPipeline() {
        assertTrue("artifacts are suffixed with the ref", workflow.contains("BUILD_ARTIFACT_SUFFIX"))
        assertTrue("the APK name carries the ref", workflow.contains("enderslicercura-apk-"))
        assertTrue("engine tags do not build the app", workflow.contains("startsWith(github.ref_name, 'v')"))
    }

    @Test
    fun theDocsCarryTheRaisedAgentCapsAndTheNoRoundLimitRule() {
        assertTrue("ten search results", solidcraftDoc.contains("at most 10 results"))
        assertTrue("2,000-character snippets", solidcraftDoc.contains("2,000 characters"))
        assertTrue("32,000-character fetched page", solidcraftDoc.contains("32,000"))
        assertTrue("1,536 px plate render", solidcraftDoc.contains("1,536 px"))
        assertFalse("the old render size is gone", solidcraftDoc.contains("768 px"))
        assertFalse("the removed round limit is gone", solidcraftDoc.contains("8 tool rounds"))
        assertFalse("the old snippet cap is gone", solidcraftDoc.contains("~800 characters"))
        assertFalse("the old fetch cap is gone", solidcraftDoc.contains("~8k characters"))
    }

    @Test
    fun theChangelogRecordsTheWithdrawnVersions() {
        assertTrue(
            "1.9.8 and 1.9.9 were built but never released",
            changelog.contains("1.9.8 and 1.9.9 were built but never released"),
        )
        assertTrue("this fix round has its own section", changelog.contains("## [1.9.11]"))
    }

    /** A repository file, from Gradle's app-module working directory or the repository root. */
    private fun repoFile(relative: String): String {
        val candidates = listOf(File(relative), File("../" + relative))
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to find repository file " + relative + " (looked in " + candidates + ")")
        return file.readText()
    }
}
