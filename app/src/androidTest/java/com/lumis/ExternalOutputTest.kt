package com.lumis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.Switch
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
class ExternalOutputTest {
    @Test fun noDisplayKeepsOutputOffWithExplicitConnectionHelp() = withApp { scenario ->
        assertTrue(read(scenario) { it.externalOutput.displays.isEmpty() })
        assertFalse(read(scenario) { it.findViewById<Switch>(R.id.external_toggle).isEnabled })
        assertFalse(read(scenario) { it.externalOutput.enabled })
    }

    @Test fun actualSecondDisplayShowsCleanVideoWhilePhoneStaysInEdit() = withApp { scenario ->
        VirtualMonitor().use { monitor ->
            await(scenario) { it.externalOutput.displays.any { d -> d.displayId == monitor.id } }
            assertFalse(read(scenario) { it.externalOutput.enabled })
            val decoder = read(scenario) { it.renderer.surfaceFor(1) }
            scenario.onActivity { it.externalOutput.enable(true) }
            await(scenario) { it.externalOutput.active && it.renderer.externalFrames > 2 }
            assertFalse(read(scenario) { it.outputMode })
            assertTrue(read(scenario) { it.findViewById<View>(R.id.corner_overlay).isShown })
            assertSame(decoder, read(scenario) { it.renderer.surfaceFor(1) })
            monitor.awaitImage { image ->
                val viewport = Viewport.fit(image.width, image.height, 0.0)
                val h = Homography(Quad.DEFAULT)
                val p = viewport.toScreen(h.map(Point(.05, .05)))
                closeColor(Color.rgb(103, 232, 249), image.getPixel(p.x.roundToInt(), p.y.roundToInt()), 22) &&
                    blackOutside(image, viewport, Quad.DEFAULT)
            }
            val phone = capture(scenario)
            assertTrue("Editor must still render on its own surface", phone.getPixel(phone.width / 2, phone.height / 2) != Color.BLACK)
            phone.recycle()
            val frames = read(scenario) { it.renderer.externalFrames }
            scenario.onActivity {
                it.setPreviewTransform(PreviewTransform(2.0, .2, -.1))
                it.updateSurface(it.scene.selected!!.copy(quad = warped))
            }
            await(scenario) { it.renderer.externalFrames > frames + 3 }
            monitor.awaitImage { image ->
                val viewport = Viewport.fit(image.width, image.height, 0.0)
                val p = viewport.toScreen(Homography(warped).map(Point(.05, .05)))
                closeColor(Color.rgb(103, 232, 249), image.getPixel(p.x.roundToInt(), p.y.roundToInt()), 22) &&
                    blackOutside(image, viewport, warped)
            }
            scenario.onActivity { it.setOutputMode(true) }
            await(scenario) { it.outputMode && it.externalOutput.active }
            scenario.onActivity { it.setOutputMode(false); it.undoEdit() }
            assertEquals(Quad.DEFAULT, read(scenario) { it.quad })
            assertSame(decoder, read(scenario) { it.renderer.surfaceFor(1) })
            scenario.onActivity { it.externalOutput.enable(false) }
            assertNull(read(scenario) { it.externalOutput.surfaceView })
            assertFalse(read(scenario) { it.externalOutput.active })
        }
    }

    @Test fun unplugDoesNotMoveOutputToAnotherDisplayAndReconnectRequiresConsent() = withApp { scenario ->
        VirtualMonitor().use { first ->
            VirtualMonitor().use { second ->
                await(scenario) { it.externalOutput.displays.size == 2 }
                scenario.onActivity { it.externalOutput.select(first.id); it.externalOutput.enable(true) }
                await(scenario) { it.externalOutput.active }
                val scene = read(scenario) { it.scene }
                first.disconnect()
                await(scenario) { !it.externalOutput.enabled && it.externalOutput.selectedId == second.id }
                assertFalse(read(scenario) { it.externalOutput.active })
                assertEquals(scene, read(scenario) { it.scene })
                scenario.onActivity { it.externalOutput.enable(true) }
                await(scenario) { it.externalOutput.active }
                second.awaitImage { it.getPixel(it.width / 2, it.height / 2) != Color.BLACK }
            }
        }
        await(scenario) { it.externalOutput.displays.isEmpty() && !it.externalOutput.enabled }
        VirtualMonitor().use { replacement ->
            await(scenario) { it.externalOutput.selectedId == replacement.id }
            assertFalse(read(scenario) { it.externalOutput.enabled })
        }
    }

