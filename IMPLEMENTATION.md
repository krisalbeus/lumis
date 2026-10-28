# Lumis implementation checklist

Implement one runnable vertical milestone at a time. The complete MVP screen set
is a design reference, not a requirement to build everything in the first pass.
Use [the Stitch workflow](design/README.md) for missing screens and assets.

## Milestone 0 - Rendering proof (complete)

- [x] Inspect Android SDK, Java, emulator, and existing Stitch screen 13.
- [x] Choose native Kotlin UI + OpenGL ES + Android video decoder. No JavaScript
  frame copies, network access, media permission, or projector dependency.
- [x] Scaffold a reproducible Android build and Gradle wrapper.
- [x] Bundle an original, deterministic H.264 sample with visible motion and a
  perspective reference grid.
- [x] Render the decoded video texture through a projective transform on the GPU.
- [x] Keep one authoritative normalized quad and separate editing overlays.
- [x] Implement four independent touch handles with large targets and convexity
  protection against folded/degenerate geometry.
- [x] Add Play/Pause, Reset corners, and optional measured FPS.
- [x] Handle decoding/rendering failures explicitly and release resources safely.
- [x] Preserve geometry and playback intent across rotation/backgrounding.
- [x] Unit-test homography, perspective interpolation, and viewport conversions.
- [x] Build debug APK and run Android lint.
- [x] Run instrumented playback, gesture, geometry, reset, and lifecycle tests on
  an Android emulator.
- [x] Inspect emulator screenshots and record measured results and limitations.

## Milestone 1 - Virtual projector canvas (complete)

- [x] Revise Stitch rendering-proof screens with the Edit | Output control and
  output-size caption; reuse screen 25 for clean output.
- [x] Establish fixed 1920x1080 logical output and correctly scaled preview.
- [x] Share one exact preview inset between GPU rendering and editing handles.
- [x] Add Edit/Output mode switch using the same renderer, video surface, and scene.
- [x] Make Output immersive and black-letterboxed, ignore touches, keep the screen
  on, and return to Edit with Back.
- [x] Preserve mode across rotation and backgrounding; exit Output on errors.
- [x] Test clean fullscreen output without editing chrome or diagnostic overlays.

## Milestone 2 - Media import (complete)

- [x] Use Android's system visual picker flow (Photo Picker on Android 13+,
  document-picker fallback on older versions) for images/videos.
- [x] Import and replace sources while preserving quad geometry and output mode.
- [x] Render imported images with the same projective mapping path as video.
- [x] Handle unsupported, unavailable, and missing sources with explicit recovery
  UI and a Replace-media action.
- [x] Preserve current media and scene state on import failures; if a restored
  source is missing, fall back to the bundled sample and keep mapping unchanged.

## Milestone 3 - Scene composition (complete)

- [x] Add an immutable scene with stable surface IDs and independent media/geometry.
- [x] Add multiple surfaces, layer selection, visibility, opacity, and z-order.
- [x] Composite all surfaces in the same GPU pipeline for Edit and clean Output.
- [x] Duplicate independently; confirm deletion without deleting original media.
- [x] Handle deletion of the last surface and adding from an empty scene.
- [x] Preserve all surfaces/properties/playback across recreation/backgrounding.
- [x] Verify four concurrently advancing videos and six images, with no artificial hard limit.
- [x] Test actual GPU alpha/order/orientation, per-layer resource isolation,
  missing-media recovery, native controls, and compact/large-font behavior.

## Milestone 4 - Persistence (complete)

- [x] Add versioned local project documents with scene/media/playback state.
- [x] Reopen projects from a native launcher project list.
- [x] Add off-main-thread, serial, debounced autosave and background flush.
- [x] Atomically publish synced snapshots with checked errors and a previous
  valid recovery copy; require confirmation before restoring an older snapshot.
- [x] Add project create/rename/duplicate/delete and explicit save/load failure
  states; preserve media and live edits.
- [x] Preserve failed-save status and unsaved session edits across recreation.
- [x] Verify precise paused playback position, cold reopen, actual force-stop
  restart, corruption/unknown versions, recovery, failed writes/retry, and CRUD.

## Milestone 5 - Editing polish (next)

- [ ] Add viewport zoom/pan without modifying scene coordinates.
- [ ] Add undo/redo with one history entry per continuous gesture.
- [ ] Review accessibility, small-screen layout, and touch targets on device.
- [ ] Stress-test 1/4 videos, 8 images, and mixed compositions on Pixel 9 Pro.

## Future work (separate approval and Stitch designs required)

- [ ] GPU effects and masks.
- [ ] External display routing.
- [ ] Audio-reactive controls.
- [ ] Camera surface detection.
- [ ] Physical projector-assisted calibration (requires hardware).

## Verification log

### Milestone 4

Verified on 2026-10-29 with the same `e2e_playstore` AVD (Android 16 / API 36).

