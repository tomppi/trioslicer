# Whole-app bug audit

This audit covers the current slicer, Cura import and persistence paths, model and G-code lifecycle, layer preview/event rewriting, BumpMesh handoff, and the new OctoPrint subsystem.

## Fixed in this pass

- Parse OctoPrint's documented human-readable `free` storage field as bytes while retaining numeric compatibility.
- Validate the stored API key by actually decrypting it at startup instead of treating an unreadable preference as a usable credential.
- Keep Application Keys polling on the configured OctoPrint origin and handle authorization cancellation without replacing the user-facing status with a coroutine cancellation error.
- Use the long timeout only for G-code uploads; ordinary commands return to the normal API timeout.
- Copy validated G-code to a checked immutable upload snapshot so re-slicing or editing layer events cannot alter a file while it is being transmitted.
- Serialize printer commands and reject motion, homing, extrusion, retraction and manual terminal commands unless the printer is operational and idle. Active jobs also block serial disconnects.
- Claim upload state before launching the transfer, reject duplicate requests, and keep unrelated command errors from clearing active upload or refresh indicators.
- Require confirmation for status-page start and restart actions, not only file-browser and upload print starts.
- Decode webcam frames away from the Compose main thread.
- Preserve a user's edited auto-connect checkbox instead of overwriting it on every status poll.
- Exclude encrypted OctoPrint preferences from Android backup and device transfer because the Android Keystore key is device-bound.
- Block normal app interaction until persisted Cura configuration restoration finishes, preventing a late restore from overwriting a fast user action after launch.

## Verified existing behavior

- Model imports, model transforms, Cura profile/project imports and print-setting changes invalidate the current G-code path before OctoPrint can upload it.
- Failed imports keep the last valid model and G-code instead of partially committing new state.
- Cura archive/XML input limits, STL size/triangle limits, G-code validation and layer-event rebuilding remain isolated from the OctoPrint subsystem.
- OctoPrint credentials are sent only to the configured origin; external webcam snapshot URLs and redirects do not receive the API key.

## Remaining device validation

Automated tests can verify parsing, compilation and state-independent logic. A real OctoPrint server and printer are still required to validate authorization UI, reverse-proxy behavior, serial connection options, webcam compatibility, command permissions and a guarded small print from upload through completion.
