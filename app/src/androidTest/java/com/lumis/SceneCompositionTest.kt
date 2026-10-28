package com.lumis

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lumis.geometry.Point
import com.lumis.geometry.Quad
import com.lumis.media.MediaSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class SceneCompositionTest {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun fourIndependentVideosAreVisibleAndAdvanceTogether() = withApp { scenario ->
        repeat(3) { scenario.onActivity { it.addSurface() } }
        val quads = listOf(rect(.05, .05, .46, .46), rect(.54, .05, .95, .46),
            rect(.05, .54, .46, .95), rect(.54, .54, .95, .95))
        scenario.onActivity { activity ->
            activity.scene.surfaces.forEachIndexed { index, layer ->
                activity.updateSurface(layer.copy(quad = quads[index]))
            }
        }
        await(scenario) { activity -> activity.scene.surfaces.all { activity.renderer.framesFor(it.id) > 10 } }
        val before = read(scenario) { activity -> activity.scene.surfaces.associate { it.id to activity.renderer.framesFor(it.id) } }
        await(scenario) { activity -> before.all { (id, count) -> activity.renderer.framesFor(id) > count + 12 } }
        val image = capture(scenario)
        val viewport = read(scenario) { it.renderer.viewport }
        quads.forEach { quad ->
            val center = viewport.toScreen(Point((quad.a.x + quad.c.x) / 2, (quad.a.y + quad.c.y) / 2))
            val color = image.getPixel(center.x.roundToInt(), center.y.roundToInt())
            assertTrue("Every concurrent surface must have real GPU media pixels", Color.red(color) + Color.green(color) + Color.blue(color) > 40)
        }
        image.recycle()
        val selected = read(scenario) { it.scene.selectedId!! }
        click(scenario, R.id.play_pause)
        SystemClock.sleep(300)
        val paused = read(scenario) { it.renderer.framesFor(selected) }
        val others = read(scenario) { it.scene.surfaces.filterNot { s -> s.id == selected }.map { s -> s.id } }
        SystemClock.sleep(500)
        assertTrue(read(scenario) { it.renderer.framesFor(selected) } <= paused + 1)
        assertTrue(read(scenario) { a -> others.all { a.renderer.framesFor(it) > before.getValue(it) + 15 } })
        assertEquals(4, read(scenario) { it.scene.surfaces.size })
    }

    @Test fun actualGpuPixelsFollowZOrderVisibilityAndOpacityInEditAndOutput() = withApp { scenario ->
        val red = fixture(scenario, Color.RED)
        val blue = fixture(scenario, Color.BLUE)
        scenario.onActivity { it.importFromUri(red); it.addSurface(MediaSource.Image(blue)) }
        awaitColor(scenario, Color.BLUE)
        val front = read(scenario) { it.scene.selected!! }
        scenario.onActivity { it.updateSurface(front.copy(opacity = .5f)) }
        awaitColor(scenario, Color.rgb(128, 0, 128))
        scenario.onActivity { it.updateSurface(front.copy(visible = false)) }
        awaitColor(scenario, Color.RED)
        assertEquals(View.GONE, read(scenario) { it.findViewById<View>(R.id.corner_overlay).visibility })
        scenario.onActivity {
            it.updateSurface(front)
            it.selectSurface(it.scene.surfaces.first().id)
        }
        // Selection changes the handles, never z-order.
        awaitColor(scenario, Color.BLUE)
        click(scenario, R.id.surface_properties)
        ui(R.id.move_forward).click()
        uiText("Done").click()
        awaitColor(scenario, Color.RED)
        click(scenario, R.id.mode_output)
        await(scenario) { it.outputMode && !it.findViewById<View>(R.id.layers).isShown }
        awaitColor(scenario, Color.RED)
        device.pressBack()
        await(scenario) { !it.outputMode }
    }

    @Test fun importedImageOrientationAndTransparencyAreCorrectOnGpu() = withApp { scenario ->
        val image = fixture(scenario, Color.RED, markers = true)
        scenario.onActivity { it.importFromUri(image) }
        awaitColor(scenario, Color.RED)
        val viewport = read(scenario) { it.renderer.viewport }
        val quad = read(scenario) { it.quad }
        val bitmap = capture(scenario)
        for ((p, expected) in listOf(
            Point(.17, .17) to Color.GREEN, Point(.83, .17) to Color.BLUE,
            Point(.17, .83) to Color.YELLOW, Point(.83, .83) to Color.CYAN,
        )) {
            val scene = com.lumis.geometry.Homography(quad).map(p)
            val screen = viewport.toScreen(scene)
            assertColor(expected, bitmap.getPixel(screen.x.roundToInt(), screen.y.roundToInt()))
        }
        bitmap.recycle()
        val translucent = fixture(scenario, Color.argb(128, 0, 0, 255))
        val red = fixture(scenario, Color.RED)
        scenario.onActivity {
            it.importFromUri(red)
            it.addSurface(MediaSource.Image(translucent))
        }
        awaitColor(scenario, Color.rgb(127, 0, 128))
        scenario.onActivity { it.updateSurface(it.scene.selected!!.copy(opacity = .5f)) }
        awaitColor(scenario, Color.rgb(191, 0, 64))
    }

    @Test fun layersSelectionAndCornerEditingOnlyChangeTheSelectedSurface() = withApp { scenario ->
        scenario.onActivity { it.duplicateSurface() }
        val second = read(scenario) { it.scene.selected!! }
        click(scenario, R.id.layers)
        requireNotNull(device.wait(Until.findObject(By.desc("Select surface 1")), 5000)).click()
        await(scenario) { it.scene.selectedId == 1L }
        val a = bounds(scenario, R.id.corner_a)
        drag(a.centerX().toFloat(), a.centerY().toFloat(), a.centerX() + 40f, a.centerY() + 20f)
        assertNotEquals(Quad.DEFAULT, read(scenario) { it.quad })
        assertEquals(second, read(scenario) { it.scene.surfaces.last() })
        click(scenario, R.id.surface_properties)
        ui(R.id.surface_visibility).click()
        uiText("Done").click()
        assertFalse(read(scenario) { it.scene.selected!!.visible })
        assertEquals(View.GONE, read(scenario) { it.findViewById<View>(R.id.corner_overlay).visibility })
    }

    @Test fun opacityControlAndConfirmedDeletionWorkIncludingAnEmptyScene() = withApp { scenario ->
        val image = fixture(scenario, Color.RED)
        scenario.onActivity { it.importFromUri(image) }
        awaitColor(scenario, Color.RED)
        click(scenario, R.id.surface_properties)
        val opacity = ui(R.id.surface_opacity)
        val slider = opacity.visibleBounds
        device.swipe(slider.left + slider.width() / 2, slider.centerY(), slider.right - 12, slider.centerY(), 8)
        uiText("Done").click()
        assertTrue(read(scenario) { it.scene.selected!!.opacity } > .9f)
        click(scenario, R.id.surface_properties)
        ui(R.id.duplicate_surface).click()
        await(scenario) { it.scene.surfaces.size == 2 }
        val duplicateId = read(scenario) { it.scene.selectedId }
        click(scenario, R.id.surface_properties)
        ui(R.id.delete_surface).click()
        uiText("Keep surface").click()
        assertEquals(duplicateId, read(scenario) { it.scene.selectedId })
        repeat(2) {
            click(scenario, R.id.surface_properties)
            ui(R.id.delete_surface).click()
            uiText("Delete surface").click()
        }
        await(scenario) { it.scene.surfaces.isEmpty() }
        assertTrue(read(scenario) { it.findViewById<View>(R.id.empty_canvas).isShown })
        assertFalse(read(scenario) { it.findViewById<View>(R.id.surface_properties).isEnabled })
        click(scenario, R.id.mode_output)
        SystemClock.sleep(250)
        val output = capture(scenario)
        for (x in 0 until output.width step 30) for (y in 0 until output.height step 30) assertEquals(Color.BLACK, output.getPixel(x, y))
        output.recycle()
        device.pressBack()
        await(scenario) { !it.outputMode }
        scenario.recreate()
        await(scenario) { it.scene.surfaces.isEmpty() && it.findViewById<View>(R.id.empty_canvas).isShown }
        click(scenario, R.id.add_surface)
        uiText("Bundled sample").click()
        await(scenario) { it.scene.surfaces.size == 1 && it.renderer.framesFor(it.scene.selectedId!!) > 3 }
        assertTrue(read(scenario) { it.scene.selectedId!! > duplicateId!! })
        assertTrue(File(requireNotNull(image.path)).exists())
    }

    @Test fun scenePlaybackSelectionAndPropertiesSurviveRecreationAndBackgrounding() = withApp { scenario ->
        val blue = fixture(scenario, Color.BLUE)
        scenario.onActivity {
            it.addSurface(MediaSource.Image(blue))
            it.updateSurface(it.scene.selected!!.copy(opacity = .6f, quad = rect(.2, .2, .6, .7)))
            it.addSurface()
            it.selectSurface(1)
        }
        await(scenario) { it.renderer.framesFor(3) > 5 }
        click(scenario, R.id.play_pause)
        val before = read(scenario) { it.scene }
        scenario.recreate()
        await(scenario) { it.renderer.framesFor(3) > 5 && it.findViewById<View>(R.id.play_pause).isEnabled }
        assertEquals(before, read(scenario) { it.scene })
        assertFalse(read(scenario) { it.player.wantsToPlay })
        repeat(2) {
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            await(scenario) { it.findViewById<View>(R.id.play_pause).isEnabled }
            assertEquals(before, read(scenario) { it.scene })
            assertFalse(read(scenario) { it.player.wantsToPlay })
        }
        click(scenario, R.id.mode_output)
        scenario.recreate()
        await(scenario) { it.outputMode && it.renderer.framesFor(3) > 5 }
        assertEquals(before, read(scenario) { it.scene })
        assertFalse(read(scenario) { it.findViewById<View>(R.id.layers).isShown })
        device.pressBack()
        await(scenario) { !it.outputMode }
    }

    @Test fun sixImageSurfacesHaveNoArtificialFourSurfaceCap() = withApp { scenario ->
        val red = fixture(scenario, Color.RED)
        scenario.onActivity {
            it.importFromUri(red)
            repeat(5) { _ -> it.addSurface(MediaSource.Image(red)) }
        }
        await(scenario) { a -> a.scene.surfaces.all { a.renderer.surfaceFor(it.id) != null } }
        assertEquals(6, read(scenario) { it.scene.surfaces.size })
        awaitColor(scenario, Color.RED)
    }

    @Test fun replacingAndDeletingOneSurfaceLeavesTheOtherDecoderUntouched() = withApp { scenario ->
        val originalSurface = read(scenario) { it.renderer.surfaceFor(1) }
        val before = read(scenario) { it.renderer.framesFor(1) }
        val blue = fixture(scenario, Color.BLUE)
        val red = fixture(scenario, Color.RED)
        scenario.onActivity { it.addSurface(MediaSource.Image(blue)) }
        awaitColor(scenario, Color.BLUE)
        scenario.onActivity { it.importFromUri(red) }
        awaitColor(scenario, Color.RED)
        click(scenario, R.id.surface_properties)
        ui(R.id.delete_surface).click()
        uiText("Delete surface").click()
        await(scenario) { it.scene.surfaces.size == 1 && it.renderer.framesFor(1) > before + 10 }
        assertSame(originalSurface, read(scenario) { it.renderer.surfaceFor(1) })
        assertEquals(1L, read(scenario) { it.scene.selectedId })
        assertTrue(read(scenario) { it.player.wantsToPlay })
    }

    @Test fun missingUnselectedImageRestoresOnlyThatSurfaceAndAllowsReplacement() = withApp { scenario ->
        val image = fixture(scenario, Color.BLUE)
        val replacement = fixture(scenario, Color.RED)
        scenario.onActivity {
            it.addSurface(MediaSource.Image(image))
            it.updateSurface(it.scene.selected!!.copy(quad = rect(.2, .2, .7, .8), opacity = .6f))
            it.selectSurface(1)
        }
        val before = read(scenario) { it.scene.surfaces.last() }
        assertTrue(File(requireNotNull(image.path)).delete())
        scenario.recreate()
        await(scenario) { it.scene.selectedId == before.id && it.findViewById<View>(R.id.error_panel).isShown }
        assertEquals(before.copy(source = MediaSource.BundledSample), read(scenario) { it.scene.selected })
        assertEquals(MediaSource.BundledSample, read(scenario) { it.scene.surfaces.first().source })
        await(scenario) { it.renderer.framesFor(1) > 5 }
        scenario.onActivity { it.importFromUri(replacement) }
        await(scenario) { !it.findViewById<View>(R.id.error_panel).isShown }
        assertEquals(before.copy(source = MediaSource.Image(replacement)), read(scenario) { it.scene.selected })
    }

    private fun rect(left: Double, top: Double, right: Double, bottom: Double) =
        Quad(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))

    private fun ui(id: Int) = requireNotNull(device.wait(Until.findObject(By.res("com.lumis", readResourceName(id))), 5000))
    private fun readResourceName(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.resources.getResourceEntryName(id)
    private fun uiText(text: String) = requireNotNull(device.wait(Until.findObject(
        By.text(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(text), java.util.regex.Pattern.CASE_INSENSITIVE)),
    ), 5000))

    private fun fixture(scenario: ActivityScenario<MainActivity>, color: Int, markers: Boolean = false): Uri = read(scenario) {
        val file = File(it.cacheDir, "scene-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        if (markers) {
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint()
            for ((rect, c) in listOf(
                android.graphics.Rect(0, 0, 40, 40) to Color.GREEN,
                android.graphics.Rect(80, 0, 120, 40) to Color.BLUE,
                android.graphics.Rect(0, 80, 40, 120) to Color.YELLOW,
                android.graphics.Rect(80, 80, 120, 120) to Color.CYAN,
            )) { paint.color = c; canvas.drawRect(rect, paint) }
        }
        file.outputStream().use { out -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) }
        bitmap.recycle()
        Uri.fromFile(file)
    }

    private fun awaitColor(scenario: ActivityScenario<MainActivity>, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 6000
        var actual = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val bitmap = capture(scenario)
            val point = read(scenario) { it.renderer.viewport.toScreen(Point(.5, .5)) }
            actual = bitmap.getPixel(point.x.roundToInt(), point.y.roundToInt())
            bitmap.recycle()
            if (difference(expected, actual) <= 3) return
            SystemClock.sleep(50)
        }
        assertColor(expected, actual)
    }

    private fun difference(a: Int, b: Int) = maxOf(abs(Color.red(a) - Color.red(b)),
        abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))

    private fun assertColor(expected: Int, actual: Int) {
        assertTrue("Expected #${Integer.toHexString(expected)}, got #${Integer.toHexString(actual)}", difference(expected, actual) <= 3)
    }
}
