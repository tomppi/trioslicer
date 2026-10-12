package com.tomppi.enderslicer.viewer

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The bundled snap-fit library: eight ready-made joints, grafted onto a seam.
 *
 * The library is kaje's "Snap-Fit Joint Test Prints - Eight Versions"
 * (printables.com/model/151102, Public Domain) and lives in
 * app/src/main/assets/snap-library. Every shape is a PAIR, and the author's own
 * files carry both pieces in ONE assembled coordinate system - the 3MF files
 * move them apart only to print them. That is the whole reason this style can
 * place by tapping: there is nothing to line up. Put the pair down with its own
 * mating plane on the seam's plane and the hook and its catch are already in
 * register, running on the author's clearances.
 *
 * What each shape is made of, and which piece is which:
 *
 *  - the FIRST piece named by the README is the HOOK: the half the user chose
 *    gets it, unioned on whole, because it is the part that has to stand proud
 *    of the seam.
 *  - the SECOND piece is the CATCH: the mate gets it. Where that piece is a
 *    whole housing box - the cantilever, separable and torsion shapes - only
 *    the local catch is grafted: the housing is clipped to a box around the
 *    hook's own reach (its material on the catch's side of the mating plane,
 *    grown by [GRAFT_MARGIN_MM]), and the mate's own material inside that box is
 *    replaced by the housing's shape there. That replacement is what makes the
 *    hooks somewhere to go on a solid part: unioning the housing's walls onto a
 *    solid mate would bury them, and cutting nothing would leave the printed
 *    halves colliding. The box is the where; the housing is the what.
 *  - the two annular shapes' second piece is a whole solid, not a housing: it
 *    is grafted as it is, and the mate grows the bead the collar snaps over.
 *
 * The library's own clearances are the library's: this object measures the
 * pair's smallest gap at 1:1 when it loads it, and the panel reports that
 * number at the current scale and warns when the scale takes it under what FDM
 * can print. The strain of a beam we did not design is NOT computed - see
 * [STRAIN_NOTE].
 *
 * Pure apart from [load], which is the one place the boolean engine is asked
 * for the clip and the closure of what it produced; everything after that is
 * arithmetic over meshes.
 */
enum class SnapLibraryShape(
    /** The README's own name for the shape, which is what the panel shows. */
    val label: String,
    /** The README's one line about it. */
    val summary: String,
    /** The piece the chosen half gets: the hook. */
    val hookFile: String,
    /** The piece the mate gets: the catch. */
    val catchFile: String,
    /**
     * The library's own mating plane along its z, where the two pieces were
     * assembled to meet: the seam's plane goes here. Measured from the author's
     * files, not guessed: for the box shapes it is the plane where the catch's
     * rim face and the hook's own face coincide (cantilever01/02 and
     * cantileverridgid and separable02 at z = 10, separable at z = 10, torsion03
     * at z = 15); for the two round shapes it is the collar's own base face,
     * which is the face that stands on the seam's plane (annular at z = 9.5,
     * annularcontinuous at z = 1.433).
     */
    val matingPlaneMm: Float,
    /** True when the catch piece is a whole housing box that must be clipped. */
    val housing: Boolean,
) {
    CANTILEVER01(
        label = "cantilever01",
        summary = "the cantilever tapered across its width",
        hookFile = "cantilever01-top.stl",
        catchFile = "cantilever01-bottom.stl",
        matingPlaneMm = 10f,
        housing = true,
    ),
    CANTILEVER02(
        label = "cantilever02",
        summary = "the same catch; the cantilever tapered across its thickness",
        hookFile = "cantilever02-top.stl",
        // The author's own note: this shape shares cantilever01's bottom piece.
        catchFile = "cantilever01-bottom.stl",
        matingPlaneMm = 10f,
        housing = true,
    ),
    CANTILEVER_RIGID(
        label = "cantileverridgid",
        summary = "a cantilever with rigid lugs",
        hookFile = "cantileverridgid-top.stl",
        catchFile = "cantileverridgid-bottom.stl",
        matingPlaneMm = 10f,
        housing = true,
    ),
    SEPARABLE(
        label = "separable",
        summary = "squeeze the two buttons to open it",
        hookFile = "separable-top.stl",
        catchFile = "separable-bottom.stl",
        matingPlaneMm = 10f,
        housing = true,
    ),
    SEPARABLE02(
        label = "separable02",
        summary = "push the lugs through the bottom's openings",
        hookFile = "separable02-top.stl",
        catchFile = "separable02-bottom.stl",
        matingPlaneMm = 10f,
        housing = true,
    ),
    ANNULAR(
        label = "annular",
        summary = "segmented annular snap, round",
        hookFile = "annular-annularring.stl",
        catchFile = "annular-annularsnap.stl",
        matingPlaneMm = 9.5f,
        housing = false,
    ),
    ANNULAR_CONTINUOUS(
        label = "annularcontinuous",
        summary = "a round snap whose joint is almost invisible",
        hookFile = "annularcontinuous-top.stl",
        catchFile = "annularcontinuous-bottom.stl",
        matingPlaneMm = 1.433f,
        housing = false,
    ),
    TORSION03(
        label = "torsion03",
        summary = "a torsion bar; the author's own note says it needs work",
        hookFile = "torsion03-top.stl",
        catchFile = "torsion03-bottom.stl",
        matingPlaneMm = 15f,
        housing = true,
    ),
}

/**
 * One loaded shape: the two pieces, the graft the clip produced, and the gap
 * the library itself designed into the pair.
 */
data class SnapLibraryPair(
    val shape: SnapLibraryShape,
    /** The hook piece, in the library's own assembled coordinates. */
    val hook: StlMesh,
    /** What lands on the mate: the catch's material inside the graft box. */
    val catchGraft: StlMesh,
    /**
     * What the mate's own material inside the box is replaced by: the box with
     * the catch taken out of it. Empty for a shape whose catch is not a housing.
     */
    val catchVoid: StlMesh,
    /** The graft box, in library coordinates. */
    val box: Box3,
    /** The hook's own outer size at 1:1, and the footprint centre the tap centres on. */
    val hookSizeMm: Vec3,
    val hookCentreMm: Vec3,
    /** The pair's smallest gap at 1:1, contacts aside. */
    val gapOneToOneMm: Float,
    /** What the gate had to say about the pieces, or null when they were closed as they came. */
    val note: String?,
)

