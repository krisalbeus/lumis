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

## Milestone 1 - Virtual projector canvas (next)

- [ ] Establish fixed 1920x1080 logical output and correctly scaled preview.
- [ ] Add Edit/Output mode switch using the same renderer.
- [ ] Test clean fullscreen output without editing chrome or diagnostic overlays.

## Milestone 2 - Media import

- [ ] Use the Android photo picker for images/videos.
- [ ] Import and replace sources; handle unsupported/denied/missing media.
- [ ] Preserve original media and retain scene state on failures.

## Milestone 3 - Scene composition

- [ ] Add multiple surfaces, selection, visibility, opacity, and z-order.
- [ ] Duplicate/delete with confirmation or undo.
- [ ] Verify at least four concurrent surfaces, with no artificial hard limit.

## Milestone 4 - Persistence

- [ ] Add versioned local projects, reopen, autosave, and recovery.
- [ ] Add project create/rename/duplicate/delete and explicit save failure states.

## Milestone 5 - Editing polish

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
and clean external output remain future milestones.
