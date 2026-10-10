# Snap fit: design decisions so far

Working notes for the split-and-snap feature, recorded while it is being designed so the
decisions survive the conversation. Phase one (split any model in two, with a live cut
preview) is being built; the snap itself is phase two.

## The feature

Split any model in two with a slider that makes the model disappear from the top or the
side - the layer viewer's interaction, but the slider drives a cut plane. The user then
places a snap fit by tapping a point on the model, scales it, and the app puts it in. The
halves print separately and are pushed together.

## What the joint is (confirmed against the user's sketch)

A cantilever with a barb on its free end, lying across the seam, hidden inside the joint -
nothing protrudes past the outline of either part. The barb's sloped face is the ramp that
cams the beam aside as the parts are pushed together; its square back face is what resists
pulling apart. The beam is unioned into one half, and its matching pocket plus clearance is
cut out of the other. The result is one-way and permanent unless a release tab is added.

THE MATING FACE IS NOT FLAT, and that is the design rather than an accident: the pocket and
the barb are what shape it, and the steps in it are the catch.

## Constraints

- The split is a plane, and that plane is the ASSEMBLY DIRECTION. The seam may be as stepped
  or curved as the model makes it, but nothing may undercut against that direction except the
  barb, which is the intended undercut.
- Clearance between the mating faces (about 0.2 mm default) so the halves slide rather than
  gall, kept separate from the snap's own clearance. Most printed snap fits fail here.
- Registration: two or three shallow keys/steps cut into the seam stop the halves sliding
  sideways or rocking once snapped. Optional, cheap with booleans.
- The user is responsible for the result. No validation, no refusal: if the joint breaks
  through a wall it still prints. A root-strain hint is possible later but is not wanted now.

## The first version uses a SQUARE

Decided: start with a square - a square key in the mating surface and an angular (square)
barb, matching the sketch. It registers rotationally, which a single circle cannot, and it is
two boxes rather than new geometry. One practical caveat to expect: FDM printers bulge at
inside corners (roughly the nozzle radius), so a square socket fits TIGHTER than a round one
at the same clearance. If the halves do not go together, more clearance or a small corner
chamfer is the fix, in that order.

## Interface shape, as an option later (user's idea: "not a triangle but a circle")

- Triangle or stepped key: registers rotationally, but a sharp inner corner is a stress riser
  and the likeliest place for a crack to start.
- Circle: prints better and has no sharp corners, BUT A SINGLE CIRCLE DOES NOT STOP ROTATION -
  it needs a second key, or the snap alone, or a rounded triangle instead.
- Rounded triangle: registers like a triangle, prints like a circle. Likely the best default.
- The barb's head could equally be angular (as drawn) or round: a ball head in a round groove
  snaps more smoothly and needs less clearance to engage. A sphere in a spherical socket is a
  distinct, very printable joint type.

## Whole-seam registration (the default registration, from the user's 3D Slash reference)

The user's reference shows a 64 mm cube with a rectangular rebate cut into one face and the
other part carrying a boss that mates into it. That is REGISTRATION over the whole seam
rather than at one point, and it is now the default: offset the mating faces so the beam's
half carries a shallow boss across the whole face (a box unioned on, inset by a rim) and the
other half the matching recess (a box subtracted, inset by the rim and clearanced). The
square key stays as a local feature, but it is sized from the part and sunk flush, so it
reads as a catch rather than a block.

- Sized from the part, never from millimetres: the key is about an eighth of the seam's
  width, the beam about a fifth of it across and a quarter of the mate's material long, the
  step around an eighth of the material deep. The parameters are ceilings; the part decides.
- The step's solids are separate meshes, because a single mesh holding two overlapping shells
  is not manifold - the engine unions the boss on and cuts the recess out.
- It registers in both sideways axes over the entire seam and is far more forgiving to print
  than a small key on a large face. Several local keys at the corners approximate it.
- Retention is still the ramped cantilever: the step registers, the barb holds.

## The joint is a ladder, not a yes/no