/**
 * Whether the library's own piece fits the place it has been put in.
 *
 * The hook piece is the one that has to sit on the part - the catch is grafted
 * local to the hook's own reach and takes no room of its own - so the two
 * numbers that matter are its footprint along the seam and across it, at the
 * scale it is being built at, against the room the seam gives at the anchor:
 * how far the edge runs straight either side of it, and how deep the face is
 * inboard of the rim. Both are read off the same cap outline the joint is built
 * on, so a number here is a number the build used and not a second opinion.
 *
 * A shape that does not fit is REFUSED by name, with both sets of numbers and
 * the largest scale that would fit, because a hook that hangs off the part is
 * the one failure the meshes cannot see: the union is closed, the graft is
 * closed and the pair still closes on itself.
 */
data class LibraryFit(
    /** The shape's own name, for the sentence. */
    val shapeLabel: String,
    /** The widest footprint the seam's straight run can hold, centred on the anchor. */
    val alongMm: Float,
    /** The face's own depth across the seam, inboard of the rim. */
    val acrossMm: Float,
    /** The hook's own footprint at this scale, along the seam. */
    val needsAlongMm: Float,
    /** ... and across it. */
    val needsAcrossMm: Float,
    /** The scale both of those were read at. */
    val scale: Float,
) {
    /** How much of the run is left over once the hook's own half is on it, along the seam. */
    private val slackAlongMm: Float get() = alongMm + FIT_TOLERANCE_MM - needsAlongMm

    /** The same across it. */
    private val slackAcrossMm: Float get() = acrossMm + FIT_TOLERANCE_MM - needsAcrossMm

    /** True when the piece's footprint is inside the room the place gives it. */
    val fits: Boolean get() = slackAlongMm >= 0f && slackAcrossMm >= 0f

    /** How much room is left over on the tighter of the two sides. */
    val marginMm: Float get() = minOf(slackAlongMm, slackAcrossMm)

    /** True when it fits, but with less room to spare than a millimetre. */
    val tight: Boolean get() = fits && marginMm < TIGHT_MARGIN_MM

    /**
     * The largest scale this place would take the piece at, from the scale it is
     * on now.
     *
     * The room is read with the same tolerance the fit is: a footprint that
     * overruns its run by a fifth of a millimetre is inside the print's own
     * resolution, and a suggestion that refused would be worse than no
     * suggestion at all.
     */
    val maxScale: Float
        get() {
            val ratios = ArrayList<Float>(2)
            if (needsAlongMm > FIT_TOLERANCE_MM) {
                ratios += (alongMm + FIT_TOLERANCE_MM) / needsAlongMm
            }
            if (needsAcrossMm > FIT_TOLERANCE_MM) {
                ratios += (acrossMm + FIT_TOLERANCE_MM) / needsAcrossMm
            }
            return scale * (ratios.minOrNull() ?: 1f)
        }

    /** [maxScale] as the panel prints it: the step it can actually be set to. */
    val maxScaleLabel: Float get() = kotlin.math.floor(maxScale * 100f) / 100f

    /**
     * The panel's sentence when the piece does not fit here: what the shape is,
     * what the place gives, and the two levers - the scale that would fit and
     * the kind of place that would.
     */
    fun refusal(): String {
        val alongShort = needsAlongMm > alongMm + FIT_TOLERANCE_MM
        val acrossShort = needsAcrossMm > acrossMm + FIT_TOLERANCE_MM
        val lever = when {
            alongShort && acrossShort -> "place it where the seam runs longer and the face is deeper"
            alongShort -> "place it on a longer run"
            else -> "place it where the face is deeper"
        }
        return String.format(
            Locale.ROOT,
            "cannot take the %s hook: it is %.1f mm along the seam and %.1f mm across it, and this " +
                "face gives %.1f mm along and %.1f mm across: scale it to %.2f\u00d7 at most, or %s.",
            shapeLabel,
            needsAlongMm,
            needsAcrossMm,
            alongMm,
            acrossMm,
            maxScaleLabel,
            lever,
        )
    }

    /** The panel's sentence when it fits, but only just; null when there is room to spare. */
    fun tightWarning(): String? {
        if (!tight) return null
        val along = slackAlongMm <= slackAcrossMm
        val slack = if (along) slackAlongMm else slackAcrossMm
        val need = if (along) needsAlongMm else needsAcrossMm
        val room = if (along) alongMm else acrossMm
        val side = if (along) "along the seam" else "across it"
        // At or past the room, the tolerance is the only thing holding it: say
        // what the two numbers are rather than report "0.0 mm to spare".
        if (slack < 0.05f) {
            return String.format(
                Locale.ROOT,
                "the %s hook just fits this place %s: %.1f mm of hook against %.1f mm of room",
                shapeLabel,
                side,
                need,
                room,
            )
        }
        return String.format(
            Locale.ROOT,
            "the %s hook fits this place with only %.1f mm to spare %s",
            shapeLabel,
            slack,
            side,
        )
    }
}

/**
 * How much over the room still counts as fitting.
 *
 * A tenth of a millimetre: a quarter of a 0.4 mm nozzle, and inside the
 * tolerance the print itself comes out to. A hook whose footprint overruns its
 * run by that much is on the part in every sense that matters; without it the
 * check would refuse a fit the user cannot tell from a good one, and the
 * smallest scale the panel can be set to would refuse too.
 */
private const val FIT_TOLERANCE_MM = 0.1f

/** Under this much room left over, the fit is said out loud. */
private const val TIGHT_MARGIN_MM = 1f

/** An axis-aligned box in library millimetres. */
data class Box3(val minX: Float, val minY: Float, val minZ: Float, val maxX: Float, val maxY: Float, val maxZ: Float) {
    val sizeX: Float get() = maxX - minX
    val sizeY: Float get() = maxY - minY
    val sizeZ: Float get() = maxZ - minZ

    /** True when [x],[y],[z] is inside, [tolerance] millimetres out allowed either way. */
    fun holds(x: Float, y: Float, z: Float, tolerance: Float = 0f): Boolean =
        x >= minX - tolerance && x <= maxX + tolerance &&
            y >= minY - tolerance && y <= maxY + tolerance &&
            z >= minZ - tolerance && z <= maxZ + tolerance

    /** True when [other] lies inside this box, [tolerance] millimetres out allowed either way. */
    fun holds(other: Box3, tolerance: Float = 0f): Boolean =
        holds(other.minX, other.minY, other.minZ, tolerance) &&
            holds(other.maxX, other.maxY, other.maxZ, tolerance)
}

