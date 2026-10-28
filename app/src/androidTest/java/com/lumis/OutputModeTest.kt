package com.lumis

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lumis.geometry.Homography
import com.lumis.geometry.OutputCanvas
import com.lumis.geometry.Point
import com.lumis.geometry.Quad
import com.lumis.geometry.Viewport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class OutputModeTest {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun editorShowsTheLogicalOutputSizeAndEditIsSelected() = withApp { scenario ->
        assertEquals("Output 1920 × 1080", read(scenario) { it.findViewById<TextView>(R.id.output_caption).text.toString() })
        assertTrue(read(scenario) { it.findViewById<View>(R.id.output_caption).isShown })
        assertTrue(read(scenario) { it.findViewById<Button>(R.id.mode_edit).isSelected })
        assertFalse(read(scenario) { it.findViewById<Button>(R.id.mode_output).isSelected })
        for (id in listOf(R.id.mode_edit, R.id.mode_output)) {
            assertTrue(bounds(scenario, id).height() >= read(scenario) { 44 * it.resources.displayMetrics.density }.toInt())
        }
        val canvas = bounds(scenario, R.id.video_canvas)
        val viewport = read(scenario) { it.renderer.viewport }
        assertEquals("Preview must keep the 16:9 output aspect",
            OutputCanvas.WIDTH.toDouble() / OutputCanvas.HEIGHT, viewport.width / viewport.height, 1e-6)
        assertEquals(Viewport.fit(canvas.width(), canvas.height(), read(scenario) { 28.0 * it.resources.displayMetrics.density }), viewport)
    }

    @Test fun outputIsCleanFullscreenAndBackReturnsToTheSameEditingSession() = withApp { scenario ->
        val handle = bounds(scenario, R.id.corner_b)
        drag(handle.centerX().toFloat(), handle.centerY().toFloat(), handle.centerX() - 45f, handle.centerY() + 35f)
        val edited = read(scenario) { it.quad }
        val surface = read(scenario) { it.renderer.currentSurface }
        assertNotNull(surface)
        click(scenario, R.id.mode_output)
        awaitCleanOutput(scenario)
        assertSame("Output must reuse the editor's video surface", surface, read(scenario) { it.renderer.currentSurface })
        assertEquals(edited, read(scenario) { it.quad })
        assertTrue(read(scenario) { it.player.wantsToPlay })
        val frames = read(scenario) { it.renderer.videoFrames }
        await(scenario) { it.renderer.videoFrames > frames + 15 }

        assertDisplayIsBlackOutsideTheProjectedQuad(scenario, edited)

        device.pressBack()
        await(scenario) { !it.outputMode && it.findViewById<View>(R.id.play_pause).isShown }
        assertFalse("Back from Output must return to editing, not exit", read(scenario) { it.isFinishing })
        for (id in listOf(R.id.mode_edit, R.id.corner_overlay, R.id.output_caption, R.id.reset_corners)) {
            assertTrue("Editor chrome $id must return", read(scenario) { it.findViewById<View>(id).isShown })
        }
        await(scenario) { !systemBarsHidden(it) }
        assertEquals(edited, read(scenario) { it.quad })
        assertSame(surface, read(scenario) { it.renderer.currentSurface })
        assertTrue(read(scenario) { it.player.wantsToPlay })
        val resumed = read(scenario) { it.renderer.videoFrames }
        await(scenario) { it.renderer.videoFrames > resumed + 10 }
    }

    @Test fun editPreviewAndOutputRenderTheSameScenePixels() = withApp { scenario ->
        val a = bounds(scenario, R.id.corner_a)
        val d = bounds(scenario, R.id.corner_d)
        drag(a.centerX().toFloat(), a.centerY().toFloat(), a.centerX() + 90f, a.centerY() + 40f)
        drag(d.centerX().toFloat(), d.centerY().toFloat(), d.centerX() + 30f, d.centerY() - 60f)
        click(scenario, R.id.play_pause)
        SystemClock.sleep(400)
        val quad = read(scenario) { it.quad }
        val preview = capture(scenario)
        val previewViewport = read(scenario) { it.renderer.viewport }
        click(scenario, R.id.mode_output)
        awaitCleanOutput(scenario)
        SystemClock.sleep(300)
        val output = capture(scenario)
        val outputViewport = read(scenario) { it.renderer.viewport }
        assertEquals(Viewport.fit(output.width, output.height, 0.0), outputViewport)
        assertTrue("Output must be drawn larger than the inset preview", outputViewport.scale > previewViewport.scale)

        val mapping = Homography(quad)
        val errors = mutableListOf<Int>()
        for (x in listOf(.16, .3, .47, .66, .84)) for (y in listOf(.2, .38, .61, .8)) {
            val scene = mapping.map(Point(x, y))
            errors += difference(pixel(preview, previewViewport.toScreen(scene)), pixel(output, outputViewport.toScreen(scene)))
        }
        android.util.Log.i("LumisTest", "Edit/Output pixel errors: mean=${errors.average()}, max=${errors.maxOrNull()}")
        assertTrue("Edit and Output must show the same projective scene: $errors", errors.average() < 6)
        assertTrue("At least 18/20 shared-scene samples must match: $errors", errors.count { it < 14 } >= 18)

        val markers = listOf(
            Point(.05, .05) to Color.rgb(103, 232, 249),
            Point(.95, .05) to Color.rgb(167, 139, 250),
            Point(.95, .95) to Color.rgb(251, 191, 36),
            Point(.05, .95) to Color.rgb(52, 211, 153),
        )
        for ((point, expected) in markers) {
            val actual = pixel(output, outputViewport.toScreen(mapping.map(point)))
            assertTrue("Output marker $point must stay upright; expected $expected got $actual", difference(expected, actual) < 22)
        }
        preview.recycle()
        output.recycle()
    }

    @Test fun outputIgnoresTouchesSoTheProjectedSceneCannotBeEditedAccidentally() = withApp { scenario ->
        val corner = bounds(scenario, R.id.corner_a)
        val before = read(scenario) { it.quad }
        click(scenario, R.id.mode_output)
        awaitCleanOutput(scenario)
        val viewport = read(scenario) { it.renderer.viewport }
        val a = viewport.toScreen(before.a)
        drag(a.x.toFloat(), a.y.toFloat(), a.x.toFloat() + 120f, a.y.toFloat() + 80f)
        drag(corner.centerX().toFloat(), corner.centerY().toFloat(), corner.centerX() + 80f, corner.centerY() + 40f)
        assertEquals(before, read(scenario) { it.quad })
        assertTrue(read(scenario) { it.outputMode })
    }

    @Test fun outputSurvivesRotationAndBackgroundingWithoutRestoringChrome() = withApp { scenario ->
        val handle = bounds(scenario, R.id.corner_c)
        drag(handle.centerX().toFloat(), handle.centerY().toFloat(), handle.centerX() - 60f, handle.centerY() - 25f)
        val edited = read(scenario) { it.quad }
        click(scenario, R.id.mode_output)
        awaitCleanOutput(scenario)
        try {
            device.setOrientationLeft()
            await(scenario) {
                it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                    it.outputMode && it.renderer.videoFrames > 5
            }
            awaitCleanOutput(scenario)
            assertEquals(edited, read(scenario) { it.quad })
            val canvas = bounds(scenario, R.id.video_canvas)
            assertTrue("Landscape output should be wider than tall", canvas.width() > canvas.height())
            assertEquals(Viewport.fit(canvas.width(), canvas.height(), 0.0), read(scenario) { it.renderer.viewport })
            assertDisplayIsBlackOutsideTheProjectedQuad(scenario, edited)

            repeat(2) {
                scenario.moveToState(Lifecycle.State.CREATED)
                SystemClock.sleep(150)
                scenario.moveToState(Lifecycle.State.RESUMED)
                await(scenario) { it.findViewById<Button>(R.id.play_pause).isEnabled }
                awaitCleanOutput(scenario)
                val frames = read(scenario) { it.renderer.videoFrames }
                await(scenario) { it.renderer.videoFrames > frames + 5 }
                assertEquals(edited, read(scenario) { it.quad })
            }
            device.pressBack()
            await(scenario) { !it.outputMode && it.findViewById<View>(R.id.play_pause).isShown }
            assertEquals(edited, read(scenario) { it.quad })
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }

    @Test fun playbackFailureInOutputReturnsToEditorRecoveryWithoutLosingTheScene() = withApp { scenario ->
        val handle = bounds(scenario, R.id.corner_d)
        drag(handle.centerX().toFloat(), handle.centerY().toFloat(), handle.centerX() + 40f, handle.centerY() - 30f)
        val edited = read(scenario) { it.quad }
        click(scenario, R.id.mode_output)
        awaitCleanOutput(scenario)
        scenario.onActivity {
            val texture = android.graphics.SurfaceTexture(false)
            val unavailable = android.view.Surface(texture)
            unavailable.release()
            texture.release()
            it.player.attach(unavailable)
        }
        await(scenario) { !it.outputMode && it.findViewById<View>(R.id.error_panel).isShown }
        assertEquals(edited, read(scenario) { it.quad })
        click(scenario, R.id.retry_playback)
        val frames = read(scenario) { it.renderer.videoFrames }
        await(scenario) { it.renderer.videoFrames > frames + 3 }
        click(scenario, R.id.mode_output)
        awaitCleanOutput(scenario)
        assertEquals(edited, read(scenario) { it.quad })
        assertEquals(Quad.DEFAULT.points.size, read(scenario) { it.quad.points.size })
    }

    private fun awaitCleanOutput(scenario: androidx.test.core.app.ActivityScenario<MainActivity>) {
        await(scenario) {
            val decor = it.window.decorView
            val canvas = it.findViewById<View>(R.id.video_canvas)
            it.outputMode && canvas.width == decor.width && canvas.height == decor.height &&
                systemBarsHidden(it) && it.renderer.outputMode &&
                it.renderer.viewport == Viewport.fit(canvas.width, canvas.height, 0.0)
        }
        for (id in listOf(R.id.mode_edit, R.id.mode_output, R.id.corner_overlay, R.id.corner_a, R.id.output_caption,
            R.id.play_pause, R.id.reset_corners, R.id.fps_toggle, R.id.fps_text, R.id.playback_state, R.id.error_panel)) {
            assertFalse("Editor chrome or diagnostics $id must not be shown in Output", read(scenario) { it.findViewById<View>(id).isShown })
        }
        assertTrue(read(scenario) {
            it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        })
    }

    private fun systemBarsHidden(activity: MainActivity): Boolean {
        val decor = activity.window.decorView
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            val insets = decor.rootWindowInsets ?: return false
            !insets.isVisible(WindowInsets.Type.statusBars()) && !insets.isVisible(WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            val hidden = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            @Suppress("DEPRECATION")
            decor.systemUiVisibility and hidden == hidden
        }
    }

    /** Uses a real display screenshot so system bars, views, and GL output are all checked together. */
    private fun assertDisplayIsBlackOutsideTheProjectedQuad(
        scenario: androidx.test.core.app.ActivityScenario<MainActivity>,
        quad: Quad,
    ) {
        SystemClock.sleep(500)
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val canvas = bounds(scenario, R.id.video_canvas)
        assertEquals("Output canvas must cover the display", screenshot.width, canvas.width())
        assertEquals("Output canvas must cover the display", screenshot.height, canvas.height())
        val viewport = read(scenario) { it.renderer.viewport }
        val corners = quad.points.map { viewport.toScreen(it) }
        var checked = 0
        val failures = mutableListOf<String>()
        for (y in 0 until screenshot.height step 6) for (x in 0 until screenshot.width step 6) {
            if (distanceOutside(Point(x.toDouble(), y.toDouble()), corners) < 4.0) continue
            val color = screenshot.getPixel(x, y)
            checked++
            if (Color.red(color) > 2 || Color.green(color) > 2 || Color.blue(color) > 2) {
                if (failures.size < 8) failures += "($x,$y)=#${Integer.toHexString(color)}"
            }
        }
        screenshot.recycle()
        assertTrue("Too few output pixels checked: $checked", checked > 10_000)
        assertTrue("Clean output must be black outside the mapped surface: $failures", failures.isEmpty())
    }

    /** Positive outside a clockwise convex quad, negative or zero inside. */
    private fun distanceOutside(point: Point, corners: List<Point>): Double {
        var outside = Double.NEGATIVE_INFINITY
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            val length = kotlin.math.hypot(b.x - a.x, b.y - a.y)
            val cross = (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
            outside = maxOf(outside, -cross / length)
        }
        return outside
    }

    private fun pixel(image: Bitmap, point: Point) = image.getPixel(
        point.x.roundToInt().coerceIn(0, image.width - 1),
        point.y.roundToInt().coerceIn(0, image.height - 1),
    )

    private fun difference(expected: Int, actual: Int) =
        (abs(Color.red(expected) - Color.red(actual)) + abs(Color.green(expected) - Color.green(actual)) +
            abs(Color.blue(expected) - Color.blue(actual))) / 3
}