| Check | Result |
|---|---|
| Unit tests | 23 passed (existing geometry and scene tests) |
| Android lint | 0 errors; 3 existing SDK/Gradle/back-attribute warnings |
| Connected tests, 1080x2400 / density 420 | 46 passed (33 regression + 13 persistence) |
| Compact tests, 720x1280 / density 320 / font scale 1.5 | 28 passed (13 persistence + 9 composition + 6 Output) |
| Document round trip | Mixed image/video and empty scenes retained IDs/next ID, order, selection, geometry, visibility, opacity, source/missing URIs, and playback |
| Autosave | Latest edits written after 500ms debounce; backgrounding flushed immediately; 12 queued saves retained the newest snapshot |
| Corruption/recovery | Truncated primary recovered from previous valid snapshot only after consent; invalid/unknown-version data remained untouched before recovery |
| Write failure | Controlled publication failure retained original disk state and live edits; visible Retry succeeded; failed-save status/geometry survived activity recreation |
| Media recovery | Missing image on cold open preserved mapping, switched only its source to sample, and kept the Replace-media prompt across saves/reopens |
| Cold-open GPU | Actual red image pixels verified after closing and reopening a warped mixed scene |
| Paused decoder position | Restored position tested against the actual player within 80ms, not only saved metadata |
| Real process restart | Force-stopped Lumis after autosave, relaunched via Projects, reopened two warped videos; scene/selection/order/playback intent retained and paused position exactly 5331ms -> 5331ms |
| Project UI | Create/blank-name validation, rename, duplicate, delete cancel/confirm, open, and Back-to-Projects save barrier exercised |
| Visual review | Project/empty cards, create/rename/actions/delete, recovery, save failure/retry/status, clean Output, compact 150% font, and landscape inspected |

Failure injection exposed that Android `AtomicFile.finishWrite()` can log
publication errors without throwing. Saves now use an explicitly synced
temporary file, checked `Os.rename`, and directory sync; a failed write cannot
produce a success-shaped UI result. Force-stop review also exposed default
keyframe seeking that could restart a video too early. The player now waits for
closest-frame seek completion before reporting ready or starting playback.

Cold-opened projects enter Edit for safety; Edit/Output mode still survives
rotation within the current session. New projects start with one bundled sample
at fixed 1920x1080. The project list and save controls are editor UI only.
Media is referenced, not copied; gallery files are never deleted.

Pending/failed writes are not durable; force-stopping before a successful save
can lose unsaved edits. Clearing app data/uninstall removes local projects;
backup/export/cloud sync are not implemented. Android 8/9 compatibility,
provider-specific permissions, physical-device performance, thermal behavior,
and projector output still require device testing.

### Milestone 3

Verified on 2026-10-29 with the same `e2e_playstore` AVD (Android 16 / API 36).

| Check | Result |
|---|---|
| Unit tests | 23 passed (15 geometry + 8 scene) |
| Android lint | 0 errors; 3 existing warnings (SDK/Gradle advisories and older-Android back attribute) |
| Connected tests, 1080x2400 / density 420 | 33 passed (24 regression + 9 composition) |
| Compact tests, 720x1280 / density 320 / font scale 1.5 | 20 passed (9 composition + 5 import + 6 Output) |
| Concurrent video | Four separate decoder/texture pairs all advanced and produced visible GPU pixels; pausing the selected video did not pause the others |
| No four-surface cap | Six imported image surfaces rendered; pure scene model also exercised 25 surfaces |
| GPU compositing | Red/blue opacity, PNG transparency, visibility, z-order, and Edit/Output verified within 3/255 per RGB channel |
| Source orientation | All four colored image corners upright/unmirrored; existing video/perspective tests still pass |
| Independent editing/resources | Layer selection/corner dragging isolated; replacing/deleting one surface left the other decoder Surface identity unchanged and frames advancing |
| Lifecycle | Mixed image/video scene, stable IDs/order/selection/properties, paused intent, Output, and repeated background/resume retained |
| Empty scene and recovery | Cancel/confirm deletion, delete-last -> black Output, recreate empty scene, add again; missing unselected image attributed/recovered per surface |
| Visual review | Portrait, landscape, Layers, Properties, delete confirmation, warped two-video clean Output, and compact 150% text inspected |

Image decodes are bounded to 2048 pixels on their longest edge; per-surface GPU
resources are reused across property edits. Decoder errors from released players
are ignored by identity, preventing obsolete callbacks from affecting new media.
Picker additions and replacements both request persistent read access where the
provider supports it. Native move buttons are the accessible reorder equivalent
of the Stitch layer-list affordance. Dialog colors reuse the existing brand tokens.

These are emulator correctness checks, not physical-device throughput guarantees.
Four-video hardware limits, long-running memory/thermal behavior, Android 8/9
compatibility, provider-specific permissions, and projector output still require
device testing. This milestone does not add permanent project saving.

### Milestone 2

Verified on 2026-10-29 with the same `e2e_playstore` AVD (Android 16 / API 36).