object SnapLibrary {
    /** Where the STLs live under the app's assets. */
    const val DIRECTORY = "snap-library"

    /**
     * How far past the hook's own reach the catch is grafted. A few millimetres:
     * enough that the catch keeps its whole local feature - the slot, the ledge
     * and the wall around them - and that its graft fuses with the mate, and not
     * enough to carry the rest of the author's housing box with it.
     */
    const val GRAFT_MARGIN_MM = 3f

    /**
     * Below this the pair's own clearance is finer than FDM resolves: a 0.4 mm
     * nozzle cannot print a gap much under this, so the two halves come out
     * fused where they should run free. A warning, never a refusal - the
     * library's fit is the library's.
     */
    const val MIN_PRINTABLE_GAP_MM = 0.15f

    /**
     * The honest line about strain. The cantilever and the wall lip report a
     * root strain because this app chose their beams, their walls and their
     * deflections; a library shape's spring is the author's, its material is the
     * author's, and the number would be invented. So the panel says so instead.
     */
    const val STRAIN_NOTE =
        "Strain is not computed for a library shape: the spring is the author's own design and " +
            "this app did not choose its beam, its wall or its deflection, so any figure here " +
            "would be invented. The fit is the library's; the clearances above are measured."

    /** The smallest distance a gap may be and still count as a gap rather than a seat. */
    private const val CONTACT_MM = 0.0001f

    /** How far past the catch's envelope a hook's own reach may still be read as inside it. */
    private const val GRAFT_TOLERANCE_MM = 0.01f

    /** One grid cell of the gap measurement, in millimetres. */
    private const val CELL_MM = 1f

    private val cache = HashMap<SnapLibraryShape, SnapLibraryPair>()