    @Test fun recreationAndBackgroundResumeRestoreIndependentOutputWithoutChangingTheScene() = withApp { scenario ->
        VirtualMonitor(800, 600).use { monitor ->
            await(scenario) { it.externalOutput.selectedId == monitor.id }
            scenario.onActivity {
                it.externalOutput.enable(true)
                it.updateSurface(it.scene.selected!!.copy(quad = warped))
            }
            await(scenario) { it.externalOutput.active }
            val scene = read(scenario) { it.scene }
            scenario.recreate()
            await(scenario) { it.externalOutput.active && it.renderer.externalFrames > 2 }
            assertEquals(scene, read(scenario) { it.scene })
            repeat(3) {
                scenario.moveToState(Lifecycle.State.CREATED)
                assertFalse(read(scenario) { it.externalOutput.active })
                monitor.awaitImage { image ->
                    (2 until image.height step 12).all { y ->
                        (2 until image.width step 12).all { x -> image.getPixel(x, y) == Color.BLACK }
                    }
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                await(scenario) { it.externalOutput.active && it.renderer.framesFor(1) > 3 }
                assertEquals(scene, read(scenario) { it.scene })
            }
            monitor.awaitImage { image ->
                val v = Viewport.fit(image.width, image.height, 0.0)
                val p = v.toScreen(Homography(warped).map(Point(.05, .05)))
                closeColor(Color.rgb(103, 232, 249), image.getPixel(p.x.roundToInt(), p.y.roundToInt()), 22) &&
                    blackOutside(image, v, warped)
            }
        }
    }

    @Test fun pausedImagesOpacityVisibilityAndUndoUpdateBothTargetsWithoutAnotherDecoder() = withApp { scenario ->
        VirtualMonitor(600, 800).use { monitor ->
            await(scenario) { it.externalOutput.selectedId == monitor.id }
            val red = fixture(Color.RED)
            val blue = fixture(Color.BLUE)
            scenario.onActivity {
                it.importFromUri(red)
                it.addSurface(MediaSource.Image(blue))
                it.updateSurface(it.scene.selected!!.copy(opacity = .5f))
                it.externalOutput.enable(true)
            }
            await(scenario) { it.externalOutput.active }
            monitor.awaitImage { closeColor(Color.rgb(127, 0, 128), it.getPixel(it.width / 2, it.height / 2), 3) }
            val bottom = read(scenario) { it.renderer.surfaceFor(1) }
            val top = read(scenario) { it.renderer.surfaceFor(2) }
            val decoderFrames = read(scenario) { it.renderer.videoFrames }
            scenario.onActivity {
                it.setPreviewTransform(PreviewTransform(4.0, .3, -.2))
                it.updateSurface(it.scene.selected!!.copy(visible = false))
            }
            monitor.awaitImage { closeColor(Color.RED, it.getPixel(it.width / 2, it.height / 2), 3) }
            scenario.onActivity { it.undoEdit() }
            monitor.awaitImage { closeColor(Color.rgb(127, 0, 128), it.getPixel(it.width / 2, it.height / 2), 3) }
            assertSame(bottom, read(scenario) { it.renderer.surfaceFor(1) })
            assertSame(top, read(scenario) { it.renderer.surfaceFor(2) })
            assertEquals(decoderFrames, read(scenario) { it.renderer.videoFrames })
        }
    }

    @Test fun outputFailureStaysBlackWithRecoveryOnlyOnThePhoneAndRetryWorks() = withApp { scenario ->
        VirtualMonitor().use { monitor ->
            await(scenario) { it.externalOutput.selectedId == monitor.id }
            scenario.onActivity { it.externalOutput.enable(true) }
            await(scenario) { it.externalOutput.active }
            monitor.awaitImage { it.getPixel(it.width / 2, it.height / 2) != Color.BLACK }
            val scene = read(scenario) { it.scene }
            val decoder = read(scenario) { it.renderer.surfaceFor(1) }
            scenario.onActivity {
                val surface = requireNotNull(it.externalOutput.surfaceView).holder.surface
                it.externalOutput.rendererState(surface, "Controlled test failure")
            }
            assertFalse(read(scenario) { it.externalOutput.active })
            assertEquals("Controlled test failure", read(scenario) { it.externalOutput.error })
            monitor.awaitImage { image ->
                (2 until image.height step 12).all { y ->
                    (2 until image.width step 12).all { x -> image.getPixel(x, y) == Color.BLACK }
                }
            }
            assertEquals(scene, read(scenario) { it.scene })
            assertSame(decoder, read(scenario) { it.renderer.surfaceFor(1) })
            click(scenario, R.id.external_retry)
            await(scenario) { it.externalOutput.active && it.externalOutput.error == null }
            monitor.awaitImage { it.getPixel(it.width / 2, it.height / 2) != Color.BLACK }
        }
    }

    @Test fun pausedVideoUsesTheSameExactDecodedFrameOnBothTargets() = withApp { scenario ->
        VirtualMonitor().use { monitor ->
            await(scenario) { it.externalOutput.selectedId == monitor.id }
            click(scenario, R.id.play_pause)
            SystemClock.sleep(200)
            scenario.onActivity { it.externalOutput.enable(true) }
            await(scenario) { it.externalOutput.active }
            val frames = read(scenario) { it.renderer.videoFrames }
            val phone = capture(scenario)
            val preview = read(scenario) { it.renderer.viewport }
            monitor.awaitImage { image ->
                val output = Viewport.fit(image.width, image.height, 0.0)
                val h = Homography(Quad.DEFAULT)
                listOf(Point(.05, .05), Point(.95, .05), Point(.95, .95), Point(.05, .95)).all { source ->
                    val p = preview.toScreen(h.map(source))
                    val q = output.toScreen(h.map(source))
                    closeColor(phone.getPixel(p.x.roundToInt(), p.y.roundToInt()), image.getPixel(q.x.roundToInt(), q.y.roundToInt()), 3)
                }
            }
            phone.recycle()
            scenario.onActivity { it.setPreviewTransform(PreviewTransform(.5)); it.setOutputMode(true) }
            await(scenario) { it.outputMode }
            assertFalse(read(scenario) { it.player.wantsToPlay })
            assertEquals(frames, read(scenario) { it.renderer.videoFrames })
        }
    }

    @Test fun repeatedHotplugAndDisplayResizeKeepOneDecoderAndCorrectAspectRatio() = withApp { scenario ->
        val decoder = read(scenario) { it.renderer.surfaceFor(1) }
        val scene = read(scenario) { it.scene }
        repeat(5) {
            VirtualMonitor().use { monitor ->
                await(scenario) { a -> a.externalOutput.selectedId == monitor.id }
                assertFalse(read(scenario) { a -> a.externalOutput.enabled })
                scenario.onActivity { a -> a.externalOutput.enable(true) }
                await(scenario) { a -> a.externalOutput.active }
                monitor.awaitImage { image -> image.getPixel(image.width / 2, image.height / 2) != Color.BLACK }
                monitor.resize(800, 600)
                await(scenario) { a ->
                    a.externalOutput.active && a.renderer.externalViewport == Viewport.fit(800, 600, 0.0)
                }
                assertEquals(scene, read(scenario) { a -> a.scene })
                assertSame(decoder, read(scenario) { a -> a.renderer.surfaceFor(1) })
            }
            await(scenario) { a -> a.externalOutput.displays.isEmpty() && !a.externalOutput.enabled }
        }
    }

    @Test fun fourVideosAndAnImageShareTheirExistingResourcesWithTheSecondDisplay() = withApp { scenario ->
        VirtualMonitor(960, 540).use { monitor ->
            await(scenario) { it.externalOutput.selectedId == monitor.id }
            val red = fixture(Color.RED)
            scenario.onActivity { activity ->
                repeat(3) { activity.addSurface() }
                activity.scene.surfaces.toList().forEachIndexed { index, layer ->
                    val x = .05 + (index % 2) * .32
                    val y = .05 + (index / 2) * .48
                    activity.updateSurface(layer.copy(quad = Quad(Point(x, y), Point(x + .28, y),
                        Point(x + .28, y + .4), Point(x, y + .4))))
                }
                activity.addSurface(MediaSource.Image(red))
                activity.updateSurface(activity.scene.selected!!.copy(quad = Quad(Point(.75, .1), Point(.95, .1),
                    Point(.95, .9), Point(.75, .9))))
            }
            await(scenario) { a -> (1L..4L).all { a.renderer.framesFor(it) > 3 } && a.renderer.surfaceFor(5) != null }
            val surfaces = read(scenario) { a -> (1L..5L).associateWith { a.renderer.surfaceFor(it) } }
            val frames = read(scenario) { a -> (1L..4L).associateWith { a.renderer.framesFor(it) } }
            scenario.onActivity { it.externalOutput.enable(true) }
            await(scenario) { a -> a.externalOutput.active && (1L..4L).all { a.renderer.framesFor(it) > frames.getValue(it) + 10 } }
            val scene = read(scenario) { it.scene }
            monitor.awaitImage { image ->
                val viewport = Viewport.fit(image.width, image.height, 0.0)
                scene.surfaces.all { layer ->
                    val source = if (layer.source is MediaSource.Image) Point(.5, .5) else Point(.05, .05)
                    val p = viewport.toScreen(Homography(layer.quad).map(source))
                    val expected = if (layer.source is MediaSource.Image) Color.RED else Color.rgb(103, 232, 249)
                    closeColor(expected, image.getPixel(p.x.roundToInt(), p.y.roundToInt()), 22)
                }
            }
            assertEquals(surfaces, read(scenario) { a -> (1L..5L).associateWith { a.renderer.surfaceFor(it) } })
            assertEquals(scene, read(scenario) { it.scene })
        }
    }

    @Test fun externalControlsHaveReadableScrollableTouchTargetsAndRouteSelectionIsExplicit() = withApp { scenario ->
        VirtualMonitor().use { first ->
            VirtualMonitor().use { second ->
                await(scenario) { it.externalOutput.displays.size == 2 }
                for (id in listOf(R.id.external_toggle, R.id.external_choose)) {
                    scenario.onActivity {
                        val view = it.findViewById<View>(id)
                        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity {
                        val view = it.findViewById<android.widget.TextView>(id)
                        val visible = Rect()
                        assertTrue(view.getGlobalVisibleRect(visible))
                        assertEquals(view.height, visible.height())
                        assertTrue(view.height >= 48 * it.resources.displayMetrics.density)
                        assertTrue(view.width >= 48 * it.resources.displayMetrics.density)
                        assertTrue(view.layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                    }
                }
                scenario.onActivity { it.externalOutput.select(first.id); it.externalOutput.enable(true) }
                await(scenario) { it.externalOutput.active }
                scenario.onActivity { it.externalOutput.select(second.id) }
                assertFalse(read(scenario) { it.externalOutput.enabled || it.externalOutput.active })
                assertEquals(second.id, read(scenario) { it.externalOutput.selectedId })
                click(scenario, R.id.external_toggle)
                await(scenario) { it.externalOutput.active }
            }
        }
    }

    private fun fixture(color: Int): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "external-${System.nanoTime()}.png")
        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
        return Uri.fromFile(file)
    }

    private class VirtualMonitor(val width: Int = 640, val height: Int = 360) : AutoCloseable {
        private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        private val display: VirtualDisplay = requireNotNull(
            InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(DisplayManager::class.java)
                .createVirtualDisplay("Lumis test monitor ${System.nanoTime()}", width, height, 160, reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY))
        val id = display.display.displayId
        private var connected = true
        fun disconnect() { if (connected) { connected = false; display.release() } }
        fun resize(width: Int, height: Int) { display.resize(width, height, 160) }

        fun awaitImage(check: (Bitmap) -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 12000
            while (SystemClock.uptimeMillis() < deadline) {
                reader.acquireLatestImage()?.use { image ->
                    val plane = image.planes[0]
                    val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    val passed = check(bitmap)
                    if (bitmap !== padded) bitmap.recycle()
                    padded.recycle()
                    if (passed) return
                }
                SystemClock.sleep(30)
            }
            fail("Virtual display did not produce the expected clean GPU pixels")
        }

        override fun close() { disconnect(); reader.close() }
    }

    private fun blackOutside(image: Bitmap, viewport: Viewport, quad: Quad): Boolean {
        val left = quad.points.minOf { it.x }
        val top = quad.points.minOf { it.y }
        val right = quad.points.maxOf { it.x }
        val bottom = quad.points.maxOf { it.y }
        for (y in 2 until image.height step 12) for (x in 2 until image.width step 12) {
            val p = viewport.toScene(Point(x.toDouble(), y.toDouble()))
            if (p.x < left - .02 || p.x > right + .02 || p.y < top - .02 || p.y > bottom + .02) {
                if (image.getPixel(x, y) != Color.BLACK) return false
            }
        }
        return true
    }

    private fun closeColor(a: Int, b: Int, tolerance: Int) = listOf(0, 8, 16).all { abs((a shr it and 255) - (b shr it and 255)) <= tolerance }
    private val warped = Quad(Point(.25, .17), Point(.78, .3), Point(.68, .84), Point(.16, .7))
}
