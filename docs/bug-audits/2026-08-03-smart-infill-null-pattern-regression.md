# Smart Infill null-pattern regression

## Device symptom

After filaSim completed and the modifier package returned to EnderSlicer, import failed with:

> filaSim returned an infill pattern that EnderSlicerCura cannot reproduce: `null`

The same screen also exposed browser-only OrcaSlicer, Bambu Studio, and PrusaSlicer handoff controls even though the Android host only supports the modifier-package route.

## Root cause

A restored or cached filaSim WebView could retain a nullable/retired pattern value. The generated filaSim exporter was hardened, but an older cached web bundle could still call the Android bridge with `basePattern: null`.

## Repair

- Normalize modifier metadata at the Android JavaScript bridge boundary.
- Emit the pinned solver contract: sparse `cubic`, graded full-density `rectilinear`, and validated binary `rectilinear`/`concentric`.
- Version the Android bridge script URL to prevent reuse of the previous cached script.
- Render one Android-specific **Apply Smart Infill** action and omit the slicer selector, 3MF project action, and `Hand off` heading.
- Keep strict Kotlin package validation so unrelated or unsupported metadata still fails closed.

## Expected result

Pressing **Apply Smart Infill** returns a valid modifier package to EnderSlicer without exposing or requiring a slicer choice. A stale nullable browser value cannot cross the Android handoff boundary.