    /**
     * The shape, loaded: the two pieces read through [read], made watertight,
     * clipped where the catch is a housing, and measured.
     *
     * [read] is the caller's own door to the files - the app's assets manager on
     * a device, a directory in a test - so nothing here knows where the library
     * is kept. Successes are kept: the pieces are megabytes and the preview
     * rebuilds on every tap and every slider, so the read and the booleans run
     * once per shape per process.
     *
     * Throws with the shape named when a piece is not and cannot be made
     * watertight, when the clip produces something open, or when the hook's own
     * reach does not lie inside the catch's envelope - the shapes that cannot
     * work say so instead of placing a joint that is not there.
     */
    fun load(shape: SnapLibraryShape, read: (String) -> StlMesh): SnapLibraryPair {
        cache[shape]?.let { return it }
        val engine = SnapFitGate.NativeEngine
        val hookRaw = read(shape.hookFile)
        val catchRaw = read(shape.catchFile)
        val notes = ArrayList<String>(2)
        val hook = prepared(shape.label + " " + shape.hookFile, hookRaw, engine, notes)
        val catch = prepared(shape.label + " " + shape.catchFile, catchRaw, engine, notes)
        // Where the catch is a housing, the graft is its own local shape: the box
        // is around the hook's reach - the material that crosses the seam into
        // the mate - grown by the margin, and it is never allowed past the
        // catch's own envelope, because that is what keeps the replacement from
        // carving the mate anywhere the author's housing never reached. A catch
        // that is a whole solid rather than a housing is grafted as it is, and
        // then the envelope is simply the catch's own.
        var box = Box3(
            catch.bounds.minX, catch.bounds.minY, catch.bounds.minZ,
            catch.bounds.maxX, catch.bounds.maxY, catch.bounds.maxZ,
        )
        var graft = catch
        var void = EMPTY
        if (shape.housing) {
            val reach = reachBounds(hook, shape.matingPlaneMm)
                ?: throw IllegalStateException(
                    shape.label + ": the hook piece has nothing on the catch's side of its own " +
                        "mating plane, so there is no hook to graft",
                )
            box = Box3(
                minX = max(catch.bounds.minX, reach.minX - GRAFT_MARGIN_MM),
                minY = max(catch.bounds.minY, reach.minY - GRAFT_MARGIN_MM),
                minZ = max(catch.bounds.minZ, min(reach.minZ, shape.matingPlaneMm) - GRAFT_MARGIN_MM),
                maxX = min(catch.bounds.maxX, reach.maxX + GRAFT_MARGIN_MM),
                maxY = min(catch.bounds.maxY, reach.maxY + GRAFT_MARGIN_MM),
                maxZ = min(catch.bounds.maxZ, shape.matingPlaneMm + GRAFT_MARGIN_MM),
            )
            require(box.sizeX > 0f && box.sizeY > 0f && box.sizeZ > 0f) {
                shape.label + ": the catch has no material where this shape's hook reaches, so " +
                    "there is nothing for the hook to catch on"
            }
            require(box.holds(reach, GRAFT_TOLERANCE_MM)) {
                shape.label + ": the hook reaches past the catch's own envelope (" +
                    describe(reach) + " against " + describe(box) + ") by " +
                    describe(past(reach, box)) + ", so part of it would have nothing to run in"
            }
            val boxMesh = boxMesh(box, shape.label + " graft box")
            graft = boolean(shape, "clip the catch to the graft box") { MeshBoolean.intersect(catch, boxMesh) }
            void = boolean(shape, "take the catch out of the graft box") { MeshBoolean.subtract(boxMesh, catch) }
            require(graft.triangleCount > 0) {
                shape.label + ": the catch's own material is not inside the box around the hook, so " +
                    "the mate would gain nothing"
            }
            require(void.triangleCount > 0) {
                shape.label + ": the graft box is entirely inside the catch, so the mate's material " +
                    "would not be freed for the hook"
            }
        }
        val pair = SnapLibraryPair(
            shape = shape,
            hook = hook,
            catchGraft = graft,
            catchVoid = void,
            box = box,
            hookSizeMm = Vec3(
                hook.bounds.maxX - hook.bounds.minX,
                hook.bounds.maxY - hook.bounds.minY,
                hook.bounds.maxZ - hook.bounds.minZ,
            ),
            hookCentreMm = Vec3(
                (hook.bounds.minX + hook.bounds.maxX) * 0.5f,
                (hook.bounds.minY + hook.bounds.maxY) * 0.5f,
                shape.matingPlaneMm,
            ),
            gapOneToOneMm = smallestGapMm(hook, catch),
            note = notes.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
        cache[shape] = pair
        return pair
    }

    /** Forgets every loaded shape; the tests' own door, and nothing on the app's path. */
    internal fun forget() = cache.clear()

    /**
     * The pair placed on the seam: the hook unioned into [hookHalf], the catch's
     * local shape grafted into the mate.
     *
     * The library's +z is the side the HOOK's half is on - its plate stands
     * there and its hooks reach the other way - so it is walked along the
     * assembly axis the way round the chosen half is. Its x and y lie in the
     * seam's plane: the hook's own length runs along the frame's rise, its width
     * along the side, and the tap is the middle of the hook's footprint. The
     * mating plane goes on the seam's own plane, and every dimension - the
     * clearances included - follows the one scale.
     */
    /** What placing one library pair came out as. */
    sealed interface Outcome {
        data class Built(val joint: SnapFitJoint) : Outcome

        /**
         * The piece does not fit the place it was put in. [reason] carries both
         * sets of numbers and the lever, so the panel says what to change rather
         * than only that it will not build.
         */
        data class Refused(val reason: String) : Outcome
    }

    /**
     * The fit of [pair]'s hook at [anchorMm] on [hookHalf], read at [scale].
     *
     * The build itself asks this before it builds anything, and the panel and
     * the tests ask the same function, so the numbers cannot drift from the ones
     * the build acts on. Null when the half has no cap at the seam to measure,
     * which leaves the build to its own refusal rather than inventing a room.
     */
    internal fun fitAt(
        pair: SnapLibraryPair,
        /** The split's own assembly direction; the cap is the same cross-section either way. */
        axisDirection: Vec3,
        anchorMm: Vec3,
        scale: Float,
        hookHalf: SnapFitHalf,
        hookOnLowHalf: Boolean,
    ): LibraryFit? {
        if (!scale.isFinite() || scale <= 0f) return null
        // The hook's own two axes in the seam's plane: the library's x runs along
        // the frame's side and its y along the rise, so its footprint is a
        // rectangle in those directions and its extent along any other is that
        // rectangle's own support function.
        val up = if (hookOnLowHalf) axisDirection * -1f else axisDirection
        val side = SnapFit.perpendicularTo(up)
        val rise = up.cross(side)
        val room = SnapSeam.room(hookHalf, axisDirection, anchorMm) ?: return null
        if (!room.isUsable) return null
        val size = pair.hookSizeMm
        val needsAlong = (
            abs(room.alongDirection.dot(side)) * size.x +
                abs(room.alongDirection.dot(rise)) * size.y
            ) * scale
        val needsAcross = (
            abs(room.inboardDirection.dot(side)) * size.x +
                abs(room.inboardDirection.dot(rise)) * size.y
            ) * scale
        return LibraryFit(
            shapeLabel = pair.shape.label,
            alongMm = room.alongMm,
            acrossMm = room.acrossMm,
            needsAlongMm = needsAlong,
            needsAcrossMm = needsAcross,
            scale = scale,
        )
    }

    fun build(
        pair: SnapLibraryPair,
        /** The split's own assembly direction, from the low half towards the high one. */
        axisDirection: Vec3,
        anchorMm: Vec3,
        scale: Float,
        hookHalf: SnapFitHalf,
        mateHalf: SnapFitHalf,
        hookOnLowHalf: Boolean,
    ): Outcome {
        // The library's own up: from the mate towards the half carrying the hook.
        val up = if (hookOnLowHalf) axisDirection * -1f else axisDirection
        val side = SnapFit.perpendicularTo(up)
        val rise = up.cross(side)
        // The seam's plane, read along the library's own up: the half's mating
        // face is recorded along the split's axis, and this walks the other way
        // when the hook is the low half's.
        val plane = if (hookOnLowHalf) -hookHalf.faceMm else hookHalf.faceMm
        val origin = anchorMm - up * (anchorMm.dot(up) - plane)
        val frame = SnapFitFrame(origin, up, side, rise, origin.dot(up))
        // The fit, before a single triangle is placed: the hook is the piece that
        // has to sit on the part, and a place too short or too shallow for it is
        // refused by name with both sets of numbers rather than built into thin
        // air - which no closedness, seat or pair check anywhere would see.
        val fit = fitAt(pair, axisDirection, anchorMm, scale, hookHalf, hookOnLowHalf)
        if (fit != null && !fit.fits) return Outcome.Refused(fit.refusal())
        val centre = pair.hookCentreMm
        val placeZ = pair.shape.matingPlaneMm
        fun place(mesh: StlMesh, half: SnapFitHalf, name: String): StlMesh =
            SnapFit.placeOnPlate(placed(mesh, frame, centre, placeZ, scale, name), half, name)
        val hookSolid = place(pair.hook, hookHalf, HOOK_NAME)
        val catchSolid = place(pair.catchGraft, mateHalf, CATCH_NAME)
        val catchVoid = place(pair.catchVoid, mateHalf, CATCH_VOID_NAME)
        val size = pair.hookSizeMm
        return Outcome.Built(SnapFitJoint(
            unionSolid = hookSolid,
            // The library takes nothing out of the hook's half: its plate is
            // unioned on and that is the whole of that half's graft.
            subtractSolid = EMPTY,
            registrationSolid = catchSolid,
            registrationRecess = catchVoid,
            frame = SnapFit.placeFrame(hookHalf, frame),
            socketFrame = SnapFit.placeFrame(mateHalf, frame),
            dimensions = SnapFitDimensions(
                style = SnapJointStyle.LIBRARY_HOOK,
                scale = scale,
                keySizeMm = 0f,
                keyHeightMm = 0f,
                keyClearanceMm = 0f,
                keyOffsetMm = 0f,
                beamLengthMm = size.y * scale,
                beamWidthMm = size.x * scale,
                beamThicknessMm = size.z * scale,
                beamRootMm = 0f,
                lipDepthMm = 0f,
                lipRunMm = 0f,
                lipClearanceMm = 0f,
                matingClearanceMm = 0f,
                rampAngleDeg = 0f,
                beamTipMm = 0f,
                lipBackMm = 0f,
                stepDepthMm = 0f,
                stepRootMm = 0f,
                stepRimMm = 0f,
                stepClearanceMm = 0f,
                rootStrainPercent = 0f,
                barbCount = 0,
                toothPitchMm = 0f,
                teethCatchMm = emptyList(),
                pawlMm = 0f,
                pawlLandMm = 0f,
                clickGapMm = emptyList(),
                deflectionMm = 0f,
                deflectionRoomMm = 0f,
                mouthChamferMm = 0f,
                mouthChamferAngleDeg = 0f,
                facing = SnapFacing.SAME,
                libraryShape = pair.shape.name,
                librarySizeMm = size * scale,
                libraryGapOneToOneMm = pair.gapOneToOneMm,
                libraryGapMm = pair.gapOneToOneMm * scale,
            ),
            style = SnapJointStyle.LIBRARY_HOOK,
            // The library has no ladder: the shape is the shape, exactly as its
            // author printed it. The rung field is what the panel's cantilever
            // row reads, and that row is not drawn for this style.
            rung = SnapFitRung.MINIMAL,
            rungReason = null,
            clamps = emptyList(),
            warnings = listOfNotNull(
                pair.note?.let { "The library piece was repaired before it could be used: " + it },
                pair.shape.takeIf { it == SnapLibraryShape.TORSION03 }?.let {
                    "The author's own note on this shape is that it needs much improvement."
                },
                // It fits, but only just: said here rather than found out in
                // plastic, because the next smaller face refuses it outright.
                fit?.tightWarning(),
            ),
        ))
    }

    /** The shape a joint was built from, or null when it is not a library joint. */
    fun shapeOf(dimensions: SnapFitDimensions): SnapLibraryShape? =
        dimensions.libraryShape.takeIf { it.isNotBlank() }
            ?.let { name -> SnapLibraryShape.entries.firstOrNull { it.name == name } }

    /** One line for the panel: the shape and what the README says it is. */
    fun describe(dimensions: SnapFitDimensions): String {
        val shape = shapeOf(dimensions) ?: return ""
        return shape.label + " \u00b7 " + shape.summary
    }

    /** The hook's own size at the scale it was built at: width x length across the seam, and its height. */
    fun sizeSummary(dimensions: SnapFitDimensions): String {
        val size = dimensions.librarySizeMm
        return String.format(
            Locale.ROOT,
            "%.1f \u00d7 %.1f mm across the seam, %.1f mm deep, at %.2f\u00d7",
            size.x,
            size.y,
            size.z,
            dimensions.scale,
        )
    }

    /**
     * The pair's own clearance, at 1:1 and at the scale it is being printed at.
     *
     * Where the pair has no gap above the seat at all - the author drew those
     * surfaces touching or a fraction of a micrometre apart, and the measurement
     * says so rather than inventing a clearance - the line says that instead of
     * printing 0.00 mm as if it were a number to compare with.
     */
    fun gapSummary(dimensions: SnapFitDimensions): String {
        if (dimensions.libraryGapOneToOneMm <= 0f) {
            return "the library's pieces meet where the author assembled them: no gap above a " +
                "tenth of a micrometre, so there is none for the scale to shrink or open"
        }
        return String.format(
            Locale.ROOT,
            "the library's own clearance: %.3f mm at 1:1, %.3f mm at %.2f\u00d7",
            dimensions.libraryGapOneToOneMm,
            dimensions.libraryGapMm,
            dimensions.scale,
        )
    }

    /**
     * The warning when the scale has taken the pair's own clearance under what
     * FDM resolves, or null. A pair that meets at 1:1 gets its own sentence: no
     * scale opens a gap that is not there, and shrinking one that is already
     * tight is the case the warning is for.
     */
    fun gapWarning(dimensions: SnapFitDimensions): String? {
        if (dimensions.libraryGapOneToOneMm <= 0f) {
            return String.format(
                Locale.ROOT,
                "the pair's own surfaces meet at 1:1, so there is no clearance to scale: at " +
                    "%.2f\u00d7 this joint prints the way the author drew it - tight - and no " +
                    "scale makes it looser. It is the library's fit, not ours.",
                dimensions.scale,
            )
        }
        if (dimensions.libraryGapMm >= MIN_PRINTABLE_GAP_MM) return null
        return String.format(
            Locale.ROOT,
            "at %.2f\u00d7 the library's own clearance is %.3f mm, under the %.2f mm this app " +
                "treats as printable: the two halves may come out fused where they should run " +
                "free. Print it larger, or expect to free the joint after printing.",
            dimensions.scale,
            dimensions.libraryGapMm,
            MIN_PRINTABLE_GAP_MM,
        )
    }

    /**
     * The hook's material on the catch's side of the mating plane, in library
     * coordinates: the part of the piece that reaches across the seam and has to
     * have somewhere to go. A triangle whose middle is below the plane belongs
     * to the reach whole, so a triangle straddling the plane contributes both
     * sides - conservative, and what the graft box wants.
     */
    private fun reachBounds(hook: StlMesh, planeZ: Float): Box3? {
        val vertices = hook.interleavedVertices
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (triangle in 0 until hook.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            var middleZ = 0f
            for (corner in 0 until 3) {
                middleZ += vertices[base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX + 2] / 3f
            }
            if (middleZ >= planeZ) continue
            for (corner in 0 until 3) {
                val at = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                val x = vertices[at]
                val y = vertices[at + 1]
                val z = vertices[at + 2]
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                minZ = min(minZ, z)
                maxZ = max(maxZ, z)
            }
        }
        if (minX > maxX) return null
        return Box3(minX, minY, minZ, maxX, maxY, min(planeZ, maxZ))
    }

    /** A closed box mesh in library coordinates. */
    private fun boxMesh(box: Box3, name: String): StlMesh {
        val builder = MeshSolidBuilder(name)
        val x0 = box.minX
        val x1 = box.maxX
        val y0 = box.minY
        val y1 = box.maxY
        val z0 = box.minZ
        val z1 = box.maxZ
        fun face(
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float,
            dx: Float, dy: Float, dz: Float,
        ) {
            builder.addTriangle(ax, ay, az, bx, by, bz, cx, cy, cz)
            builder.addTriangle(ax, ay, az, cx, cy, cz, dx, dy, dz)
        }
        face(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0)
        face(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1)
        face(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1)
        face(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0)
        face(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0)
        face(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1)
        return builder.build()
    }

    /** One boolean, with its result's closedness verified by the engine rather than assumed. */
    private fun boolean(shape: SnapLibraryShape, what: String, run: () -> MeshBoolean.Result): StlMesh {
        val result = run()
        if (result !is MeshBoolean.Result.Success) {
            throw IllegalStateException(
                shape.label + ": the engine could not " + what + ": " +
                    (result as MeshBoolean.Result.Failure).reason,
            )
        }
        val mesh = result.mesh
        if (mesh.triangleCount > 0 && MeshBoolean.manifoldStatus(mesh) != SnapFitGate.NO_ERROR) {
            throw IllegalStateException(
                shape.label + ": the engine could " + what + ", and the result is not a closed " +
                    "solid (" + MeshBoolean.manifoldStatus(mesh) + "), so it cannot be grafted",
            )
        }
        return mesh
    }

    /** [mesh] through the watertight gate, with what the gate had to do noted. */
    private fun prepared(
        name: String,
        mesh: StlMesh,
        engine: SnapFitGate.Engine,
        notes: MutableList<String>,
    ): StlMesh = when (val result = SnapFitGate.prepare(mesh, engine, name)) {
        is SnapFitGate.Result.Prepared -> {
            result.ready.note?.let { notes += name + ": " + it }
            result.ready.mesh
        }
        is SnapFitGate.Result.Refused -> throw IllegalStateException(result.reason)
    }

    /**
     * [mesh], given in the library's own assembled coordinates, laid out in the
     * seam's frame: the mating plane on the seam's plane, the hook's footprint
     * centred on [centre], every dimension through [scale].
     */
    private fun placed(
        mesh: StlMesh,
        frame: SnapFitFrame,
        centre: Vec3,
        planeZ: Float,
        scale: Float,
        name: String,
    ): StlMesh {
        val builder = MeshSolidBuilder(name)
        val vertices = mesh.interleavedVertices
        val points = FloatArray(9)
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            for (corner in 0 until 3) {
                val at = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                val point = frame.model(
                    Vec3(
                        scale * (vertices[at] - centre.x),
                        scale * (vertices[at + 1] - centre.y),
                        scale * (vertices[at + 2] - planeZ),
                    ),
                )
                points[corner * 3] = point.x
                points[corner * 3 + 1] = point.y
                points[corner * 3 + 2] = point.z
            }
            builder.addTriangle(
                points[0], points[1], points[2],
                points[3], points[4], points[5],
                points[6], points[7], points[8],
            )
        }
        return builder.build()
    }