A complicated seam - a boat hull, a thin curved wall - cannot always carry the
whole joint. The pad is a whole-seam feature, and a hollow cross-section leaves
it sitting over air; the square key can be wider than the wall it is cut into.
So the generator fits the fullest joint the seam's own cross-section actually
takes, and refuses only when nothing at all fits:

1. **Full** - whole-seam pad and matching recess, square key, ramped cantilever.
2. **Simplified** - the key and the cantilever, no pad. Often the only thing a
   thin curved wall can take.
3. **Minimal** - the bare ramped cantilever, shrunk to the material there is.

Which rung was used is shown in the panel, with the reason the fuller ones were
dropped ("the seam is hollow where the whole-seam pad would sit; key and
cantilever only"). The same control pins the full joint, so the app never blocks
the user: it says what it is placing and why, and he decides.

THIS IS A STEPPING STONE. A complicated model gets the simple solution first;
a better joint for it can come later. The ladder is not a verdict on what any
model deserves, and the lighter rungs are not a permanent limit.

## The pad follows the material, not the face

The whole-seam pad began as a box across the seam. On a solid part that is the
right shape; on a hollow one it is a wall standing in the middle of the void,
which is what a forced full joint on a Benchy used to place. So the pad box is
intersected with the half's own solid and what survives is the material's own
cross-section - a rim that follows the hull and the cabin walls. That rim is
inset a little (a share of the local material, never more than a third of the
thinnest wall found, so a thin wall keeps a rim or the pad is refused), and the
recess is built from the same contoured shape plus the clearance, so the two
still mate: a proper contoured tongue and groove.

If the wall cannot keep a rim - a Benchy hull is about a millimetre, and the
boolean engine itself will not resolve a shell that thin - the pad is refused
and the ladder drops a rung and says why ("the wall here is too thin for a pad
with a rim; a bare cantilever only"). The refusal is the ladder working, not a
failure.

## Queued: multiple barbs along one beam (cable-tie principle)

Asked for and NOT yet built: several barbs on one cantilever, each a 45 degree
ramp with a square catch, so the mate's pawl catches whichever one it reaches
and the joint offers several engagement depths from one lever. Pitch derived
from the lip depth (ramp run plus a lip-depth flat), 1 to 3 barbs, default 1 so
nothing changes unless asked.

The design conclusion reached while working the geometry, so the next attempt
does not have to rediscover it:

- A MATCHING SERIES of grooves in the mate does NOT give distinct stable
  clicks: with the same pitch on both parts, either every barb nests at once
  (one stable position) or none does. The cable tie works differently.
- The correct shape is ONE PAWL on the mate and SEVERAL TEETH on the beam. The
  mate's single step (the pawl) sits at the DEEPEST catch, and its deep slot
  spans from the pocket mouth back past the shallowest barb, so every barb but
  the one being caught is inside the slot. As the parts close, the beam's
  barbs pass under the pawl one at a time and spring out: the click positions
  are beamLength - catch_i, the first click is the barb nearest the tip
  (loose), the last is the deepest (seated).
- The panel must name the engaged click ("first click - loose" ... "seated -
  tight") and, if there is no assembly-depth control, report the seated one as
  the designed engagement plus the list of click depths; the risk of this
  design is a half-closed joint that cannot say where it stopped.
- Deflection accumulates while the lip rides each ramp, so the strain readout
  takes N x lipDepth as the worst case; the deepest engagement must still clear
  the material it bends through.

This joins the multi-joint work (several joints on one seam, Loose/Tight
clearance stepping, socket-mouth chamfer, per-joint facing) - also asked for
and not yet built.

## Built: several joints, stepping, the socket's ramp and the multi-barb beam

All four landed together, and each one changed something the others depend on.

- SEVERAL JOINTS. A tap adds a joint to the pair rather than moving the one
  joint; the panel lists them, selects one, moves it, removes it or clears the
  lot. Every joint is BUILT on the pair's own faces - so its rung is the
  material's answer about that place on the seam, not about how many joints
  came before it - and the BOOLEANS then run one joint after another over the
  running halves, which is what the printed parts are. Nothing is ever dropped
  quietly: a joint the material will not take, a pair whose pockets cross or
  leave less than a beam-thickness of wall between them, and a pocket that
  removes no material because a neighbour already took it are three different
  sentences, and each one stops the preview and names the joint.
- AUTO SPREAD. Two to four joints, evenly along the rim of the cross-section
  the two halves share at the seam - the same contoured material the pad is cut
  from, read as an outline rather than a tongue. Rasterised rather than sampled
  (a real seam's face carries thousands of triangles), then walked by
  farthest-point sampling so a long thin band gets joints along its length
  instead of clustered where the walk started. Spacing is the joint's own
  footprint across the seam (beam, teeth and key - the pad is deliberately
  left out, because a whole-seam pad is the seam's own cross-section and two
  full joints share one) plus one beam-thickness of wall. The app proposes and
  the user decides: every proposed point is checked by building the joint it
  would carry, and a point the material refuses is reported and left out.
- LOOSE/TIGHT STEPPING. Two steps, 0.06 mm of clearance apart - about a third
  of a 0.4 mm nozzle's width, near the limit FDM resolves - assigned by
  placement order (first loose, next a step tighter) and changeable per joint.
  CLEARANCE ONLY: the hook's length, thickness, lip, pitch, angles, teeth and
  facing are identical in both steps. The mating face's own relief is not part
  of it: that is how the two faces sit, not how the joint fits.
- SOCKET-MOUTH CHAMFER. A 45-degree funnel on the pocket's mouth - the FDM
  overhang limit, so the roof prints - opening at the mating face and closing
  onto the pawl. It is the lead-in in the hole rather than on the hook, which
  is what lets a square-faced hook cam in, and therefore what makes facing a
  choice. It is NOT a substitute for the room the beam needs to bend.
- FACING. Every joint on a seam faces the same way by default and flips as a
  pair; mixing is an explicit switch, and only then can one joint turn. An
  opposite joint is the beam mirrored about its own centreline, pocket and
  deflection room included: the catch still faces back along the same assembly
  direction, so a mixed pair assembles the same way and locks the slide along
  the seam both ways.
- MULTI-BARB, as the ratchet conclusion said: ONE PAWL on the mate, 1 to 3
  TEETH on the beam, default 1 so the one-tooth beam is byte for byte the beam
  that was built before. Pitch = the lead-in's run plus a lip-depth flat, the
  tip-most tooth's lead-in ends at the beam's tip, and the pawl sits one
  clearance in front of the DEEPEST catch, exactly where the single-barb
  pocket's step has always been. The pocket is narrow from its mouth to the
  pawl and wide beyond it, so every tooth but the one being caught is inside
  the wide slot. Clicks are therefore (N - 1 - i) x pitch + lipClearance apart,
  the tooth nearest the tip catching first and the deepest catch seating the
  halves; the panel names the seated click and lists the rest.

## The deflection gap was missing, and the single-barb joint could not close

Found while adding the socket's ramp, kept because it is the sort of thing the
next change will trip over again.

The pawl rides the tooth's ramp and pushes the beam bodily aside by the tooth's
own height less the clearance it already has - lipDepth - lipClearance, order
half a millimetre. The committed pocket's floor sat one clearance below the
beam on BOTH sides, so the beam had 0.2 mm of room to bend half a millimetre:
the tip would have been pressed into the pocket's floor for the whole of its
travel and the halves would never have closed. Nothing in the geometry said so;
the pocket was closed, the beam was closed, and every check passed.

The fix is the room itself: the pocket's floor is dropped by the sink plus a
clearance, on the side the beam bends towards, and mirrored with the facing. The
mate's own material below the anchor is the ceiling, and a ceiling that bites is
reported as a clamp with the numbers rather than left to jam. The socket's ramp
helps the hook along; it does not replace this.

## Still to settle

- The small hook at the upper right of the sketch: the beam's anchor, or a second catch?
- Which parameters are exposed (beam length, thickness, hook depth, hook angle) versus derived
  from a single "scale" slider.
- Whether the joint is ever releasable, and whether that gets a tab.
- Print separately (assumed) versus print in place.
