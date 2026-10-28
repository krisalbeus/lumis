# Lumis

An Android-first, offline projection-mapping app. Currently implemented:
**Milestone 0, the rendering proof**, **Milestone 1, the virtual projector
canvas**, **Milestone 2, media import**, **Milestone 3, scene composition**,
**Milestone 4, local project persistence**, and **Milestone 5, editing polish
(emulator-verified; physical-device acceptance pending)**. This is intentionally not the
complete MVP.

Play a bundled video, independently drag its four corners, and watch the GPU
preserve perspective in real time. Switch between **Edit** and a clean,
fullscreen **Output** of the same fixed 1920x1080 scene. Includes Play/Pause,
Reset corners, optional measured FPS, portrait/landscape layouts, and accessible
corner-movement actions. Import local images/videos and replace unavailable media
without losing corner mapping.
Compose multiple independently mapped images/videos with visibility, opacity,
layer ordering, duplication, and confirmed deletion.
No projector, account, network access, or media permissions are required.

- [Implementation checklist](IMPLEMENTATION.md)
- [Google Stitch design handoff](design/README.md)
- [Stitch project](https://stitch.withgoogle.com/projects/9969649650324268552)

## Build and run

Requirements:

- JDK 17 or 21. Android Studio's JDK is suitable; set `JAVA_HOME` to a real JDK.
- Android SDK platform 36, build tools, and platform-tools.
- An Android 8.0+ (API 26+) device or emulator with OpenGL ES 2.0 and external
  video-texture support. Testing so far uses an Android 16 arm64 emulator.
- Set `ANDROID_HOME` to your SDK, or create an untracked `local.properties` with
  `sdk.dir=/absolute/path/to/Android/sdk`.

Open the repository in Android Studio, sync Gradle, and run the `app` configuration.
Alternatively:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.lumis/.ProjectsActivity
```

For this Mac's existing environment (its inherited `JAVA_HOME` is stale):

```sh
export JAVA_HOME=$(/usr/libexec/java_home)
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug
```

The checked-in Gradle 8.14.3 wrapper pins the official distribution SHA-256.
There are no third-party runtime dependencies. AndroidX test libraries and JUnit
are test-only dependencies; the first build needs access to Gradle/Maven artifacts.

## Controls

- The launcher opens **Projects**. **New project** creates a named local project
  with one bundled sample surface; project names must contain 1-100 characters.
  **Open project** restores its saved composition. **Project actions** offers
  rename, independent project duplication, and confirmed deletion.
- **Projects** in the editor, or **Back** from Edit, saves the current snapshot
  before returning to the project list. **Save now** flushes immediately.
  All these controls are in the scrollable editor panel.
- **Edit | Output** switches presentation without restarting video or changing
  geometry. Output is immersive and black outside the mapped video. It has no
  handles, outlines, labels, FPS, buttons, or system bars. Press or gesture
  **Back** to return to Edit. Output ignores touches so the projected scene
  cannot be edited accidentally.
- **Import media** opens Android's system visual picker: Photo Picker on Android
  13+ and document picker fallback on older versions. Supported media are local
  images and videos.
- Imported images and videos use the same mapped quad, and replacing media keeps
  the selected surface's geometry, opacity, visibility, and layer order unchanged.
- **Add surface** adds a bundled sample or opens the image/video picker for a new
  surface. There is no artificial surface-count limit; available decoder/GPU/memory
  resources still limit practical compositions.
- **Layers** lists surfaces frontmost first. Select a row to edit that surface's
  corners, media, and playback; selection never changes drawing order. Each row's
  **Show** switch hides its content and editing handles without deleting it.
- **Surface properties** controls visibility, 0-100% opacity, **Move backward /
  forward**, **Duplicate surface**, and **Delete surface**. Duplication inserts an
  independent copy immediately above the original, retaining mapping/properties
  and playback intent. Reordering does not restart decoders.
- Deletion requires confirmation and never deletes the original media file.
  Deleting the last surface leaves an empty editor and pure black Output;
  **Add surface** starts composing again. Properties/playback/reset are disabled
  when there is no selection.
- Unavailable or unsupported files produce explicit recovery UI. If a previously
  imported source is missing on restore, Lumis falls back to the bundled sample
  for continuity and prompts **Replace media** while preserving mapping.
- The first time Android enters immersive mode, the OS may show its own
  one-time "Viewing full screen" tutorial over the app. Dismiss it before
  projecting; it is system UI, not Lumis output.
- Drag handles **A, B, C, D**. Each has a 56dp target.
- Two fingers on empty canvas **pan and pinch-zoom the preview (50-400%)**.
  One finger moves a corner; a gesture starting on a corner keeps ownership if
  another finger lands. **Zoom in / out**, **Pan canvas** direction buttons,
  and **Fit** provide accessible alternatives in the scrollable control panel.
  Fit restores the preview only. Navigation never changes scene coordinates,
  projected geometry, saved content, or clean Output.
- **Undo / Redo** restores corner mapping, Reset, properties, layer order,
  add/duplicate/delete, and source replacement. A continuous corner drag or
  opacity swipe is one edit; a cancelled gesture keeps any already-visible
  changes as one edit. No-op gestures create no entry. Selection, playback,
  preview navigation, and automatic missing-media fallback are not edits.
  A new content edit discards redo. Unchanged media resources keep playing;
  restored/replaced media recovers the recorded playback intent/position.
- Undo/redo autosaves the restored scene. History contains model snapshots, not
  textures/decoders, and survives rotation/backgrounding in the same process.
  It is session-only: cold project reopen/process death does not restore history.
  Preview framing survives activity recreation but cold project reopen starts
  at Fit. Surface IDs remain monotonic even after undoing additions.
- Corners stay within the canvas. Folded, crossed, or nearly collapsed geometry
  is rejected with a visible message instead of feeding a singular transform to
  the renderer.
- **Pause / Play** controls only the selected video without resetting its geometry.
- **Reset corners** restores the selected surface's starting rectangle without restarting playback.
- **Show FPS** reports actual redraws and decoded frames sampled by the renderer,
  not a fabricated display refresh rate. With a 30fps source and no interaction,
  roughly 30 redraws/sec is expected; paused playback does not spin a render loop.
- Screen-reader users can focus each labeled corner and use left/right/up/down
  actions. Scroll-forward/backward actions adjust horizontally.
- Rotation and background/resume preserve all surfaces, stable IDs, order,
  selection, geometry, visibility, opacity, each video's playback intent/position,
  and Edit/Output mode. A playback or renderer error exits Output so recovery UI
  never appears in clean output.
  Saved-instance-state preserves the current editing session across recreation.
  Cold-opened projects intentionally enter Edit rather than immediately projecting.

The video is deliberately silent and loops. A playback/rendering error leaves
geometry intact and shows an explicit Retry action.

## Local saving and recovery

- Scene/selection/property/playback changes autosave after 500ms without another
  change; backgrounding also queues an immediate snapshot. Writes run serially off
  the UI thread, so queued older snapshots cannot overwrite newer edits.
- Version 1 JSON documents in private app storage contain project ID/name,
  timestamp, fixed-canvas scene order, stable surface IDs/next ID, selection,
  geometry, visibility, opacity, source URIs, per-video playback intent/position,
  and missing-media references. UI chrome and GPU/decoder objects are not stored.
- Each write syncs a temporary file, atomically renames it into place with checked
  errors, and syncs the directory. A separate previous valid snapshot provides
  recovery. Incomplete temporary writes are never treated as projects.
- If the latest document is corrupt but a previous valid snapshot exists, Lumis
  asks before recovery. Recovery may lose the latest edits. Unknown versions and
  invalid scene data are rejected explicitly, never silently replaced by defaults.
  Unreadable projects stay listed so the user can review their error or delete them.
- Save failures exit Output, show **Retry / Keep editing**, and retain live edits.
  Navigation back to Projects waits for a successful save. Force-stopping during
  a pending/failed save can still lose unsaved edits; no app can guarantee their
  durability before the write completes.
- Media stays at its original URI. Duplicating projects shares media references,
  not files; deleting a project never deletes gallery media. Providers may revoke
  access despite a persistence request; missing sources retain mapping and a
  Replace-media prompt across saves/reopens.
- Projects are local only: no cloud sync/export, cross-device backup, or recovery
  after uninstall/clearing app data. Android backup remains disabled.

## Rendering architecture

```text
MainActivity: one immutable Scene in 1920x1080 logical output space
     |                         |
     v                         v
CornerOverlay (Edit only)  VideoRenderer (GL thread, Edit and Output)
selected-surface handles  ordered GPU compositor + presentation viewport
                               ^
                               |
Per-video MediaPlayer -> Surface -> SurfaceTexture / external OES texture
Per-image bounded bitmap decode -> GL texture -> same homography path
```

- Android's decoder writes directly to a GPU-backed surface. Video pixels do not
  pass through Kotlin/Java arrays, JavaScript, or regenerated bitmaps.
- Imported still images are validated, decoded for upload to a 2D GL texture, and
  rendered with the exact same homography and viewport path as video.
  Decodes are limited to a 2048-pixel longest edge to bound per-image memory.
  Android 9+ also applies the image decoder's source-orientation handling.
- Each stable surface ID owns its texture/decoder resources. Source replacement
  or deletion releases only that surface; selection, geometry, visibility,
  opacity, and z-order edits reuse existing resources.
- Surfaces draw back-to-front using premultiplied-alpha blending, including
  transparent images and per-surface opacity. Hidden surfaces keep their playback
  intent; showing them again does not restart their videos.
- A unit-square homography maps source coordinates into the convex destination
  quad. The vertex shader retains homogeneous `w` in `gl_Position`, allowing
  correct perspective interpolation across both triangles without a diagonal seam.
- The decoder's `SurfaceTexture` transform and source orientation are handled
  explicitly. Static colored corner markers make upside-down/mirrored output
  detectable independently of the relative mapping tests.
- Editing handles are a separate Android view, not part of rendered media.
- Rendering is event-driven: decoded frames and geometry edits request redraws.
- Player and GPU resources are released on backgrounding and recreated on resume.
  Geometry, play/pause intent, and position are saved separately from those resources.

Scene coordinates are normalized to the fixed 1920x1080 logical output frame
(`OutputCanvas`). A `Viewport` places that frame on a physical surface. Edit uses
a 28dp inset preview shared exactly by the renderer and handle overlay, followed
by an editor-only zoom/pan transform. The shader applies the final viewport in
homogeneous clip space, keeping the hardware GL viewport screen-sized even at
400% zoom. Output ignores that editor transform and uses
the same `GLSurfaceView`, compositor, media surfaces, and scene with no inset,
letterboxed or pillarboxed in black at 16:9. Switching modes only changes view
visibility, system bars, and the viewport; it never recreates the video pipeline.
On a 2400x1080 landscape phone, Output is exactly 1920x1080 pixels. External
HDMI/Presentation display routing is not implemented yet.

Key source:

- [Quad, homography, and viewport math](app/src/main/java/com/lumis/geometry/Quad.kt)
- [Immutable scene and surface operations](app/src/main/java/com/lumis/scene/Scene.kt)
- [Versioned local project store](app/src/main/java/com/lumis/project/ProjectStore.kt)
- [Project manager](app/src/main/java/com/lumis/ProjectsActivity.kt)
- [GPU video renderer](app/src/main/java/com/lumis/renderer/VideoRenderer.kt)
- [Decoder lifecycle](app/src/main/java/com/lumis/media/SamplePlayer.kt)
- [Corner editing and accessibility](app/src/main/java/com/lumis/editor/CornerOverlay.kt)
- [Editor preview transform](app/src/main/java/com/lumis/editor/PreviewTransform.kt)
- [Scene edit history](app/src/main/java/com/lumis/editor/EditHistory.kt)
- [Native screen](app/src/main/java/com/lumis/MainActivity.kt)

The UI follows the Milestone 1 Stitch revisions of references 13 and 33, plus
screen 25 for clean Output. Stitch added decorative
wireframe/pipeline diagnostics to those revisions; they are outside approved scope
and intentionally not implemented. It uses Android's system sans font
for an offline, dependency-free technical spike. The Android vector logo adapts
the Stitch foreground's geometry and gradients, omitting tiny dashed construction
guides for small-size legibility.
Composition controls reuse approved screens 10/11 (Layers/Properties),
14 (source choice), 30 (delete confirmation), and 08 (empty canvas). Native
scrollable dialogs and accessible move buttons adapt the references without
introducing roadmap controls.
Editing polish reuses screens 09/12 for Undo/Redo and two-finger navigation,
with native zoom/Fit and accessible pan controls. These remain editor-only.

## Tests

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
# Start/unlock an emulator or connect a device first:
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

The instrumentation harness wakes the test device and pre-confirms Android's
one-time immersive tutorial so it cannot cover output assertions. Use only a development device
or emulator: rotation and touch tests interact with its display. The multi-pointer
test dispatches to the real overlay inside the app; single-finger drags go through
Android's system input pipeline.

- [Unit tests](app/src/test/java/com/lumis/geometry/GeometryTest.kt) cover corner
  correspondence, identity, trapezoid perspective, straight grid lines, clip-space
  `w`, 200 deterministic randomized convex quads, serialization, viewport round
  trips, invalid geometry, and floating-point touch drift.
- [Instrumented tests](app/src/androidTest/java/com/lumis/RenderingProofTest.kt)
  cover advancing video, pause/resume, looping, live independent corner gestures,
  reset, absolute video orientation, GPU pixel comparison against a homography,
  recreation/rotation, repeated backgrounding, accessible actions, invalid geometry,
  multi-touch ownership, and explicit error/retry without scene loss.
- [Media import tests](app/src/androidTest/java/com/lumis/MediaImportTest.kt)
  cover image/video replacement, unsupported and unavailable-source handling,
  preserved mapping on failures, and missing-source restore flow.
- [Scene unit tests](app/src/test/java/com/lumis/scene/SceneTest.kt) cover stable
  IDs, selection, independent duplication, ordering, visibility, opacity,
  validation, hit testing, deletion, and more than four surfaces.
- [Persistence tests](app/src/androidTest/java/com/lumis/ProjectPersistenceTest.kt)
  cover versioned mixed/empty round trips, CRUD, name validation, corruption and
  unknown versions, previous-snapshot recovery consent, save failure/retry,
  latest-edit ordering, background flush, missing-media cold reopen, actual GPU
  pixels after cold reopen, and paused decoder position restoration within 80ms.
- [Composition tests](app/src/androidTest/java/com/lumis/SceneCompositionTest.kt)
  verify four independently advancing videos with actual GPU pixels, six image
  surfaces without a four-surface cap, absolute image orientation, transparent
  image blending and opacity (within 3/255 per RGB channel), draw order,
  selection/gesture isolation, native property controls, delete confirmation,
  empty-scene clean Output, mixed-scene recreation/backgrounding, and per-layer
  replacement/deletion/missing-source isolation.
- [Output tests](app/src/androidTest/java/com/lumis/OutputModeTest.kt) cover
  the 1920x1080 caption and preview aspect, fullscreen immersive output with all
  editor chrome/diagnostics hidden, Back returning to the same editing session,
  identical decoder surface and continuous playback, Edit-vs-Output GPU pixels,
  upright output, touch rejection, rotation, backgrounding, and error recovery.
  A full-display screenshot asserts every sampled pixel outside the projected
  quad is black, catching system bars and stray views.
- [Editing unit tests](app/src/test/java/com/lumis/editor/EditingTest.kt) cover
  viewport identity, anchor-preserving zoom, coordinate round trips, bounds,
  invalid input, and history ordering/branching.
- [Editing interaction tests](app/src/androidTest/java/com/lumis/EditingPolishTest.kt)
  cover gesture grouping/cancellation, undo/redo of properties/order/lifecycle/
  source/deletion, monotonic IDs, media resource isolation, durable undo,
  cold-session boundaries, accessible controls, and zoomed GPU perspective.
  An independent Android Matrix inverse evaluates gradient texture coordinates
  at actual pixel centers, requiring error within 3/255 at 50%, 150%, and 400%.
- [Composition stress tests](app/src/androidTest/java/com/lumis/CompositionStressTest.kt)
  exercise 1 video, 4 videos, 8 distinct images, and 4 videos plus 8 images.
  Each runs five edit/undo/redo/Output/background cycles, verifies every surface's
  actual GPU pixels, and checks each video decoder advances independently.
  Timing/process-memory logs are emulator observations, not device benchmarks.
- GPU tests use `PixelCopy` on the actual GL surface, not a UI screenshot proxy.
  They compare 20 source points after a strong trapezoid warp, requiring average
  per-channel error under 5/255 and at least 18/20 samples under 12/255.

Reports are generated under `app/build/reports/`; connected-test XML and logs
are under `app/build/outputs/androidTest-results/`.
See [the verification log](IMPLEMENTATION.md#verification-log) for measured results.

## Sample media

[prism_waves.mp4](app/src/main/res/raw/prism_waves.mp4) is original generated
test media: 1280x720 H.264, 30fps, eight seconds, no audio. It contains periodic
cyan/violet color motion, a straight grid, and four distinct orientation markers:
A cyan, B violet, C amber, D green. It contains no stock footage or font assets.

Regenerate it with FFmpeg (optional; the clip is already bundled):

```sh
sh tools/generate-sample.sh
```

## Not implemented yet

External Output displays, effects, masks, and cameras are not part of this
build. Physical Pixel 9 Pro performance, thermal behavior,
hardware-specific decoding, and projector output still need device testing.
Follow the [milestone checklist](IMPLEMENTATION.md) rather than expanding everything
at once. Missing future UI designs must be created in the existing Stitch project.