    /** How far [inner] pokes out of [outer], as a box of the overhangs; zeroes when it is inside. */
    private fun past(inner: Box3, outer: Box3): Box3 = Box3(
        max(0f, outer.minX - inner.minX),
        max(0f, outer.minY - inner.minY),
        max(0f, outer.minZ - inner.minZ),
        max(0f, inner.maxX - outer.maxX),
        max(0f, inner.maxY - outer.maxY),
        max(0f, inner.maxZ - outer.maxZ),
    )

    private fun describe(box: Box3): String = String.format(
        Locale.ROOT,
        "%.1f\u00d7%.1f\u00d7%.1f mm",
        box.sizeX,
        box.sizeY,
        box.sizeZ,
    )

    private val EMPTY: StlMesh = MeshSolidBuilder("none").build()

    internal const val HOOK_NAME = "snap library hook"
    internal const val CATCH_NAME = "snap library catch"
    internal const val CATCH_VOID_NAME = "snap library catch recess"

    // --- The pair's own smallest gap -----------------------------------------

    /**
     * The smallest distance between two meshes, with the places they are meant
     * to sit against each other left out.
     *
     * The author assembled the pair seated: the lid's underside and the box's
     * rim are two coincident faces at exactly the mating plane, so the plain
     * minimum distance between the pieces is zero and says nothing about
     * whether the pair will print. What decides that is the smallest distance
     * between two surfaces that are NOT in contact - the running clearance
     * around the hook, the barb and the bead - and that is what this measures
     * and what the panel reports. Contacts are anything under [CONTACT_MM] - a
     * tenth of a micrometre - because the files' coincident faces are exactly
     * zero and no designed clearance is anywhere near that small.
     *
     * Exact rather than sampled, and cheap enough to run once per shape: the
     * minimum of two closed surfaces is attained either between a vertex and a
     * face or between two edges, so both are measured, each against a
     * bounding-volume hierarchy of the other mesh.
     */
    internal fun smallestGapMm(first: StlMesh, second: StlMesh, contactMm: Float = CONTACT_MM): Float {
        if (first.triangleCount == 0 || second.triangleCount == 0) return 0f
        val firstTree = TriangleTree(first)
        val secondTree = TriangleTree(second)
        var best = Float.MAX_VALUE
        val firstVertices = firstTree.vertices
        val secondVertices = secondTree.vertices
        for (at in firstVertices.indices step 3) {
            secondTree.nearestPoint(contactMm, firstVertices[at], firstVertices[at + 1], firstVertices[at + 2], best)?.let {
                if (it in contactMm..<best) best = it
            }
        }
        for (at in secondVertices.indices step 3) {
            firstTree.nearestPoint(contactMm, secondVertices[at], secondVertices[at + 1], secondVertices[at + 2], best)?.let {
                if (it in contactMm..<best) best = it
            }
        }
        val firstEdges = EdgeTree(first, contactMm)
        val secondEdges = EdgeTree(second, contactMm)
        for (edge in firstEdges.edges) {
            secondEdges.nearestEdge(edge, best)?.let { if (it in contactMm..<best) best = it }
        }
        for (edge in secondEdges.edges) {
            firstEdges.nearestEdge(edge, best)?.let { if (it in contactMm..<best) best = it }
        }
        return if (best == Float.MAX_VALUE) 0f else best
    }

