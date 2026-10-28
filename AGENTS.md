# Lumis

Lumis is an Android-first, offline projection-mapping app. Implement small runnable
milestones, starting with one playing video, GPU-backed perspective-correct quad
mapping, four draggable corners, Reset, and optional FPS. A design is not permission
to implement the entire MVP or roadmap at once.

## Design workflow

- Read [the design handoff](design/README.md) before UI or asset work.
- Google Stitch is the design source of truth. Use the existing Lumis project
  `9969649650324268552` and design system `assets/e7e76232818f47c9be8016bebcc921aa`.
- Before implementing a feature, screen, dialog, state, or branded asset without
  an existing design, use Google Stitch to create it in that project. Do not invent
  a separate visual language or silently substitute an undesigned screen.
- Search for Stitch tools, inspect the current references in
  [the screen manifest](design/stitch/screens.json), and reuse the design system.
  Update the manifest and handoff after new designs or revisions.
- Current approved scope is MVP screens and brand assets only. Future camera,
  calibration, effects, external-display, and other roadmap features require
  explicit scope approval and their own Stitch designs before implementation.
- Stitch HTML is a visual reference, not the Android renderer. Preserve one
  authoritative scene and one rendering pipeline for editor preview and output.
  Never put editor chrome, diagnostics, or unavailable-media labels in clean output.
