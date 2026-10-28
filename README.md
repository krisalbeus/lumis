# Lumis

An Android-first, offline projection-mapping app. Currently implemented:
**Milestone 0, the rendering proof**. This is intentionally not the complete MVP.

Play a bundled video, independently drag its four corners, and watch the GPU
preserve perspective in real time. Includes Play/Pause, Reset corners, optional
measured FPS, portrait/landscape layouts, and accessible corner-movement actions.
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
adb shell am start -n com.lumis/.MainActivity
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

- Drag handles **A, B, C, D**. Each has a 56dp target.
- Corners stay within the canvas. Folded, crossed, or nearly collapsed geometry
  is rejected with a visible message instead of feeding a singular transform to
  the renderer.
- **Pause / Play** controls the video without resetting its geometry.
- **Reset corners** restores the starting rectangle without restarting playback.
- **Show FPS** reports actual redraws and decoded frames sampled by the renderer,
  not a fabricated display refresh rate. With a 30fps source and no interaction,
  roughly 30 redraws/sec is expected; paused playback does not spin a render loop.
- Screen-reader users can focus each labeled corner and use left/right/up/down
  actions. Scroll-forward/backward actions adjust horizontally.
- Rotation and background/resume preserve corner geometry and playback intent.
  Android saved-instance-state also survives activity recreation; permanent
  project persistence is a later milestone.

The video is deliberately silent and loops. A playback/rendering error leaves
geometry intact and shows an explicit Retry action.

## Rendering architecture

```text
MainActivity: one immutable normalized Quad
     |                         |
     v                         v
CornerOverlay             VideoRenderer (GL thread)
editing handles           projective clip-space transform
                               ^
                               |
MediaPlayer -> Surface -> SurfaceTexture / external OES texture
```

- Android's decoder writes directly to a GPU-backed surface. Video pixels do not
  pass through Kotlin/Java arrays, JavaScript, or regenerated bitmaps.
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

The preview is aspect-fitted at 16:9. This milestone renders directly into its GL
surface; a separate 1920x1080 output target and clean fullscreen mode belong to
Milestone 1. This code does not yet claim external-display routing.

Key source:

- [Quad, homography, and viewport math](app/src/main/java/com/lumis/geometry/Quad.kt)
- [GPU video renderer](app/src/main/java/com/lumis/renderer/VideoRenderer.kt)
- [Decoder lifecycle](app/src/main/java/com/lumis/media/SamplePlayer.kt)
- [Corner editing and accessibility](app/src/main/java/com/lumis/editor/CornerOverlay.kt)
- [Native screen](app/src/main/java/com/lumis/MainActivity.kt)

The UI follows Stitch references 13 and 33. It uses Android's system sans font
for an offline, dependency-free technical spike. The Android vector logo adapts
the Stitch foreground's geometry and gradients, omitting tiny dashed construction
guides for small-size legibility.

## Tests

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
# Start/unlock an emulator or connect a device first:
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

The instrumentation harness wakes the test device. Use only a development device
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

Project management/saving, imported media, multiple surfaces, undo/redo, viewport
pan/zoom, clean Output mode, shaders, masks, cameras, and external displays are
not part of this build. Physical Pixel 9 Pro performance, thermal behavior,
hardware-specific decoding, and projector output still need device testing.
Follow the [milestone checklist](IMPLEMENTATION.md) rather than expanding everything
at once. Missing future UI designs must be created in the existing Stitch project.