    /** One edge of a mesh: six coordinates. */
    private class Edge(val values: FloatArray)

    private class TriangleTree(mesh: StlMesh) {
        val vertices: FloatArray
        private val triangles: Int
        private val index: IntArray
        private val nodes: MutableList<Node> = ArrayList()

        init {
            triangles = mesh.triangleCount
            vertices = FloatArray(triangles * 9)
            val source = mesh.interleavedVertices
            for (triangle in 0 until triangles) {
                for (corner in 0 until 3) {
                    val from = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE +
                        corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                    val to = triangle * 9 + corner * 3
                    vertices[to] = source[from]
                    vertices[to + 1] = source[from + 1]
                    vertices[to + 2] = source[from + 2]
                }
            }
            index = IntArray(triangles) { it }
            build(0, triangles)
        }

        private inner class Node(
            val minX: Float, val minY: Float, val minZ: Float,
            val maxX: Float, val maxY: Float, val maxZ: Float,
            val left: Int, val right: Int,
            val start: Int, val count: Int,
        )

        private fun build(start: Int, count: Int): Int {
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var minZ = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var maxZ = Float.NEGATIVE_INFINITY
            for (slot in start until start + count) {
                val base = index[slot] * 9
                for (corner in 0 until 3) {
                    val x = vertices[base + corner * 3]
                    val y = vertices[base + corner * 3 + 1]
                    val z = vertices[base + corner * 3 + 2]
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                    minZ = min(minZ, z)
                    maxZ = max(maxZ, z)
                }
            }
            val node = nodes.size
            if (count <= LEAF_TRIANGLES) {
                nodes += Node(minX, minY, minZ, maxX, maxY, maxZ, -1, -1, start, count)
                return node
            }
            val sizeX = maxX - minX
            val sizeY = maxY - minY
            val sizeZ = maxZ - minZ
            val axis = when {
                sizeX >= sizeY && sizeX >= sizeZ -> 0
                sizeY >= sizeZ -> 1
                else -> 2
            }
            // Split about the middle of the centroids' spread rather than sorting:
            // a partition is all the hierarchy needs, and it is linear.
            val middle = middleOf(start, count, axis)
            var low = start
            var high = start + count - 1
            while (low <= high) {
                if (centroid(index[low], axis) <= middle) {
                    low++
                } else {
                    val swap = index[low]
                    index[low] = index[high]
                    index[high] = swap
                    high--
                }
            }
            var leftCount = low - start
            if (leftCount == 0 || leftCount == count) leftCount = count / 2
            // Fill the placeholder first so the children are laid out after it.
            nodes += Node(minX, minY, minZ, maxX, maxY, maxZ, -2, -2, start, count)
            val left = build(start, leftCount)
            val right = build(start + leftCount, count - leftCount)
            nodes[node] = Node(minX, minY, minZ, maxX, maxY, maxZ, left, right, start, count)
            return node
        }

