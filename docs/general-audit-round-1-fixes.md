# General application audit — round 1 fixes

This revision resolves the 13 retained findings from the first whole-application audit.

Key safety changes:

- final G-code publication uses an exhaustive command-family policy and rejects unmodeled M commands and macros;
- Layers and print-time estimates apply M220 feed factors consistently with Path;
- RepRapFirmware parameterized G10 is not treated as firmware retraction;
- workspace, Cura import, OctoPrint credential, Smart Infill pointer, and SAF export state have serialized or recoverable transactions;
- picker and tool results received during process restoration are queued for exactly-once replay;
- ignored ZIP entries consume global entry, inflation, ratio, time, and cancellation budgets;
- Smart Infill is removed from the runtime slice snapshot while source validation is in progress;
- plaintext API-key drafts are transient and cleared when endpoint identity changes.

Rejected slices and failed exports do not publish a completed artifact. The pull request remains draft pending physical printer validation.
