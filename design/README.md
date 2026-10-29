# Lumis design handoff

## Source of truth and scope

- [Google Stitch project](https://stitch.withgoogle.com/projects/9969649650324268552)
- Project ID: `9969649650324268552`
- Design system: `assets/e7e76232818f47c9be8016bebcc921aa` (Lumis Projection Studio)
- [Machine-readable screen and asset manifest](stitch/screens.json)
- Product basis: the supplied Mobile Projection Mapping App PRD, originally
  titled ProjectMap. The selected product name is **Lumis**.

The user selected **complete MVP screens and branded assets**, not the full
roadmap. Designs do not change the PRD's implementation order: first prove one
continuously playing video mapped to an independently draggable four-corner quad.
No projector is needed for this milestone.

## Visual direction

Dark creative studio: charcoal surfaces, electric violet actions, luminous cyan
geometry, restrained glow in artwork rather than interface text.

| Token | Value |
|---|---|
| Background | `#090B10` |
| Surface | `#141821` |
| Raised surface | `#1C2230` |
| Primary violet | `#A78BFA` |
| Geometry cyan | `#67E8F9` |
| Primary text | `#F4F5FA` |
| Secondary text | `#9CA8BA` |

Use readable sans typography, a 4/8dp spacing rhythm, approximately 16dp card
radii, and at least 48dp touch targets. Generated references use Space Grotesk
and technical monospace accents; lockup descriptors use JetBrains Mono.
Bundle licensed fonts or choose documented system fallbacks for the offline app.
Do not treat remote Google Fonts links in mockup HTML as a runtime dependency.

The brand concept is an original perspective quadrilateral/light-beam mark.
Treat generated brand work as a design candidate, not trademark clearance.

## Current screen inventory

There are **35 current design references**: 32 application screens/states and
three asset/component boards. Some references are sheets or dialogs, not separate
navigation destinations. Their exact resource IDs and export references are in
[the manifest](stitch/screens.json).

| No. | Screen or state |
|---|---|
| 01 | Projects / Home |
| 02 | Projects / Empty |
| 03 | New Project |
| 04 | Project Actions |
| 05 | Rename Project |
| 06 | Delete Project Confirmation |
| 07 | Project Recovery |
| 08 | Editor / Empty Canvas |
| 09 | Editor / Selected Video |
| 10 | Editor / Surface Layers |
| 11 | Editor / Surface Properties |
| 12 | Editor / Viewport Navigation |
| 13 | Rendering Proof / Milestone 0 |
| 14 | Add Surface / Source Choice |
| 15 | Import / Android Photo Picker |
| 16 | Import / Loading and Validation |
| 17 | Import / Unsupported Media |
| 18 | Missing Media / Replace Source |
| 19 | Media Access / Permission Denied |
| 20 | Project Settings |
| 21 | Debug Settings |
| 22 | Editor / Diagnostics |
| 23 | Project Load Error |
| 24 | Renderer or Decoder Failure |
| 25 | Output / Clean Fullscreen |
| 26 | Brand / Logo and App Icon |
| 27 | Brand / Splash and Empty-State Assets |
| 28 | Design System / Components and Interaction States |
| 29 | Editor / Selected Image |
| 30 | Delete Surface Confirmation |
| 31 | Save Failure / Retry |
| 32 | About Lumis |
| 33 | Rendering Proof / Landscape |
| 34 | External display output |
| 35 | Editor / Tap layer selection |

Screen 09 was refined to remove technical jargon and unsolicited debug tools.
Screen 25 was revised because its initial screenshot was not 16:9. Use the
current manifest entries; superseded designs may remain visible in Stitch.
The corrected output HTML uses a logical 1920x1080 canvas; its Stitch screenshot
metadata is 3840x2160, the same aspect ratio at twice the resolution.

## Local exports

- [Projects / Home](stitch/01-projects-home.html)
- [New project](stitch/03-new-project.html)
- [Project actions](stitch/04-project-actions.html)
- [Rename project](stitch/05-rename-project.html)
- [Delete project confirmation](stitch/06-delete-project.html)
- [Project recovery](stitch/07-project-recovery.html)
- [Save failure / Retry](stitch/31-save-failure-retry.html)
- [Logo and app-icon board](stitch/26-brand-logo-app-icon.html)
- [Splash and empty-state board](stitch/27-splash-empty-state-assets.html)
- [Component and interaction-state board](stitch/28-components-interaction-states.html)
- [Clean output reference](stitch/25-output-clean-fullscreen.html)
- [Selected-video editor reference](stitch/09-editor-selected-video.html)
- [Selected-video screenshot](stitch/09-editor-selected-video.png)
- [Surface layers](stitch/10-editor-surface-layers.html)
- [Surface properties](stitch/11-editor-surface-properties.html)
- [Viewport navigation](stitch/12-editor-viewport-navigation.html)
- [External display output](stitch/34-external-display-output.html)
- [Delete surface confirmation](stitch/30-delete-surface-confirmation.html)
- [Rendering-proof mobile layout, Milestone 1](stitch/13-rendering-proof-mobile.html)
- [Rendering-proof landscape layout, Milestone 1](stitch/33-rendering-proof-landscape.html)
- [Extracted SVG assets](assets/)

Screen 33 was added during Milestone 0 implementation to define the landscape
canvas/control-rail adaptation. Its screenshot board metadata is 2560x2048; use
it as a responsive layout reference, not a literal phone framebuffer. Actual
landscape behavior is verified by the Android instrumentation suite.

Screens 13 and 33 were revised for Milestone 1. The revisions replace the
"Rendering proof" badge with screen 09's **Edit | Output** segmented control and
add an editor-only "Output 1920 × 1080" caption. Stitch also introduced
wireframe/pipeline/buffer diagnostics and coordinate readouts in those revisions.
They are decorative here, outside approved scope, and not implemented. Output
itself follows screen 25: black, fullscreen, mapped content only, with Back
returning to Edit. The Milestone 0 versions are listed under
`supersededScreens`.

Milestone 3 reuses approved screens 10/11 for Layers/Properties, 14 for adding
image/video/sample sources, 30 for destructive confirmation, and 08 for the
empty canvas. The native implementation adapts their charcoal/violet/cyan
tokens into scrollable, accessible dialogs. Layer order is frontmost first;
Move backward/forward buttons provide an accessible alternative to drag handles.
Visibility, opacity, independent duplication, and confirmed deletion are in
scope; locks, effects, calibration, save/project navigation, and decorative
diagnostics are not. Only the selected visible surface gets editing handles;
no dialog, handle, label, or empty-state message enters clean Output.
The three composition HTML exports are existing approved references, not new
screen revisions.

Milestone 4 reuses screens 01/02 for Projects/Empty, 03-06 for project CRUD,
07/23 for recovery/load errors, and 31 for save failure/retry. These are native
scrollable project cards and dialogs using the existing brand tokens. New
projects keep the current fixed 1920x1080 canvas and bundled sample; arbitrary
canvas sizes, thumbnails, projector status, technical telemetry, templates,
and cloud/export controls in generated references are not implemented.
Autosave is debounced at 500ms rather than the decorative 30-second copy in
screen 07. Recovery copy explicitly warns that an older snapshot can lose recent
edits; it does not repeat the mockup's unsupported "No data was lost" claim.
Save/load errors and project navigation never enter clean Output.
The persistence exports are the approved existing references, not new revisions.

Milestone 5 reuses screens 09/12 for Undo/Redo, zoom level, zoom in/out, Fit,
and two-finger pan/pinch navigation. Single-finger corner editing retains gesture
ownership. Native scrollable controls preserve source/playback access; a
charcoal direction-button dialog provides a screen-reader-friendly alternative
to panning. This is an accessibility adaptation of screen 12, not a new visual
language. Zoom is bounded to 50-400%; Fit resets preview framing only.
Undo/redo is session-only and groups each drag/opacity swipe as one edit.
Preview framing and history never enter clean Output. Screen 12's grid snap,
mesh/pattern tools, projector status, and decorative telemetry are not implemented.
The new local HTML export is the existing approved screen, not a Stitch revision.

Milestone 6 was explicitly approved on 2026-10-29: independent external-display
output, testable with a USB-C/HDMI monitor before a projector is available.
Screen 34 was created in the existing Stitch project using screen 09 and the
existing design-system tokens. It defines a phone-side display status, opt-in
Send clean output switch, display selection, disconnected guidance, and Retry.
The phone's Edit/Output switch remains independent. The external Presentation
contains only a fullscreen media SurfaceView over black; paused/error states
cover the media with black rather than displaying phone recovery UI.

The generated reference is a large specification board, not literal phone
geometry. Decorative mesh tools, EDID details, invented latency/frame-drop
statistics, projector model names, and a second broadcast/commit button are
not implemented. Its absolute no-notifications claim is not an OS guarantee:
Android/Samsung still controls desktop chrome, notifications, and mirroring
outside an active Presentation. Native controls use the existing scrollable
panel and 48dp targets. No casting, MediaProjection permission, effects,
physical calibration, or display-wall support was added.

Screen 35 adds the approved tap-to-select interaction using the existing editor
and navigation design system. A completed short tap selects the topmost visible,
nonzero-opacity quad at that point. Hit testing uses geometry, not image alpha;
empty taps keep selection. Existing selected corner handles retain drag priority.
Swipes, holds, cancellation, and multi-finger gestures do not select.
Layers remains the accessible alternative. Selection changes neither ordering
nor history, and local/external Output pixels remain unaffected. The mockup's
extra Restart/Loop controls, tap halo, dashed outlines, and decorative glow are
not part of this change; native selection uses existing cyan handles.

All current screen references remain in Stitch. Only the references above are
exported locally; the manifest is not a full offline archive of every mockup.
Use Stitch's `download_file` tool with the manifest's file resource names for
additional exports. Download URLs may need authentication and are not permanent
public links.

The exported HTML is generated reference material. It loads Tailwind, icon fonts,
and fonts from remote hosts; local browser rendering may fail when those resources
are unavailable. It is not a self-contained offline application or production
Android UI. Do not carry those network dependencies into the app.

### Standalone vectors

The following 14 SVGs were extracted from Stitch's marked inline assets. Their
XML and internal references were checked; they do not depend on external SVG
files or the surrounding board CSS.

| Purpose | Files |
|---|---|
| Primary mark | [lumis-mark.svg](assets/lumis-mark.svg) |
| Dark and light lockups | [lumis-lockup.svg](assets/lumis-lockup.svg), [lumis-lockup-light.svg](assets/lumis-lockup-light.svg) |
| Single-color mark | [lumis-mark-mono.svg](assets/lumis-mark-mono.svg) |
| Adaptive icon layers | [ic-launcher-foreground.svg](assets/ic-launcher-foreground.svg), [ic-launcher-background.svg](assets/ic-launcher-background.svg) |
| Android themed icon | [ic-launcher-monochrome.svg](assets/ic-launcher-monochrome.svg) |
| Splash mark | [splash-mark.svg](assets/splash-mark.svg) |
| Empty states | [empty-projects.svg](assets/empty-projects.svg), [empty-canvas.svg](assets/empty-canvas.svg) |
| Missing media | [missing-media.svg](assets/missing-media.svg), [unavailable-source.svg](assets/unavailable-source.svg) |
| Unsupported media | [unsupported-media.svg](assets/unsupported-media.svg) |
| Recovery | [recovery.svg](assets/recovery.svg) |

The lockup letters are vector paths, but their small "PROJECTION STUDIO"
descriptor is live text using JetBrains Mono. Bundle an appropriately licensed
font or outline that descriptor before final production export.

SVG sources are not Android VectorDrawable XML. Convert supported geometry and
review filters, gradients, adaptive-icon masks, themed-icon contrast, splash
safe zones, and small-size legibility during Android integration.

The boards also supply thumbnail concepts and an action-icon matrix. These are
visual references, not exported playable videos, licensed font bundles, Android
resource XML, or a complete standalone action-icon library. Use one consistent
properly licensed icon family during implementation. Obtain or generate actual
sample media separately; thumbnail artwork is not a video fixture.

## Behavior takes precedence over decorative mockup details

- One canonical output canvas and scene renderer must back Edit and Output.
- Clean output has no app logo, controls, handles, editor outlines, status bar,
  diagnostics, or unavailable-source labels. Back navigation returns to editing.
- Four-point mapping must be genuinely perspective-correct. CSS artwork in
  Stitch does not prove a working renderer, homography, decoder, or frame rate.
- The default editor uses plain language. Technical coordinates, FPS, decoder
  state, and grids belong behind explicit debug settings.
- A reference grid is optional and intentional; do not silently leak it into
  clean output. Clarify whether it is editor-only or explicitly rendered content.
- Preserve scenes and original media on import, decoder, renderer, load, or save
  errors. Never display success when saving failed.
- Screen 15 represents the Android system photo picker, not a custom media
  library. System UI varies with Android version; do not clone it or require
  broad storage permission to use the picker.
- Default output is 1920x1080 with a black background. Mockup labels do not imply
  arbitrary-resolution controls, a timeline, multi-select, or calibration exists.
- Selection and errors must not rely on color alone. Destructive actions need
  confirmation or undo. Continuous dragging should form one undo action.

## Required workflow for missing features and designs

1. Read the PRD and confirm that the feature is in the approved implementation
   milestone. Obtain approval before adding roadmap scope.
2. Inspect this inventory and the current Stitch screen. Reuse an existing design
   if it already covers the required behavior.
3. If the screen, state, component, or branded asset does not exist, **use Google
   Stitch before implementing it**. Discover the Stitch tools, generate in project
   `9969649650324268552`, and reuse design system
   `assets/e7e76232818f47c9be8016bebcc921aa`.
4. Include entry/exit behavior, empty/loading/error/permission states, Android
   touch/accessibility constraints, offline behavior, and clean-output boundaries
   in the design request. Keep user terminology approachable.
5. Inspect the result. Check aspect ratio, clipping, touch targets, unwanted
   controls, and scope creep. Revise in Stitch rather than silently redesigning
   locally. Keep revised and superseded IDs distinguishable.
6. Add current screen/file resources to the manifest and update this handoff.
   Export reusable assets with explicit filenames when needed. Use authenticated
   Stitch download tools rather than assuming a download URL is public.
7. Implement using the actual Android architecture. Test behavior and performance
   independently of the mockups. Do not mistake generated HTML for the renderer.

Future effects/shaders, masks, audio-reactive controls, external displays, camera
surface detection, and physical projector calibration are deliberately not
designed here. Generate their designs in the same Stitch project when approved.

## Verification status

The manifest contains exactly one current entry for each number 01-33.
The corrected output aspect ratio and extracted SVG XML/internal references
were checked. The selected-video screenshot received a visual review and a
plain-language simplification pass.

This document describes design references, not a tested interactive prototype.
For the implemented Android rendering proof, see [the application README](../README.md).
Every screen still needs implementation-time review on the target Android device.
Browser preview of the exported boards encountered remote-resource loading
errors; this does not verify their offline visual rendering.