        private fun middleOf(start: Int, count: Int, axis: Int): Float {
            var low = Float.POSITIVE_INFINITY
            var high = Float.NEGATIVE_INFINITY
            for (slot in start until start + count) {
                val value = centroid(index[slot], axis)
                low = min(low, value)
                high = max(high, value)
            }
            return (low + high) * 0.5f
        }

        private fun centroid(triangle: Int, axis: Int): Float {
            val base = triangle * 9 + axis
            return (vertices[base] + vertices[base + 3] + vertices[base + 6]) / 3f
        }

        /** The distance from a point to the nearest triangle's surface, or null when none is closer. */
        fun nearestPoint(
            contactMm: Float,
            x: Float, y: Float, z: Float, within: Float,
        ): Float? {
            var best = within
            var found: Float? = null
            val stack = IntArray(64)
            var top = 0
            stack[top++] = 0
            while (top > 0) {
                val node = nodes[stack[--top]]
                if (node.count == 0) continue
                if (boxDistance(node, x, y, z) >= best) continue
                if (node.left < 0) {
                    for (slot in node.start until node.start + node.count) {
                        val base = index[slot] * 9
                        val distance = pointTriangle(
                            x, y, z,
                            vertices[base], vertices[base + 1], vertices[base + 2],
                            vertices[base + 3], vertices[base + 4], vertices[base + 5],
                            vertices[base + 6], vertices[base + 7], vertices[base + 8],
                        )
                        if (distance >= contactMm && distance < best) {
                            best = distance
                            found = distance
                        }
                    }
                } else {
                    if (top + 2 > stack.size) break
                    stack[top++] = node.left
                    stack[top++] = node.right
                }
            }
            return found
        }

