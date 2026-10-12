# Snap-fit hook library

Ready-made snap joints, used by the snap panel's library hook style.

**Source:** "Snap-Fit Joint Test Prints - Eight Versions" by kaje,
<https://www.printables.com/model/151102-snap-fit-joint-test-prints-eight-versions> —
inspired by MIT's *Snap-Fit Joints for Plastics - A Design Guide*
(<https://fab.cba.mit.edu/classes/S62.12/people/vernelle.noel/Plastic_Snap_fit_design.pdf>).

**Licence:** Public Domain (the model page's Creative Commons declaration: sharing
without attribution, remix culture, commercial use, free cultural works). No
attribution is required; this note is here because the author earned it.

## The shapes

Each shape is a pair, and both pieces are in the same assembled coordinates - the
author's 3MF files move them apart only to print them. Nothing has to be lined up.

| shape | pieces | what it is |
|---|---|---|
| cantilever01 | cantilever01-top / -bottom | the cantilever tapered across its width |
| cantilever02 | cantilever02-top, **cantilever01-bottom** | the same catch; the cantilever tapered across its thickness |
| cantileverridgid | cantileverridgid-top / -bottom | a cantilever with rigid lugs |
| separable | separable-top / -bottom | squeeze the two buttons to open it |
| separable02 | separable02-top / -bottom | push the lugs through the bottom's openings |
| annular | annular-annularring / annular-annularsnap | segmented annular snap, round |
| annularcontinuous | annularcontinuous-top / -bottom | a round snap whose joint is almost invisible |
| torsion03 | torsion03-top / -bottom | a torsion bar; the author's own note says it needs work |

The second piece of the cantilever, separable and torsion shapes is a whole
housing box, so the app grafts only the local catch: the housing is clipped to a
box around the hook's own reach - the material that crosses the seam - and the
mate's own material inside that box is replaced by the housing's shape there.
That is what gives the hooks the author's own slot, wall and ledge to run in on
a part that is solid where the tap landed; unioning the housing onto a solid
mate would bury them, and cutting nothing would leave the printed halves
colliding.

The second piece of the two round shapes (annular, annularcontinuous) is a solid
bead rather than a housing: it is grafted as it stands, and the mate grows it.

The app measures each pair's own smallest gap - contacts aside, because the
author assembled the pieces seated - and reports it at the scale the joint is
being printed at. Those clearances are the library's, not the app's: the app did
not design the springs, so it reports no strain figure for them.

The eight shapes' own mating planes, where the seam's plane goes, are measured
from the files: z = 10 for cantilever01, cantilever02, cantileverridgid,
separable and separable02, z = 15 for torsion03, and the collar's own base face
for the round two (z = 9.5 for annular, z = 1.433 for annularcontinuous).
