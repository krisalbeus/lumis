package com.lumis

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lumis.editor.CornerOverlay
import com.lumis.editor.PreviewTransform
import com.lumis.geometry.Homography
import com.lumis.geometry.Point
import com.lumis.geometry.Quad
import com.lumis.geometry.Viewport
import com.lumis.media.MediaSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class EditingPolishTest {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun zoomAndPanMoveActualGpuPixelsButNeverSceneOrOutput() = withApp { scenario ->
        click(scenario, R.id.play_pause)
        SystemClock.sleep(150)
        val scene = read(scenario) { it.scene }
        val surface = read(scenario) { it.renderer.currentSurface }
        val original = capture(scenario)
        val base = read(scenario) { it.renderer.viewport }
        scenario.onActivity { it.setPreviewTransform(PreviewTransform(1.25, .08, .03)) }
        await(scenario) { it.renderer.viewport == it.previewTransform.apply(base) }
        val zoomed = capture(scenario)
        compareMappedSamples(original, base, zoomed, read(scenario) { it.renderer.viewport }, scene.selected!!.quad)
        original.recycle()
        zoomed.recycle()
        assertEquals(scene, read(scenario) { it.scene })
        assertFalse(read(scenario) { it.canUndo })
        click(scenario, R.id.mode_output)
        await(scenario) {
            val view = it.findViewById<View>(R.id.video_canvas)
            it.renderer.viewport == Viewport.fit(view.width, view.height, 0.0) && view.width == it.window.decorView.width &&
                view.height == it.window.decorView.height
        }
        val output = capture(scenario)
        val h = Homography(scene.selected!!.quad)
        val p = read(scenario) { it.renderer.viewport.toScreen(h.map(Point(.05, .05))) }
        assertColor(Color.rgb(103, 232, 249), output.getPixel(p.x.roundToInt(), p.y.roundToInt()), 22)
        output.recycle()
        for (id in listOf(R.id.undo, R.id.redo, R.id.zoom_in, R.id.pan_canvas, R.id.zoom_level)) {
            assertFalse(read(scenario) { it.findViewById<View>(id).isShown })
        }
        assertSame(surface, read(scenario) { it.renderer.currentSurface })
        device.pressBack()
        await(scenario) { !it.outputMode && it.renderer.viewport == it.previewTransform.apply(base) }
        click(scenario, R.id.fit_canvas)
        await(scenario) { it.renderer.viewport == base }
        assertEquals(scene, read(scenario) { it.scene })
    }

    @Test fun aContinuousCornerDragIsExactlyOneUndoEntryAndRedoRestoresIt() = withApp { scenario ->
        val initial = read(scenario) { it.quad }
        val handle = bounds(scenario, R.id.corner_a)
        drag(handle.centerX().toFloat(), handle.centerY().toFloat(), handle.centerX() + 55f, handle.centerY() + 25f)
        val moved = read(scenario) { it.quad }
        assertNotEquals(initial, moved)
        assertTrue(read(scenario) { it.canUndo })
        click(scenario, R.id.undo)
        assertEquals(initial, read(scenario) { it.quad })
        assertFalse(read(scenario) { it.canUndo })
        assertTrue(read(scenario) { it.canRedo })
        click(scenario, R.id.redo)
        assertEquals(moved, read(scenario) { it.quad })
        click(scenario, R.id.undo)
        val b = bounds(scenario, R.id.corner_b)
        drag(b.centerX().toFloat(), b.centerY().toFloat(), b.centerX() - 35f, b.centerY() + 15f)
        assertFalse(read(scenario) { it.canRedo })
    }

    @Test fun cornerEditingAtZoomUsesTheInversePreviewTransform() = withApp { scenario ->
        scenario.onActivity {
            it.updateSurface(it.scene.selected!!.copy(quad = central))
            it.setPreviewTransform(PreviewTransform(1.5, .03, -.02))
        }
        await(scenario) { it.findViewById<CornerOverlay>(R.id.corner_overlay).viewport == it.renderer.viewport }
        val before = read(scenario) { it.quad }
        val viewport = read(scenario) { it.renderer.viewport }
        val a = bounds(scenario, R.id.corner_a)
        val dx = 30f
        val dy = 20f
        drag(a.centerX().toFloat(), a.centerY().toFloat(), a.centerX() + dx, a.centerY() + dy)
        val moved = read(scenario) { it.quad }
        assertEquals(before.a.x + dx / viewport.width, moved.a.x, .002)
        assertEquals(before.a.y + dy / viewport.height, moved.a.y, .002)
        assertEquals(before.b, moved.b)
        click(scenario, R.id.undo)
        assertEquals(before, read(scenario) { it.quad })
        assertEquals(PreviewTransform(1.5, .03, -.02), read(scenario) { it.previewTransform })
    }

    @Test fun twoFingerNavigationDoesNotCreateHistoryAndSingleFingerCannotPan() = withApp { scenario ->
        val before = read(scenario) { it.scene }
        scenario.onActivity { activity ->
            val view = activity.findViewById<GLSurfaceView>(R.id.video_canvas)
            val stage = view.parent as android.view.ViewGroup
            val x = view.width / 2f
            val y = view.height / 2f
            val span = minOf(view.width, view.height) * .1f
            val down = SystemClock.uptimeMillis()
            fun send(action: Int, points: List<Pair<Float, Float>>) {
                val properties = points.mapIndexed { index, _ ->
                    MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER }
                }.toTypedArray()
                val coords = points.map { (px, py) -> MotionEvent.PointerCoords().apply { this.x = px; this.y = py; pressure = 1f; size = 1f } }.toTypedArray()
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, properties, coords,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                stage.dispatchTouchEvent(event)
                event.recycle()
            }
            send(MotionEvent.ACTION_DOWN, listOf(x - span to y))
            send(MotionEvent.ACTION_MOVE, listOf(x - span + 20 to y + 10))
            assertEquals(PreviewTransform(), activity.previewTransform)
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(x - span to y, x + span to y))
            repeat(10) { step ->
                val expansion = span * (step + 1) / 20
                send(MotionEvent.ACTION_MOVE, listOf(x - span - expansion + 20 to y + 15, x + span + expansion + 20 to y + 15))
            }
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(x - span to y, x + span to y))
            send(MotionEvent.ACTION_UP, listOf(x - span to y))
        }
        await(scenario) { it.previewTransform.zoom > 1.4 && it.renderer.viewport == it.previewTransform.apply(
            Viewport.fit(it.findViewById<View>(R.id.video_canvas).width, it.findViewById<View>(R.id.video_canvas).height,
                28.0 * it.resources.displayMetrics.density)) }
        assertEquals(before, read(scenario) { it.scene })
        assertFalse(read(scenario) { it.canUndo })
    }

    @Test fun resetVisibilityAndLayerOperationsUndoWithoutRestartingUnaffectedVideo() = withApp { scenario ->
        val surface = read(scenario) { it.renderer.surfaceFor(1) }
        scenario.onActivity { it.updateSurface(it.scene.selected!!.copy(quad = central, opacity = .6f, visible = false)) }
        click(scenario, R.id.undo)
        assertEquals(Quad.DEFAULT, read(scenario) { it.quad })
        assertTrue(read(scenario) { it.scene.selected!!.visible })
        assertEquals(1f, read(scenario) { it.scene.selected!!.opacity })
        scenario.onActivity { it.duplicateSurface() }
        val duplicate = read(scenario) { it.scene.selectedId!! }
        await(scenario) { it.renderer.framesFor(duplicate) > 3 }
        click(scenario, R.id.undo)
        await(scenario) { it.renderer.surfaceFor(duplicate) == null }
        assertEquals(1, read(scenario) { it.scene.surfaces.size })
        assertSame(surface, read(scenario) { it.renderer.surfaceFor(1) })
        click(scenario, R.id.redo)
        await(scenario) { it.renderer.framesFor(duplicate) > 3 }
        assertSame(surface, read(scenario) { it.renderer.surfaceFor(1) })
        click(scenario, R.id.surface_properties)
        ui(R.id.delete_surface).click()
        text("Delete surface").click()
        assertEquals(1, read(scenario) { it.scene.surfaces.size })
        click(scenario, R.id.undo)
        assertEquals(duplicate, read(scenario) { it.scene.selectedId })
        assertEquals(2, read(scenario) { it.scene.surfaces.size })
        click(scenario, R.id.redo)
        assertEquals(1, read(scenario) { it.scene.surfaces.size })
        scenario.onActivity { it.addSurface() }
        assertTrue(read(scenario) { it.scene.selectedId!! > duplicate })
    }

    @Test fun mediaReplacementUndoRedoRestoresSourceAndPausedPlaybackIntent() = withApp { scenario ->
        click(scenario, R.id.play_pause)
        val uri = imageFixture()
        scenario.onActivity { it.importFromUri(uri) }
        await(scenario) { it.renderer.contentType == com.lumis.renderer.VideoRenderer.ContentType.IMAGE }
        click(scenario, R.id.undo)
        await(scenario) { it.findViewById<Button>(R.id.play_pause).isEnabled && it.renderer.videoFrames > 3 }
        assertEquals(MediaSource.BundledSample, read(scenario) { it.currentSource })
        assertFalse(read(scenario) { it.player.wantsToPlay })
        click(scenario, R.id.redo)
        await(scenario) { it.currentSource == MediaSource.Image(uri) && it.renderer.contentType == com.lumis.renderer.VideoRenderer.ContentType.IMAGE }
        assertFalse(read(scenario) { it.findViewById<Button>(R.id.play_pause).isEnabled })
    }

    @Test fun continuousOpacitySliderCreatesOnlyOneEntryAndAccessibleStepsAreUndoable() = withApp { scenario ->
        click(scenario, R.id.surface_properties)
        val slider = ui(R.id.surface_opacity).visibleBounds
        device.swipe(slider.right - 15, slider.centerY(), slider.left + slider.width() / 3, slider.centerY(), 20)
        text("Done").click()
        val opacity = read(scenario) { it.scene.selected!!.opacity }
        assertTrue(opacity < .6f)
        click(scenario, R.id.undo)
        assertEquals(1f, read(scenario) { it.scene.selected!!.opacity })
        assertFalse(read(scenario) { it.canUndo })
        click(scenario, R.id.redo)
        assertEquals(opacity, read(scenario) { it.scene.selected!!.opacity })
        scenario.onActivity {
            it.findViewById<View>(R.id.corner_a).performAccessibilityAction(R.id.move_right, null)
        }
        assertNotEquals(Quad.DEFAULT, read(scenario) { it.quad })
        click(scenario, R.id.undo)
        assertEquals(Quad.DEFAULT, read(scenario) { it.quad })
        assertEquals(opacity, read(scenario) { it.scene.selected!!.opacity })
    }

    @Test fun previewAndHistorySurviveRecreationAndUndoAutosavesTheRestoredScene() = withApp { scenario ->
        scenario.onActivity {
            it.updateSurface(it.scene.selected!!.copy(quad = central))
            it.setPreviewTransform(PreviewTransform(1.5, .04, -.03))
        }
        scenario.recreate()
        await(scenario) { it.findViewById<Button>(R.id.play_pause).isEnabled && it.canUndo }
        assertEquals(PreviewTransform(1.5, .04, -.03), read(scenario) { it.previewTransform })
        click(scenario, R.id.undo)
        assertEquals(Quad.DEFAULT, read(scenario) { it.quad })
        assertFalse(read(scenario) { it.canUndo })
        assertTrue(read(scenario) { it.canRedo })
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        await(scenario) { it.findViewById<Button>(R.id.play_pause).isEnabled }
        click(scenario, R.id.redo)
        assertEquals(central, read(scenario) { it.quad })
        click(scenario, R.id.undo)
        await(scenario) { it.findViewById<android.widget.TextView>(R.id.project_status).text.toString().endsWith("Saved on device") }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = com.lumis.project.ProjectStore(File(context.filesDir, "projects"))
        assertEquals(Quad.DEFAULT, store.load(read(scenario) { it.currentProjectId!! }).project.state.scene.selected!!.quad)
    }

    @Test fun newEditingControlsAreScrollableReadableAndHaveAccessibleTouchTargets() = withApp { scenario ->
        for (id in listOf(R.id.undo, R.id.redo, R.id.zoom_out, R.id.zoom_in, R.id.fit_canvas, R.id.pan_canvas)) {
            scenario.onActivity {
                val view = it.findViewById<Button>(id)
                view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val view = it.findViewById<Button>(id)
                val visible = Rect()
                assertTrue(view.getGlobalVisibleRect(visible))
                assertEquals(view.height, visible.height())
                assertTrue(view.height >= 48 * it.resources.displayMetrics.density)
                assertTrue(view.width >= 48 * it.resources.displayMetrics.density)
                assertTrue(view.layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                assertTrue(view.text.isNotBlank())
            }
        }
        repeat(12) { click(scenario, R.id.zoom_in) }
        assertEquals(4.0, read(scenario) { it.previewTransform.zoom }, 0.0)
        assertFalse(read(scenario) { it.findViewById<Button>(R.id.zoom_in).isEnabled })
        click(scenario, R.id.pan_canvas)
        text("Move left").click()
        text("Done").click()
        assertTrue(read(scenario) { it.previewTransform.panX < 0 })
        assertFalse(read(scenario) { it.canUndo })
        click(scenario, R.id.fit_canvas)
        assertEquals(PreviewTransform(), read(scenario) { it.previewTransform })
    }

    @Test fun noOpAndCancelledTouchesDoNotDiscardRedoAndPartialCancelledDragIsOneEntry() = withApp { scenario ->
        scenario.onActivity { it.updateSurface(it.scene.selected!!.copy(quad = central)); it.undoEdit() }
        scenario.onActivity { activity ->
            val overlay = activity.findViewById<CornerOverlay>(R.id.corner_overlay)
            val p = overlay.viewport.toScreen(activity.quad.a)
            fun send(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, x, y, 0)
                overlay.dispatchTouchEvent(event)
                event.recycle()
            }
            send(MotionEvent.ACTION_DOWN, p.x.toFloat(), p.y.toFloat())
            send(MotionEvent.ACTION_CANCEL, p.x.toFloat(), p.y.toFloat())
            assertFalse(activity.canUndo)
            assertTrue(activity.canRedo)
            activity.updateSurface(activity.scene.selected!!)
            activity.setPreviewTransform(PreviewTransform(.75))
            assertTrue(activity.canRedo)
            activity.setPreviewTransform(PreviewTransform())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity { activity ->
            val overlay = activity.findViewById<CornerOverlay>(R.id.corner_overlay)
            val p = overlay.viewport.toScreen(activity.quad.a)
            for ((action, delta) in listOf(MotionEvent.ACTION_DOWN to 0, MotionEvent.ACTION_MOVE to 25, MotionEvent.ACTION_CANCEL to 25)) {
                val event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, p.x.toFloat() + delta, p.y.toFloat() + delta, 0)
                overlay.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        assertTrue(read(scenario) { it.canUndo })
        assertFalse(read(scenario) { it.canRedo })
        click(scenario, R.id.undo)
        assertEquals(Quad.DEFAULT, read(scenario) { it.quad })
        assertFalse(read(scenario) { it.canUndo })
    }

    @Test fun resetAndReorderEachUndoAsOneEditWhileSelectionAndPlaybackDoNot() = withApp { scenario ->
        scenario.onActivity { it.updateSurface(it.scene.selected!!.copy(quad = central)) }
        click(scenario, R.id.reset_corners)
        assertEquals(Quad.DEFAULT, read(scenario) { it.quad })
        click(scenario, R.id.undo)
        assertEquals(central, read(scenario) { it.quad })
        click(scenario, R.id.undo)
        assertFalse(read(scenario) { it.canUndo })
        scenario.onActivity { it.addSurface() }
        click(scenario, R.id.surface_properties)
        ui(R.id.move_backward).click()
        text("Done").click()
        assertEquals(listOf(2L, 1L), read(scenario) { it.scene.surfaces.map { s -> s.id } })
        click(scenario, R.id.undo)
        assertEquals(listOf(1L, 2L), read(scenario) { it.scene.surfaces.map { s -> s.id } })
        scenario.onActivity { it.selectSurface(1) }
        click(scenario, R.id.play_pause)
        assertTrue(read(scenario) { it.canRedo })
        click(scenario, R.id.redo)
        assertEquals(listOf(2L, 1L), read(scenario) { it.scene.surfaces.map { s -> s.id } })
    }

    @Test fun zoomedWarpedImageMatchesAnalyticSourceCoordinatesOnTheGpu() = withApp { scenario ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "gradient-${System.nanoTime()}.png")
        val gradient = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0..255) for (x in 0..255) gradient.setPixel(x, y, Color.rgb(x, 128, y))
        file.outputStream().use { assertTrue(gradient.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        gradient.recycle()
        val quad = Quad(Point(.25, .22), Point(.74, .3), Point(.65, .78), Point(.34, .68))
        scenario.onActivity {
            it.importFromUri(Uri.fromFile(file))
            it.updateSurface(it.scene.selected!!.copy(quad = quad))
        }
        val mapping = Homography(quad)
        val forward = Matrix()
        assertTrue(forward.setPolyToPoly(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), 0,
            quad.points.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }.toFloatArray(), 0, 4))
        val inverse = Matrix()
        assertTrue(forward.invert(inverse))
        for (zoom in listOf(.5, 1.5, 4.0)) {
            scenario.onActivity { it.setPreviewTransform(PreviewTransform(zoom, .04, -.03)) }
            await(scenario) { it.findViewById<CornerOverlay>(R.id.corner_overlay).viewport == it.renderer.viewport }
            val deadline = SystemClock.uptimeMillis() + 12000
            var maximum: Int
            do {
                SystemClock.sleep(50)
                val image = capture(scenario)
                val viewport = read(scenario) { it.renderer.viewport }
                maximum = 0
                var count = 0
                for (y in listOf(.1, .3, .5, .7, .9)) for (x in listOf(.1, .3, .5, .7, .9)) {
                    val p = viewport.toScreen(mapping.map(Point(x, y)))
                    if (p.x < 1 || p.y < 1 || p.x >= image.width - 1 || p.y >= image.height - 1) continue
                    val px = p.x.roundToInt()
                    val py = p.y.roundToInt()
                    val center = viewport.toScene(Point(px + .5, py + .5))
                    val source = floatArrayOf(center.x.toFloat(), center.y.toFloat())
                    inverse.mapPoints(source)
                    val expected = Color.rgb((source[0] * 256 - .5f).roundToInt().coerceIn(0, 255), 128,
                        (source[1] * 256 - .5f).roundToInt().coerceIn(0, 255))
                    maximum = maxOf(maximum, difference(expected, image.getPixel(px, py)))
                    count++
                }
                image.recycle()
                assertTrue("At least five GPU samples must be on screen at zoom $zoom", count >= 5)
            } while (maximum > 3 && SystemClock.uptimeMillis() < deadline)
            assertTrue("GPU samples at zoom $zoom must preserve perspective/orientation within 3/255; error=$maximum", maximum <= 3)
        }
    }

    @Test fun deleteLastUndoRestoresTheVideoAndRedoRestoresEmptyCleanOutput() = withApp { scenario ->
        click(scenario, R.id.surface_properties)
        ui(R.id.delete_surface).click()
        text("Delete surface").click()
        assertTrue(read(scenario) { it.scene.surfaces.isEmpty() })
        click(scenario, R.id.undo)
        await(scenario) { it.renderer.framesFor(1) > 3 && it.findViewById<Button>(R.id.play_pause).isEnabled }
        assertEquals(Quad.DEFAULT, read(scenario) { it.quad })
        assertFalse(read(scenario) { it.canUndo })
        click(scenario, R.id.redo)
        scenario.onActivity { it.setOutputMode(true) }
        await(scenario) { it.renderer.surfaceFor(1) == null }
        val image = capture(scenario)
        for (y in 1 until image.height step 40) for (x in 1 until image.width step 40) {
            assertEquals(Color.BLACK, image.getPixel(x, y))
        }
        image.recycle()
        scenario.onActivity { it.setOutputMode(false); it.addSurface() }
        assertTrue(read(scenario) { it.scene.selectedId!! > 1 })
    }

    @Test fun coldProjectReopenKeepsUndoneContentButStartsWithFitAndEmptyHistory() {
        lateinit var id: String
        withApp { scenario ->
            scenario.onActivity {
                it.updateSurface(it.scene.selected!!.copy(quad = central))
                it.undoEdit()
                it.setPreviewTransform(PreviewTransform(2.0, .1, -.1))
            }
            await(scenario) { it.findViewById<android.widget.TextView>(R.id.project_status).text.toString().endsWith("Saved on device") }
            id = read(scenario) { it.currentProjectId!! }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra("project_id", id)).use { scenario ->
            await(scenario) { it.renderer.framesFor(1) > 3 && it.findViewById<Button>(R.id.play_pause).isEnabled }
            assertEquals(Quad.DEFAULT, read(scenario) { it.quad })
            assertEquals(PreviewTransform(), read(scenario) { it.previewTransform })
            assertFalse(read(scenario) { it.canUndo || it.canRedo || it.outputMode })
        }
    }

    private fun compareMappedSamples(a: Bitmap, av: Viewport, b: Bitmap, bv: Viewport, quad: Quad) {
        val mapping = Homography(quad)
        var maximum = 0
        var samples = 0
        for (y in listOf(.05, .95)) for (x in listOf(.05, .95)) {
            val p = mapping.map(Point(x, y))
            val ap = av.toScreen(p)
            val bp = bv.toScreen(p)
            if (bp.x !in 0.0..<b.width.toDouble() || bp.y !in 0.0..<b.height.toDouble()) continue
            val ac = a.getPixel(ap.x.roundToInt(), ap.y.roundToInt())
            val bc = b.getPixel(bp.x.roundToInt(), bp.y.roundToInt())
            maximum = maxOf(maximum, difference(ac, bc))
            samples++
        }
        assertTrue("Multiple widely separated source markers must be captured", samples >= 3)
        assertTrue("Zoomed GPU content must match the same source; maximum RGB error=$maximum", maximum < 18)
    }

    private fun imageFixture(): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "editing-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun difference(a: Int, b: Int) = maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
    private fun assertColor(expected: Int, actual: Int, tolerance: Int) = assertTrue("Expected $expected, got $actual", difference(expected, actual) <= tolerance)
    private fun ui(id: Int) = requireNotNull(device.wait(Until.findObject(By.res("com.lumis",
        InstrumentationRegistry.getInstrumentation().targetContext.resources.getResourceEntryName(id))), 5000))
    private fun text(value: String) = requireNotNull(device.wait(Until.findObject(By.text(
        java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(value), java.util.regex.Pattern.CASE_INSENSITIVE))), 5000))
    private val central = Quad(Point(.3, .3), Point(.7, .3), Point(.7, .7), Point(.3, .7))
}
