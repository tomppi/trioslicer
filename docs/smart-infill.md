# Smart Infill / filaSim integration

## Native engine

The structural workflow no longer needs the WebView: `libfilasim_jni.so` (arm64-v8a, built by
`scripts/build-filasim-engine-android.sh` from the same pinned upstream tree and patch chain as the
bundled WebAssembly workspace) runs the voxel FEA, the density optimizer and the modifier export
inside the app, and `FilaSimEngine` drives it from Kotlin with cancellation and live progress. The
engine's session layer lives in `native/filasim/`; `filasim-smoke <model.stl> <out-dir>` runs
import, constraints, solve, optimize and export on a desktop, which is how the native pipeline is
verified without a phone. Results reach the slice through the same v2 metadata contract and
`modifier_NNpct.stl` archive the WebView transport used, so the validation and staging paths below
are unchanged.

EnderSlicerCura packages the pinned filaSim structural-analysis workspace and supports its complete optimized-output workflow: graded or binary infill becomes Cura modifier meshes, while Part Topo becomes a new validated STL model.

On the Plate, **Smart Infill** opens the analysis as a panel over the model rather than a separate screen, because assigning a boundary condition means tapping the part. A tap takes the connected surface within the panel's *Tap radius* of the finger — the crease segmentation the picker used to assign is a face finder, and on a smooth or flat part it reports the whole model as one surface, so the radius (not the patch) is what bounds a tap. Further taps add to the same condition, and **Face** widens it to the whole flat surface. The picked surfaces are tinted on the model — green for the supports the part rests on, orange for the loads it carries, yellow for the condition waiting for a tap — so what is analyzed is what is seen. Each condition's values (a force, moment or bearing vector in X/Y/Z, a pressure, a bedding modulus, a prescribed displacement, a point mass) are edited in its row, and the row title follows them. After an optimization the part is tinted by the density under each surface — the same blue→cyan→yellow→red ramp as the legend beside it, which lists every density with its share of the optimized volume. The same panel sets the material (with presets from the pinned upstream material library, plus whether the layer criterion is scored and whether self-weight acts), the analysis grid resolution, and the goal — stiffest within the budget, as stiff as a uniform print at the same mean infill, or the lightest design that reaches a safety-factor target, with self-supporting infill available as a constraint.

## Workflow

1. Import and position one STL in EnderSlicerCura.
2. Open **Smart Infill**.
3. Define supports and loads by tapping the part, and set the material, budget and mode in the panel.
4. Check the setup, solve the reference part and run an optimization.
5. Choose the output for the selected optimization mode:
   - **Graded/Binary:** export modifier STLs. The Android bridge imports the ZIP directly, without creating a user-visible download.
   - **Part Topo:** export the optimized shape. The Android bridge validates the binary STL and imports it as the new model.
6. For graded or binary output, slice normally. EnderSlicerCura applies the filaSim base density and print assumptions, then loads the regional meshes into CuraEngine with ordered `infill_mesh` and `infill_sparse_density` values.
7. For Part Topo, inspect the replacement geometry, position it if necessary and slice it like any other STL.

A modifier package is bound to the SHA-256 digest of the exact transformed binary STL supplied to filaSim. Importing another model, texturing it, or changing its move/rotation/lay-flat transform invalidates the package. Part Topo output deliberately clears any previous modifier package because it is a different printable geometry.

## Slicing contract

While a graded or binary package is active, the slice uses filaSim's:

- base infill density and pattern;
- layer height and line width;
- perimeter count;
- top and bottom shell count;
- ordered modifier densities.

Adaptive layers are disabled because a different layer-height field would no longer match the analyzed shell thickness. Modifier geometry is already in the displayed model's final printer coordinates and therefore receives only Cura's bed-origin offset, not the source object's affine transform a second time.

## Validation boundaries

The Android host rejects:

- malformed or oversized ZIP archives;
- nested paths or unexpected entries;
- duplicate, non-increasing or out-of-range densities;
- modifier densities at or below the base density;
- structurally invalid binary STL modifier or Part Topo files;
- meshes above the configured triangle limit;
- packages that no longer match the displayed model fingerprint;
- unknown infill patterns instead of silently substituting another pattern.

CuraEngine and final G-code retain the app's existing build-volume, extrusion-temperature, artifact-lifecycle and export validation.

## Limitations

- One printable model and one extruder.
- The Android WebView currently uses filaSim's single-threaded WASM build.
- Cura modifier transport and Part Topo geometry require physical validation across nested regions, thin walls, supports, print directions and different infill patterns.
- filaSim results depend on accurate loads, constraints, material properties, layer adhesion and print orientation. This is an engineering aid, not a certified structural calculation.

## Source and license

The workspace is built from `CNCKitchen/smartInfillGenerator` commit `e7485ec22d4ebe8baca04190404fbb877c90e031` under `AGPL-3.0-only`. The build script, Android bridge, license, source notice and exact dependency manifests are included in this repository or packaged workspace. See [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).
