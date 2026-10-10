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

## Still to settle

- The small hook at the upper right of the sketch: the beam's anchor, or a second catch?
- Which parameters are exposed (beam length, thickness, hook depth, hook angle) versus derived
  from a single "scale" slider.
- Whether the joint is ever releasable, and whether that gets a tab.
- Print separately (assumed) versus print in place.
