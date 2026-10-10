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

## Still to settle

- The small hook at the upper right of the sketch: the beam's anchor, or a second catch?
- Which parameters are exposed (beam length, thickness, hook depth, hook angle) versus derived
  from a single "scale" slider.
- Whether the joint is ever releasable, and whether that gets a tab.
- Print separately (assumed) versus print in place.