| Check | Result |
|---|---|
| Unit tests | 15 passed |
| Android lint | 0 errors; 3 warnings (version advisories only) |
| Connected tests, 1080x2400 / density 420 | 24 passed (13 rendering proof + 6 Output + 5 media import) |
| Compact tests, 720x1280 / density 320 / font scale 1.5 | 13 passed (all 5 media-import tests + 6 Output + 2 rendering checks) |
| Import image/video | Imported PNG and MP4 sources replaced live scene without resetting corners |
| Failure handling | Unsupported and unavailable imports surfaced explicit recovery UI and retained existing scene |
| Missing restored source | Deleted imported image on disk, recreated activity, observed fallback to bundled sample with Replace-media prompt and preserved mapping |
| Renderer path parity | Imported images and videos render through the same homography/viewport path used by existing GPU tests |

### Milestone 1

Verified on 2026-10-29 with the same `e2e_playstore` AVD (Android 16 / API 36).

| Check | Result |
|---|---|
| Unit tests | 15 passed, including logical-pixel and Edit/Output viewport mapping |
| Connected tests, 1080x2400 / density 420 | 19 passed (13 rendering proof + 6 Output) |
| Compact tests, 720x1280 / density 320 / font scale 1.5 | 10 passed (all 6 Output + 4 rendering checks) |
| Android lint | 0 errors; 3 warnings (2 version advisories, API-33 back attribute ignored on older Android) |
| Edit vs Output GPU pixels | 20 projected samples; mean per-channel error 0.2-0.35/255, maximum 1 |
| Clean output | Full-display screenshots black outside the mapped quad in portrait and landscape; no system bars, handles, labels, or FPS |
| Output framing | Landscape 2400x1080 output is exactly 1920x1080 at x=240; portrait is letterboxed 1080x607.5 |
| Same pipeline | Decoder `Surface` identity unchanged through Edit -> Output -> Edit; playback continued |
| Back / rotation / background | Back returns to Edit; Output and geometry survive rotation and two background cycles |
| Error in Output | Returns to Edit recovery UI; Retry keeps geometry and Output can be re-entered |

Testing found and fixed a half-pixel mismatch: the renderer rounded its 28dp
inset while handles used the fractional value. Visual review also exposed
Android's one-time immersive-mode tutorial, which is now documented and confirmed
before tests. Emulator display, font, rotation, and immersive settings were restored.

### Milestone 0

Verified on 2026-10-29 with the existing `e2e_playstore` AVD: Pixel 8 profile,
Android 16 / API 36, arm64, OpenGL emulator backend. Native debug APK built and
installed successfully. No projector or physical Android device was used.

| Check | Result |
|---|---|
| Unit tests | 12 passed |
| Connected tests, 1080x2400 / density 420 | 13 passed |
| Compact tests, 720x1280 / density 320 / font scale 1.5 | 5 passed |
| Android lint | 0 errors; 2 version-update advisories |
| Source orientation | All four absolute corner colors verified upright/unmirrored |
| GPU perspective mapping, normal size | 20 samples; mean per-channel error 0.1/255, maximum 1 |
| GPU perspective mapping, compact size | 20 samples; mean per-channel error 0.45/255, maximum 1 |
| Steady playback observed | Approximately 28-30 redraws/video frames per second for the 30fps clip |
| Looping | More than 200 frames delivered across a nine-second test of the eight-second clip |
| Background/resume | Five consecutive cycles; geometry retained and playback resumed |
| Paused recreation | Geometry, paused intent, FPS preference, and a visible video frame retained |
| Physical display rotation | Canvas remained usable; geometry and playback retained |
| Error recovery | Unavailable video surface explicitly reported; Retry retained geometry |
| Touch/accessibility | Four independent real system drags, reset, convexity protection, accessible movement, multi-pointer ownership, and full button visibility |
| Runtime permissions | APK declares no permissions |
| Manual launch | No Lumis renderer/player errors or crashes in the checked process log |

The two lint advisories concern targeting Android 36 rather than a newer SDK and
the pinned Gradle 8.14.3 distribution rather than its newer patch. They do not
hide correctness warnings or suppress failed checks.

The compact run repeats GPU mapping, absolute source orientation, live corner
editing/reset, lifecycle/recreation, and button visibility/touch-size checks.
Emulator display, font, and rotation overrides were restored after testing.

Visual iteration caught and fixed an upside-down source texture, noisy
subpixel updates from a stationary touch, and button clipping at larger font
sizes. Additional absolute-orientation and layout tests guard these behaviors.

See [build/test commands](README.md#tests). Screenshots and raw test-run copies
were saved in the development session; generated test reports and the installable
APK remain under the ignored `app/build/` directory. No APK, SDK path, local
keystore, or emulator state is committed as source.

Emulator results do **not** establish physical Pixel 9 Pro frame-rate, thermal,
codec, or HDMI performance. This proof uses a single bundled 720p silent video;
60fps sources, imported media, concurrent decoders, permanent project saving,
and external-display output remain future milestones.
