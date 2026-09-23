# UI style guide

Conventions for EnderSlicerCura's Compose UI. The app targets phones and
foldables with a dense, tool-first layout: small screens, gloved-ish use,
few chrome elements. Follow the rules below for new or modified screens.

The current visual language ("engineering cockpit", dark-first) and the
information architecture it implements are specified in
[docs/ux-redesign/DESIGN_PROPOSAL.md](../ux-redesign/DESIGN_PROPOSAL.md) -
read that document before starting a new screen.

## Navigation

- Four persistent destinations in the bottom NavigationBar:
  Plate · Settings · Print · More (see AppTab in EnderSlicerApp.kt).
  Never add a fifth destination; extend More instead.
- Full-screen destinations (Settings, Print, More) are tabs, not modal
  sheets. Keep ModalBottomSheet only for quick, momentary pickers
  (model tools, layer events, non-planar/conical options, Smart Infill).
- The Plate top bar owns Import (STL) and the Plate overflow menu (model
  position, mesh limit, clear plate). Cura `.curaprofile` and `.3mf`
  settings imports belong to the Cura settings sheet (Settings tab), next
  to the settings they replace; anything less frequent belongs in the More
  hub.
- More > Printer is a full-screen destination with a back arrow (state
  `printerScreenOpen`); it opens the safety checklist + machine profile.
  Never turn it back into a modal sheet.
- First-run onboarding is one-shot and skippable (OnboardingStore). It only
  ever sets machine values that already live in SlicerSettings.
- Expanded layouts (maxWidth >= 600 dp, unfolded foldables) split the Plate
  tab into viewer + SessionPanel. Reuse SessionPanel; do not stack a second
  panel on folded widths.

## Theme

- Use EnderSlicerTheme's per-engine palettes from EnderSlicerTheme.kt:
  Cura = blue accent, PrusaSlicer = orange accent ("Cura is blue,
  PrusaSlicer is orange" is a product rule). Do not switch to wallpaper
  dynamic color: the engine identity is part of the product.
- The selected engine (SlicerEngineStore) drives theme accent, profile
  formats, G-code dialect and engine binary; profiles are NEVER merged
  across engines. The switcher lives at the top of the Settings tab
  (EngineSelectorCard) and changes the whole app instantly.
- Semantic colors come from MaterialTheme.colorScheme:
  primary (actions/active), error (danger), outlineVariant (hairline
  dividers), surfaceVariant (icons/shortcuts).
- Status/story colors used in legends (infill, supports, travel, warnings)
  may use the legibility-safe constants documented in the proposal.

## Layout

- Use the 4 px grid: spacing from EnderSlicerDimens (Space2, Space4,
  Space6, Space8, Space12, Space16, Space24) instead of ad-hoc values.
- Cards: 12 dp padding (EnderSlicerDimens.CardPadding); sheets: 12 dp
  padding (EnderSlicerDimens.SheetPadding), 8 dp inner spacing.
- Minimum touch target 48 dp (EnderSlicerDimens.TouchTarget); sliders and
  timeline controls get at least a 36-40 dp interactive height.
- Full-width primary actions centered; secondary actions in the same row
  with equal weight.

## Buttons

- Exactly three tiers: filled Button (one primary action per screen),
  outlined OutlinedButton (secondary actions), TextButton (tertiary /
  inline actions).
- Disabled buttons rely on Material defaults, but always pair a disabled
  primary-looking control with a one-line caption explaining what is
  missing (e.g. "Slice a model first to export validated G-code").

## Text

- titleLarge for the top-bar destination name, bodySmall for the
  top-bar context line; titleSmall for card titles, bodySmall for
  content, labelSmall for helper text - never more than two
  grey/onSurfaceVariant levels per card.
- Error and warning text uses MaterialTheme.colorScheme.error; capturing
  the count/purpose in a single line ("Cura compatibility warnings: 3").
- Long explanatory copy belongs behind an info affordance or in a legend
  with color swatches, not as a paragraph in the control flow.

## Fields

- OutlinedTextField labels, not placeholder-only fields. Right-align
  numeric values with a unit suffix where useful.
- Numbers: format with the device locale via NumberFormat (no grouping)
  and trim trailing zeros through the locale decimal separator - never
  mutate a formatted string with "."-based trimming (it leaves dangling
  separators like "115,").

## Provenance

- Every editable setting shows where its value came from. Use origin
  badges: PROFILE (from the Cura profile), IMPORTED (project or
  configuration snapshot), APP (user override) - and keep the color
  coding in sync with CategorizedSettingsSheet / MachineSettingsSheet.
- Collapsed settings sections show a one-line summary of their values
  instead of an empty header.

## Status vs help

- Transient status messages (restore results, operation outcomes) are
  separate from static gesture help. Gesture hints are dismissible and
  should be shown once per session.

## States

- Every list/empty state says what to do next ("Import an STL from the
  Import button").
- Printer-dependent actions are disabled (with a reason caption) unless the
  printer is operational; never rely on the server's 400/409 alone.

## Viewers

- The model-view turntable camera is shared by Model, Layers and
  nozzle-path modes. When a SurfaceView is recreated (tab switch,
  rotation), restore the last orbit via ModelSurfaceView.restoreOrientation
  before the first frame.
- Nozzle-path geometry uses the per-move slice data (flow width, layer
  height). Shading must be analytic per face and stable at low zoom -
  never reintroduce vertex-interpolated directional tint that moires
  (see the renderer spec in DESIGN_PROPOSAL.md).
