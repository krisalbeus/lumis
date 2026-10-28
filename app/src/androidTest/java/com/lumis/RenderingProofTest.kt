package com.lumis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lumis.geometry.Homography
import com.lumis.geometry.Point
import com.lumis.geometry.Quad
import com.lumis.geometry.Viewport
import com.lumis.editor.CornerOverlay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class RenderingProofTest {
    @Test fun videoAdvancesPausesAndResumes() = withApp { scenario ->
        await(scenario) { it.renderer.videoFrames > 10 }
        val before = read(scenario) { it.renderer.videoFrames }
        await(scenario) { it.renderer.videoFrames > before + 15 }
        click(scenario, R.id.play_pause)
        SystemClock.sleep(350)
        val paused = read(scenario) { it.renderer.videoFrames }
        SystemClock.sleep(500)
        assertTrue("Decoder must stop producing frames while paused", read(scenario) { it.renderer.videoFrames } <= paused + 1)
        assertEquals("Play", read(scenario) { it.findViewById<Button>(R.id.play_pause).text.toString() })
        click(scenario, R.id.play_pause)
        await(scenario) { it.renderer.videoFrames > paused + 10 }
    }

    @Test fun fourIndependentHandlesRespondWhileVideoKeepsPlayingAndResetWorks() = withApp { scenario ->
        val before = read(scenario) { it.renderer.videoFrames }
        val ids = listOf(R.id.corner_a, R.id.corner_b, R.id.corner_c, R.id.corner_d)
        ids.forEachIndexed { index, id ->
            val initial = read(scenario) { it.quad }
            val rect = bounds(scenario, id)
            drag(rect.centerX().toFloat(), rect.centerY().toFloat(), rect.centerX() + 24f, rect.centerY() + 18f)
            val moved = read(scenario) { it.quad }
            assertNotEquals("Corner $index did not move", initial.points[index], moved.points[index])
            (0..3).filter { it != index }.forEach { assertEquals(initial.points[it], moved.points[it]) }
        }
        assertTrue("Video must advance during dragging", read(scenario) { it.renderer.videoFrames } > before + 10)
        click(scenario, R.id.reset_corners)
        assertEquals(Quad.DEFAULT, read(scenario) { it.quad })
    }

    @Test fun gpuPixelsMatchProjectiveMappingWithoutAnAffineDiagonalSeam() = withApp { scenario ->
        click(scenario, R.id.play_pause)
        SystemClock.sleep(400)
        val original = capture(scenario)
        val initial = read(scenario) { it.quad }
        val a = bounds(scenario, R.id.corner_a)
        val b = bounds(scenario, R.id.corner_b)
        val offset = (b.centerX() - a.centerX()) * .28f
        drag(a.centerX().toFloat(), a.centerY().toFloat(), a.centerX() + offset, a.centerY().toFloat())
        drag(b.centerX().toFloat(), b.centerY().toFloat(), b.centerX() - offset, b.centerY().toFloat())
        val finalQuad = read(scenario) { it.quad }
        assertNotEquals(initial, finalQuad)
        SystemClock.sleep(250)
        val warped = capture(scenario)
        val viewport = Viewport.fit(original.width, original.height, read(scenario) { 28.0 * it.resources.displayMetrics.density })
        val originalMap = Homography(initial)
        val warpedMap = Homography(finalQuad)
        val errors = mutableListOf<Int>()
        for (x in listOf(.18, .29, .43, .61, .82)) for (y in listOf(.17, .33, .57, .79)) {
            val source = Point(x, y)
            val p = viewport.toScreen(originalMap.map(source))
            val q = viewport.toScreen(warpedMap.map(source))
            val expected = original.getPixel(p.x.roundToInt(), p.y.roundToInt())
            val actual = warped.getPixel(q.x.roundToInt(), q.y.roundToInt())
            errors += (abs(Color.red(expected) - Color.red(actual)) +
                abs(Color.green(expected) - Color.green(actual)) + abs(Color.blue(expected) - Color.blue(actual))) / 3
        }
        assertTrue("GPU must preserve sampled source pixels under the homography: $errors", errors.average() < 5)
        assertTrue("At least 18/20 source samples must match within 12 levels: $errors", errors.count { it < 12 } >= 18)
        val outside = viewport.toScreen(Point(.02, .5))
        assertEquals("Outside the quad must remain black", Color.BLACK, warped.getPixel(outside.x.toInt(), outside.y.toInt()))
        android.util.Log.i("LumisTest", "Projective GPU pixel errors: mean=${errors.average()}, max=${errors.maxOrNull()}")
        original.recycle()
        warped.recycle()
    }

    @Test fun lifecycleAndRecreationPreserveGeometryAndPlaybackIntent() = withApp { scenario ->
        val handle = bounds(scenario, R.id.corner_c)
        drag(handle.centerX().toFloat(), handle.centerY().toFloat(), handle.centerX() - 50f, handle.centerY() - 30f)
        val edited = read(scenario) { it.quad }
        click(scenario, R.id.play_pause)
        click(scenario, R.id.fps_toggle)
        scenario.recreate()
        await(scenario) { it.findViewById<Button>(R.id.play_pause).isEnabled && it.renderer.videoFrames > 0 }
        assertEquals(edited, read(scenario) { it.quad })
        assertFalse(read(scenario) { it.player.wantsToPlay })
        assertEquals(View.VISIBLE, read(scenario) { it.findViewById<View>(R.id.fps_text).visibility })
        scenario.moveToState(Lifecycle.State.CREATED)
        val pausedFrames = read(scenario) { it.renderer.videoFrames }
        scenario.moveToState(Lifecycle.State.RESUMED)
        await(scenario) { it.findViewById<Button>(R.id.play_pause).isEnabled && it.renderer.videoFrames > pausedFrames }
        assertEquals(edited, read(scenario) { it.quad })
        assertFalse(read(scenario) { it.player.wantsToPlay })
        click(scenario, R.id.play_pause)
        val frames = read(scenario) { it.renderer.videoFrames }
        await(scenario) { it.renderer.videoFrames > frames + 8 }
    }

    @Test fun loopingContinuesPastTheEndOfTheSampleAndReportsRealFps() = withApp { scenario ->
        click(scenario, R.id.fps_toggle)
        val before = read(scenario) { it.renderer.videoFrames }
        SystemClock.sleep(9_000)
        assertTrue("At least 200 frames should arrive across the loop", read(scenario) { it.renderer.videoFrames } > before + 200)
        assertTrue(read(scenario) { it.player.wantsToPlay })
        val fps = read(scenario) { it.findViewById<TextView>(R.id.fps_text).text.toString() }
        assertTrue("FPS should be measured, not a placeholder: $fps", fps.contains("render FPS") && fps.contains("video FPS"))
    }

    @Test fun accessibilityCanAdjustEachCornerWithoutDragging() = withApp { scenario ->
        listOf(R.id.corner_a, R.id.corner_b, R.id.corner_c, R.id.corner_d).forEachIndexed { index, id ->
            val before = read(scenario) { it.quad.points[index] }
            scenario.onActivity { assertTrue(it.findViewById<View>(id).performAccessibilityAction(R.id.move_right, null)) }
            assertEquals(before.x + .01, read(scenario) { it.quad.points[index].x }, 1e-8)
            scenario.onActivity { assertTrue(it.findViewById<View>(id).performAccessibilityAction(R.id.move_down, null)) }
            assertEquals(before.y + .01, read(scenario) { it.quad.points[index].y }, 1e-8)
        }
    }

    @Test fun controlsRemainFullyVisibleAndHaveLargeTouchTargets() = withApp { scenario ->
        for (id in listOf(R.id.play_pause, R.id.reset_corners)) {
            scenario.onActivity {
                val button = it.findViewById<Button>(id)
                button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val button = it.findViewById<Button>(id)
                val visible = Rect()
                assertTrue(button.getGlobalVisibleRect(visible))
                assertEquals("Button must not be clipped by its row or scroll panel", button.height, visible.height())
                assertTrue(button.height >= 48 * it.resources.displayMetrics.density)
                assertTrue("All text lines must fit", button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
            }
        }
    }

    @Test fun sourceOrientationIsUprightAndNotMirrored() = withApp { scenario ->
        click(scenario, R.id.play_pause)
        SystemClock.sleep(350)
        val image = capture(scenario)
        val viewport = Viewport.fit(image.width, image.height, read(scenario) { 28.0 * it.resources.displayMetrics.density })
        val mapping = Homography(read(scenario) { it.quad })
        val markers = listOf(
            Point(.05, .05) to Color.rgb(103, 232, 249),
            Point(.95, .05) to Color.rgb(167, 139, 250),
            Point(.95, .95) to Color.rgb(251, 191, 36),
            Point(.05, .95) to Color.rgb(52, 211, 153),
        )
        for ((point, expected) in markers) {
            val p = viewport.toScreen(mapping.map(point))
            val actual = image.getPixel(p.x.roundToInt(), p.y.roundToInt())
            for (shift in listOf(0, 8, 16)) {
                assertTrue("Source marker $point must be upright; expected $expected got $actual",
                    abs((expected shr shift and 255) - (actual shr shift and 255)) < 22)
            }
        }
        image.recycle()
    }

    @Test fun physicalRotationKeepsCanvasAndControlsUsable() = withApp { scenario ->
        val handle = bounds(scenario, R.id.corner_a)
        drag(handle.centerX().toFloat(), handle.centerY().toFloat(), handle.centerX() + 40f, handle.centerY() + 25f)
        val edited = read(scenario) { it.quad }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            device.setOrientationLeft()
            await(scenario) {
                it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                    it.findViewById<Button>(R.id.play_pause).isEnabled && it.renderer.videoFrames > 2
            }
            assertEquals(edited, read(scenario) { it.quad })
            assertTrue("Landscape canvas should have meaningful height", bounds(scenario, R.id.video_canvas).height() >= 250)
            assertTrue(bounds(scenario, R.id.play_pause).width() >= 120)
            val frames = read(scenario) { it.renderer.videoFrames }
            await(scenario) { it.renderer.videoFrames > frames + 10 }
            click(scenario, R.id.reset_corners)
            assertEquals(Quad.DEFAULT, read(scenario) { it.quad })
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }

    @Test fun repeatedBackgroundResumeRestartsVideoWithoutLosingCorners() = withApp { scenario ->
        val handle = bounds(scenario, R.id.corner_b)
        drag(handle.centerX().toFloat(), handle.centerY().toFloat(), handle.centerX() - 40f, handle.centerY() + 30f)
        val edited = read(scenario) { it.quad }
        repeat(5) {
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(100)
            scenario.moveToState(Lifecycle.State.RESUMED)
            await(scenario) { it.findViewById<Button>(R.id.play_pause).isEnabled }
            val frame = read(scenario) { it.renderer.videoFrames }
            await(scenario) { it.renderer.videoFrames > frame + 3 }
            assertEquals(edited, read(scenario) { it.quad })
        }
    }

    @Test fun foldedCornerGestureStopsBeforeInvalidGeometry() = withApp { scenario ->
        val a = bounds(scenario, R.id.corner_a)
        val c = bounds(scenario, R.id.corner_c)
        drag(a.centerX().toFloat(), a.centerY().toFloat(), c.centerX().toFloat(), c.centerY().toFloat())
        val after = read(scenario) { it.quad }
        assertNotEquals("The handle must not collapse onto its opposite corner", after.a, after.c)
        assertTrue(Homography(after).matrix.all { it.isFinite() })
        click(scenario, R.id.reset_corners)
        assertEquals(Quad.DEFAULT, read(scenario) { it.quad })
    }

    @Test fun unavailableVideoSurfaceShowsAnErrorAndRetryPreservesTheScene() = withApp { scenario ->
        val handle = bounds(scenario, R.id.corner_d)
        drag(handle.centerX().toFloat(), handle.centerY().toFloat(), handle.centerX() + 40f, handle.centerY() - 30f)
        val edited = read(scenario) { it.quad }
        scenario.onActivity {
            val texture = android.graphics.SurfaceTexture(false)
            val unavailable = android.view.Surface(texture)
            unavailable.release()
            texture.release()
            it.player.attach(unavailable)
        }
        await(scenario) { it.findViewById<View>(R.id.error_panel).visibility == View.VISIBLE }
        assertFalse(read(scenario) { it.findViewById<Button>(R.id.play_pause).isEnabled })
        assertEquals(edited, read(scenario) { it.quad })
        click(scenario, R.id.retry_playback)
        val frames = read(scenario) { it.renderer.videoFrames }
        await(scenario) { it.renderer.videoFrames > frames + 3 }
        assertEquals(edited, read(scenario) { it.quad })
    }

    @Test fun secondFingerDoesNotStealTheActiveCornerOrCauseAJump() = withApp { scenario ->
        val handle = bounds(scenario, R.id.corner_a)
        val overlayBounds = bounds(scenario, R.id.corner_overlay)
        val x = (handle.centerX() - overlayBounds.left).toFloat()
        val y = (handle.centerY() - overlayBounds.top).toFloat()
        val before = read(scenario) { it.quad }
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, primary: Point?, secondary: Point?) {
            val pointers = listOfNotNull(primary?.let { 7 to it }, secondary?.let { 19 to it })
            val properties = pointers.map { (id, _) ->
                MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }.toTypedArray()
            val coordinates = pointers.map { (_, point) ->
                MotionEvent.PointerCoords().apply { this.x = point.x.toFloat(); this.y = point.y.toFloat(); pressure = 1f; size = 1f }
            }.toTypedArray()
            val event = MotionEvent.obtain(
                down, SystemClock.uptimeMillis(), action, pointers.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            scenario.onActivity { it.findViewById<CornerOverlay>(R.id.corner_overlay).dispatchTouchEvent(event) }
            event.recycle()
        }
        val first = Point(x.toDouble(), y.toDouble())
        val second = Point(x + 150.0, y + 100.0)
        var ended = false
        try {
            inject(MotionEvent.ACTION_DOWN, first, null)
            inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second)
            inject(MotionEvent.ACTION_MOVE, first, Point(second.x + 30, second.y + 30))
            assertEquals(before, read(scenario) { it.quad })
            val moved = Point(first.x + 30, first.y + 20)
            inject(MotionEvent.ACTION_MOVE, moved, second)
            val edited = read(scenario) { it.quad }
            assertNotEquals(before.a, edited.a)
            assertEquals(before.b, edited.b)
            inject(MotionEvent.ACTION_POINTER_UP, moved, second)
            inject(MotionEvent.ACTION_MOVE, null, Point(second.x + 90, second.y))
            assertEquals(edited, read(scenario) { it.quad })
            inject(MotionEvent.ACTION_UP, null, second)
            ended = true
        } finally {
            if (!ended) inject(MotionEvent.ACTION_CANCEL, first, second)
        }
    }
}
