package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBedMeshInjectorTest {
    private val envelope = PrinterEnvelope(
        widthMm = 220.0, depthMm = 220.0, heightMm = 250.0,
        buildPlateShape = "rectangular", originAtCenter = false,
    )
    private val centeredEnvelope = envelope.copy(originAtCenter = true)

    @Test
    fun ublStartGcodeGetsC29AreaAndProbeBeforeActivation() {
        val file = temporaryGcode(
            ";FLAVOR:Marlin\n" +
                "G28\n" +
                "G29 L0 ; load mesh\n" +
                "G29 A  ; activate UBL\n" +
                ";LAYER:0\n" +
                "G1 X10 Y20 Z0.28 F1200\n" +
                "G1 X60 Y70 E0.5\n" +
                ";LAYER:1\n" +
                "G1 X60 Y70 Z0.48 E0.5\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        val text = file.readText()
        val activation = text.indexOf("G29 A  ; activate UBL")
        assertTrue("C29 area must come before the activation", text.indexOf("C29 L5 R65 F15 B75 X6 Y6") in 0 until activation)
        assertTrue("the probe must come before the activation", text.indexOf("G29 P1 ; probe only the model area") in 0 until activation)
        assertFalse("case with an activation must not add its own", text.contains("M420 S1 ; activate leveling"))
        assertTrue(text.contains(AdaptiveBedMeshInjector.MARKER))
        assertFalse("second injection must be idempotent", AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
    }

    @Test
    fun m420S1StartGcodeKeepsTheExistingActivation() {
        val file = temporaryGcode(
            ";FLAVOR:Marlin\n" +
                "G28\n" +
                "M420 S1\n" +
                ";LAYER:0\n" +
                "G1 X30 Y40 Z0.2\n" +
                "G1 X90 Y100 E0.4\n" +
                ";LAYER:1\n" +
                "G1 X90 Y100 Z0.4 E0.4\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 2.0))
        val text = file.readText()
        assertFalse("no duplicate M420 when the start script already activates", text.contains("M420 S1 ; activate leveling"))
        assertTrue(text.contains("C29 L28 R92 F38 B102 X6 Y6 ; AML mesh area"))
        assertTrue("the probe must come before the existing M420", text.indexOf("G29 P1 ; probe only the model area") < text.indexOf("M420 S1"))
    }

    @Test
    fun activationBeforeProbeStillGetsActivationAfterProbe() {
        // M420 S1 runs first, then C29 disables leveling and the probe runs; the
        // injected command must re-activate AFTER the probe or leveling stays off.
        val file = temporaryGcode(
            "G28\n" +
                "M420 S1\n" +
                "G29 P1 ; probe\n" +
                ";LAYER:0\n" +
                "G1 X10 Y20 E0.3\n" +
                ";LAYER:1\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        val text = file.readText()
        assertEquals(1, "G29 P1".toRegex().findAll(text).count())
        assertTrue("C29 must be right before the existing probe", text.indexOf("C29 L5 R15 F15 B25 X6 Y6") < text.indexOf("G29 P1 ; probe"))
        val activation = text.indexOf("M420 S1 ; activate leveling")
        assertTrue("leveling must be reactivated after the probe", text.indexOf("G29 P1 ; probe") < activation)
        assertTrue("the original pre-probe M420 stays", text.indexOf("M420 S1\n") < text.indexOf("G29 P1 ; probe"))
    }

    @Test
    fun probeWithoutActivationGetsActivationAfterProbe() {
        val file = temporaryGcode(
            "G28\n" +
                "G29 P1 ; probe\n" +
                ";LAYER:0\n" +
                "G1 X10 Y20 E0.3\n" +
                ";LAYER:1\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        val text = file.readText()
        assertTrue("C29 must come before the existing probe", text.indexOf("C29 L5 R15 F15 B25 X6 Y6") < text.indexOf("G29 P1 ; probe"))
        assertTrue("leveling must be activated after the probe", text.indexOf("G29 P1 ; probe") < text.indexOf("M420 S1 ; activate leveling"))
    }

    @Test
    fun anchorLineStrippedC29StillGetsTheBlock() {
        // The anchor is the line AFTER the last G28, which here is the stripped
        // C29 A itself - the injected block must still be written.
        val file = temporaryGcode(
            "G28\n" +
                "C29 A ; use AML\n" +
                ";LAYER:0\n" +
                "G1 X10 Y20 E0.3\n" +
                ";LAYER:1\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        val text = file.readText()
        assertFalse("the useless C29 A must be removed", text.contains("C29 A"))
        assertTrue("the block must be present", text.contains("C29 L5 R15 F15 B25 X6 Y6 ; AML mesh area"))
        assertTrue(text.contains("G29 P1 ; probe only the model area"))
        assertTrue(text.contains("M420 S1 ; activate leveling"))
    }

    @Test
    fun nonPhaseOneG29IsNotTreatedAsTheProbe() {
        val file = temporaryGcode(
            "G28\n" +
                "G29 P2 ; manual probe phase\n" +
                ";LAYER:0\n" +
                "G1 X10 Y20 E0.3\n" +
                ";LAYER:1\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        val text = file.readText()
        assertTrue(text.contains("G29 P1 ; probe only the model area"))
        assertTrue("manual phase must not be stripped or counted", text.contains("G29 P2 ; manual probe phase"))
        assertTrue(text.contains("M420 S1 ; activate leveling"))
    }

    @Test
    fun missingActivationEmitsAreaProbeAndActivationAfterHome() {
        val file = temporaryGcode(
            "G28\n" +
                ";LAYER:0\n" +
                "G1 X10 Y10 E0.3\n" +
                ";LAYER:1\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        val text = file.readText()
        assertTrue(text.contains("M420 S1 ; activate leveling"))
        val home = text.indexOf("G28")
        assertTrue("block must come after G28", home in 0 until text.indexOf("C29 L5"))
        assertTrue("C29 before probe", text.indexOf("C29 L5 R15 F5 B15 X6 Y6") < text.indexOf("G29 P1 ; probe only the model area"))
        assertTrue("probe before activation", text.indexOf("G29 P1 ; probe only the model area") < text.indexOf("M420 S1 ; activate leveling"))
    }

    @Test
    fun boundsAreClampedToTheBuildVolume() {
        val file = temporaryGcode(
            "G28\n" +
                ";LAYER:0\n" +
                "G1 X-5 Y215 Z0.2\n" +
                "G1 X50 Y218 E0.3\n" +
                ";LAYER:1\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 10.0))
        val text = file.readText()
        assertTrue(text.contains("C29 L0 R60 F205 B220 X6 Y3 ; AML mesh area"))
    }

    @Test
    fun centerOriginBoundsAreConvertedToBedCoordinates() {
        val file = temporaryGcode(
            "G28\n" +
                ";LAYER:0\n" +
                "G1 X-5 Y-5 Z0.2\n" +
                "G1 X50 Y20 E0.3\n" +
                ";LAYER:1\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, centeredEnvelope, 5.0))
        val text = file.readText()
        // -5..50 x -> 105..160 abs; -5..20 y -> 105..130 abs; +/- 5 margin
        assertTrue(text.contains("C29 L100 R165 F100 B135 X6 Y4 ; AML mesh area"))
    }

    @Test
    fun prusaLayerMarkersWorkTheSame() {
        val file = temporaryGcode(
            ";LAYER_CHANGE\n" +
                ";Z:0.2\n" +
                "G1 X20 Y30 F1200\n" +
                "G1 X80 Y90 E0.8\n" +
                ";LAYER_CHANGE\n" +
                ";Z:0.4\n" +
                "G1 X80 Y90 E0.8\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        val text = file.readText()
        assertTrue(text.contains("C29 L15 R85 F25 B95 X6 Y6 ; AML mesh area"))
        assertTrue(text.contains("G29 P1 ; probe only the model area"))
        assertTrue(text.contains("M420 S1 ; activate leveling"))
    }

    /**
     * OrcaSlicer picks the layer envelope by vendor: a Bambu Lab printer gets
     * "; CHANGE_LAYER", every other vendor the PrusaSlicer ";LAYER_CHANGE". The
     * injector knew only the latter, so a Bambu-envelope slice silently got no
     * AML block at all.
     */
    @Test
    fun bambuLayerMarkersWorkTheSame() {
        val file = temporaryGcode(
            "; CHANGE_LAYER\n" +
                "; Z_HEIGHT: 0.2\n" +
                "G1 X20 Y30 F1200\n" +
                "G1 X80 Y90 E0.8\n" +
                "; CHANGE_LAYER\n" +
                "; Z_HEIGHT: 0.4\n" +
                "G1 X80 Y90 E0.8\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        val text = file.readText()
        assertTrue(text.contains("C29 L15 R85 F25 B95 X6 Y6 ; AML mesh area"))
        assertTrue(text.contains("G29 P1 ; probe only the model area"))
        assertTrue(text.contains("M420 S1 ; activate leveling"))
    }

    @Test
    fun c29AreaLicensedForPreviewAndPublishedAndNozzlePathParsesIt() {
        val file = temporaryGcode(
            "G28\n" +
                ";LAYER:0\n" +
                "G1 X10 Y20 E0.5\n" +
                ";LAYER:1\n" +
                "G1 X10 Y20 Z0.4 E0.5\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        val path = GcodeNozzlePathParser.parse(file)
        assertEquals(2, path.moveCount)
        val parsed = GcodeCommand.parse("C29 L53 R175 F57 B177 X6 Y6")!!
        GcodeCommandPolicy.requirePreviewSafe(parsed, 0)
        GcodeCommandPolicy.requirePublishedSafe(parsed, null, 1)
        GcodeCommandPolicy.requirePreviewSafe(GcodeCommand.parse("C29 A")!!, 0)
        GcodeCommandPolicy.requirePublishedSafe(GcodeCommand.parse("C29 M")!!, null, 1)
    }

    @Test
    fun existingC29InStartIsReplacedByTheAuthoritativeArea() {
        val file = temporaryGcode(
            "G28\n" +
                "C29 A ; use AML\n" +
                "M420 S1\n" +
                ";LAYER:0\n" +
                "G1 X10 Y20 E0.5\n" +
                ";LAYER:1\n" +
                "G1 X10 Y20 Z0.4 E0.5\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        val text = file.readText()
        assertFalse("the useless C29 A must be removed", text.contains("C29 A"))
        assertEquals(1, "C29 L".toRegex().findAll(text).count())
        assertTrue(text.contains("C29 L5 R15 F15 B25 X6 Y6 ; AML mesh area"))
        assertFalse("second injection must be idempotent", AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
    }

    @Test
    fun g27ParkIsTrustedBeforeMotionLikeG28() {
        GcodeCommandPolicy.requirePreviewSafe(GcodeCommand.parse("G27")!!, 0)
        val afterMotion = runCatching {
            GcodeCommandPolicy.requirePreviewSafe(GcodeCommand.parse("G27")!!, 5)
        }.exceptionOrNull()
        assertTrue(afterMotion != null)
    }

    @Test
    fun otherCCodesAndUnsupportedC29ArgumentsRemainRejected() {
        val preview = runCatching { GcodeCommandPolicy.requirePreviewSafe(GcodeCommand.parse("C20")!!, 0) }.exceptionOrNull()
        assertTrue(preview != null)
        val published = runCatching {
            GcodeCommandPolicy.requirePublishedSafe(GcodeCommand.parse("C29 Q")!!, null, 1)
        }.exceptionOrNull()
        assertTrue(published != null)
        val publishedTooBig = runCatching {
            GcodeCommandPolicy.requirePublishedSafe(GcodeCommand.parse("C29 L5000")!!, null, 1)
        }.exceptionOrNull()
        assertTrue(publishedTooBig != null)
    }


    @Test
    fun autoDensityWidensWithTheModel() {
        // A wide 160x40mm footprint region gets more points in X than Y.
        val file = temporaryGcode(
            "G28\n" +
                ";LAYER:0\n" +
                "G1 X10 Y20 E0.3\n" +
                "G1 X170 Y60 E0.3\n" +
                ";LAYER:1\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        assertTrue(file.readText().contains("C29 L5 R175 F15 B65 X6 Y3 ; AML mesh area"))
    }

    @Test
    fun chosenDensityIsFittedPerAxis() {
        val file = temporaryGcode(
            "G28\n" +
                ";LAYER:0\n" +
                "G1 X10 Y20 E0.3\n" +
                ";LAYER:1\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0, maxPointsPerAxis = 4))
        assertTrue(file.readText().contains("C29 L5 R15 F15 B25 X4 Y4 ; AML mesh area"))
        val bad = runCatching {
            tempGcodeWith("G28\n;LAYER:0\nG1 X10 Y20 E0.3\n;LAYER:1\n").let {
                AdaptiveBedMeshInjector.inject(it, envelope, 5.0, maxPointsPerAxis = 2)
            }
        }.exceptionOrNull()
        assertTrue("density outside 3..9 must be rejected", bad != null)
    }

    private fun tempGcodeWith(contents: String): File =
        File.createTempFile("aml-injector-test", ".gcode").apply { writeText(contents); deleteOnExit() }


    private fun temporaryGcode(contents: String): File =
        File.createTempFile("aml-injector-test", ".gcode").apply {
            writeText(contents)
            deleteOnExit()
        }
}