        private fun boxDistance(node: Node, x: Float, y: Float, z: Float): Float {
            val dx = max(0f, max(node.minX - x, x - node.maxX))
            val dy = max(0f, max(node.minY - y, y - node.maxY))
            val dz = max(0f, max(node.minZ - z, z - node.maxZ))
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    private class EdgeTree(mesh: StlMesh, private val contactMm: Float) {
        val edges: List<FloatArray>
        private val index: IntArray
        private val nodes: MutableList<Node> = ArrayList()

        init {
            val source = mesh.interleavedVertices
            val list = ArrayList<FloatArray>(mesh.triangleCount * 3)
            for (triangle in 0 until mesh.triangleCount) {
                val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
                for (corner in 0 until 3) {
                    val from = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                    val to = base + ((corner + 1) % 3) * MeshSolidBuilder.FLOATS_PER_VERTEX
                    list += floatArrayOf(
                        source[from], source[from + 1], source[from + 2],
                        source[to], source[to + 1], source[to + 2],
                    )
                }
            }
            edges = list
            index = IntArray(edges.size) { it }
            if (edges.isNotEmpty()) build(0, edges.size)
        }

        private inner class Node(
            val minX: Float, val minY: Float, val minZ: Float,
            val maxX: Float, val maxY: Float, val maxZ: Float,
            val left: Int, val right: Int,
            val start: Int, val count: Int,
        )

        private fun build(start: Int, count: Int): Int {
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var minZ = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var maxZ = Float.NEGATIVE_INFINITY
            for (slot in start until start + count) {
                val edge = edges[index[slot]]
                for (end in 0 until 2) {
                    minX = min(minX, edge[end * 3])
                    maxX = max(maxX, edge[end * 3])
                    minY = min(minY, edge[end * 3 + 1])
                    maxY = max(maxY, edge[end * 3 + 1])
                    minZ = min(minZ, edge[end * 3 + 2])
                    maxZ = max(maxZ, edge[end * 3 + 2])
                }
            }
            val node = nodes.size
            if (count <= LEAF_EDGES) {
                nodes += Node(minX, minY, minZ, maxX, maxY, maxZ, -1, -1, start, count)
                return node
            }
            val sizeX = maxX - minX
            val sizeY = maxY - minY
            val sizeZ = maxZ - minZ
            val axis = when {
                sizeX >= sizeY && sizeX >= sizeZ -> 0
                sizeY >= sizeZ -> 1
                else -> 2
            }
            val middle = (lowestMiddle(start, count, axis) + highestMiddle(start, count, axis)) * 0.5f
            var low = start
            var high = start + count - 1
            while (low <= high) {
                if (middleOf(index[low], axis) <= middle) {
                    low++
                } else {
                    val swap = index[low]
                    index[low] = index[high]
                    index[high] = swap
                    high--
                }
            }
            var leftCount = low - start
            if (leftCount == 0 || leftCount == count) leftCount = count / 2
            nodes += Node(minX, minY, minZ, maxX, maxY, maxZ, -2, -2, start, count)
            val left = build(start, leftCount)
            val right = build(start + leftCount, count - leftCount)
            nodes[node] = Node(minX, minY, minZ, maxX, maxY, maxZ, left, right, start, count)
            return node
        }

        private fun middleOf(edge: Int, axis: Int): Float =
            (edges[edge][axis] + edges[edge][3 + axis]) * 0.5f

        private fun lowestMiddle(start: Int, count: Int, axis: Int): Float {
            var low = Float.POSITIVE_INFINITY
            for (slot in start until start + count) low = min(low, middleOf(index[slot], axis))
            return low
        }

        private fun highestMiddle(start: Int, count: Int, axis: Int): Float {
            var high = Float.NEGATIVE_INFINITY
            for (slot in start until start + count) high = max(high, middleOf(index[slot], axis))
            return high
        }

        fun nearestEdge(edge: FloatArray, within: Float): Float? {
            var best = within
            var found: Float? = null
            val stack = IntArray(64)
            var top = 0
            stack[top++] = 0
            while (top > 0) {
                val node = nodes[stack[--top]]
                if (node.count == 0) continue
                if (boxEdgeDistance(node, edge) >= best) continue
                if (node.left < 0) {
                    for (slot in node.start until node.start + node.count) {
                        val other = edges[index[slot]]
                        val distance = segmentDistance(edge, other)
                        if (distance >= contactMm && distance < best) {
                            best = distance
                            found = distance
                        }
                    }
                } else {
                    if (top + 2 > stack.size) break
                    stack[top++] = node.left
                    stack[top++] = node.right
                }
            }
            return found
        }

        private fun boxEdgeDistance(node: Node, edge: FloatArray): Float {
            // A cheap lower bound: the distance between the boxes along each axis,
            // and the edge's own extent taken as a box.
            val ex0 = min(edge[0], edge[3])
            val ex1 = max(edge[0], edge[3])
            val ey0 = min(edge[1], edge[4])
            val ey1 = max(edge[1], edge[4])
            val ez0 = min(edge[2], edge[5])
            val ez1 = max(edge[2], edge[5])
            val dx = max(0f, max(node.minX - ex1, ex0 - node.maxX))
            val dy = max(0f, max(node.minY - ey1, ey0 - node.maxY))
            val dz = max(0f, max(node.minZ - ez1, ez0 - node.maxZ))
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    /** The distance from a point to a triangle's surface. */
    private fun pointTriangle(
        px: Float, py: Float, pz: Float,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
    ): Float {
        val abx = bx - ax
        val aby = by - ay
        val abz = bz - az
        val acx = cx - ax
        val acy = cy - ay
        val acz = cz - az
        val apx = px - ax
        val apy = py - ay
        val apz = pz - az
        val d1 = abx * apx + aby * apy + abz * apz
        val d2 = acx * apx + acy * apy + acz * apz
        if (d1 <= 0f && d2 <= 0f) return length(apx, apy, apz)
        val bpx = px - bx
        val bpy = py - by
        val bpz = pz - bz
        val d3 = abx * bpx + aby * bpy + abz * bpz
        val d4 = acx * bpx + acy * bpy + acz * bpz
        if (d3 >= 0f && d4 <= d3) return length(bpx, bpy, bpz)
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0f && d1 >= 0f && d3 <= 0f) {
            val v = d1 / (d1 - d3)
            return length(px - (ax + abx * v), py - (ay + aby * v), pz - (az + abz * v))
        }
        val cpx = px - cx
        val cpy = py - cy
        val cpz = pz - cz
        val d5 = abx * cpx + aby * cpy + abz * cpz
        val d6 = acx * cpx + acy * cpy + acz * cpz
        if (d6 >= 0f && d5 <= d6) return length(cpx, cpy, cpz)
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0f && d2 >= 0f && d6 <= 0f) {
            val w = d2 / (d2 - d6)
            return length(px - (ax + acx * w), py - (ay + acy * w), pz - (az + acz * w))
        }
        val va = d3 * d6 - d5 * d4
        if (va <= 0f && (d4 - d3) >= 0f && (d5 - d6) >= 0f) {
            val w = (d4 - d3) / ((d4 - d3) + (d5 - d6))
            return length(px - (bx + (cx - bx) * w), py - (by + (cy - by) * w), pz - (bz + (cz - bz) * w))
        }
        val denominator = 1f / (va + vb + vc)
        val v = vb * denominator
        val w = vc * denominator
        return length(
            px - (ax + abx * v + acx * w),
            py - (ay + aby * v + acy * w),
            pz - (az + abz * v + acz * w),
        )
    }

    /** The distance between two segments. */
    private fun segmentDistance(first: FloatArray, second: FloatArray): Float {
        val d1x = first[3] - first[0]
        val d1y = first[4] - first[1]
        val d1z = first[5] - first[2]
        val d2x = second[3] - second[0]
        val d2y = second[4] - second[1]
        val d2z = second[5] - second[2]
        val rx = first[0] - second[0]
        val ry = first[1] - second[1]
        val rz = first[2] - second[2]
        val a = d1x * d1x + d1y * d1y + d1z * d1z
        val e = d2x * d2x + d2y * d2y + d2z * d2z
        val f = d2x * rx + d2y * ry + d2z * rz
        var s = 0f
        var t = 0f
        if (a <= 1e-12f && e <= 1e-12f) return length(rx, ry, rz)
        if (a <= 1e-12f) {
            t = (f / e).coerceIn(0f, 1f)
        } else {
            val c = d1x * rx + d1y * ry + d1z * rz
            if (e <= 1e-12f) {
                s = (-c / a).coerceIn(0f, 1f)
            } else {
                val b = d1x * d2x + d1y * d2y + d1z * d2z
                val denominator = a * e - b * b
                s = if (denominator > 1e-12f) ((b * f - c * e) / denominator).coerceIn(0f, 1f) else 0f
                t = (b * s + f) / e
                if (t < 0f) {
                    t = 0f
                    s = (-c / a).coerceIn(0f, 1f)
                } else if (t > 1f) {
                    t = 1f
                    s = ((b - c) / a).coerceIn(0f, 1f)
                }
            }
        }
        return length(
            (first[0] + d1x * s) - (second[0] + d2x * t),
            (first[1] + d1y * s) - (second[1] + d2y * t),
            (first[2] + d1z * s) - (second[2] + d2z * t),
        )
    }

    private fun length(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    private const val LEAF_TRIANGLES = 8
    private const val LEAF_EDGES = 8
}
