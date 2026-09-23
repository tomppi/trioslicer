# Arc-overhang native port notice

The native arc-overhang path generator is a CuraEngine-oriented
reimplementation derived from:

- Steven McCulloch's `stmcculloch/arc-overhang` research and GPL-3.0 code.
- The Multiplex arc-overhang implementation in `rvmn/SuperPleccer`, licensed
  under GNU AGPL-3.0.

The port retains the central Multiplex behavior: begin from material supported
by the previous layer, retain one centre for as long as possible, expand
successive arcs outward, and clip every arc against the real bottom-skin
polygon. The surrounding integration is written for CuraEngine's `Shape`,
`OpenLinesSet`, bridge-skin classification, `GCodePathConfig`, and
`LayerPlan` APIs.

This derived source is distributed with enderslicercura and CuraEngine under
GNU AGPL-3.0-or-later. The upstream projects and authors are not affiliated
with or responsible for enderslicercura.
