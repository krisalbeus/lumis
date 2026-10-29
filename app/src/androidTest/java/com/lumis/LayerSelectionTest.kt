package com.lumis

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumis.editor.PreviewTransform
import com.lumis.geometry.Point
import com.lumis.geometry.Quad
import com.lumis.geometry.Viewport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LayerSelectionTest {
    @Test fun tapSelectsTopmostOverlappingQuadWithoutReorderingOrChangingResources() = withApp { scenario ->
        scenario.onActivity { it.duplicateSurface(); it.selectSurface(1) }
        await(scenario) { it.renderer.framesFor(2) > 3 }
        val layers = read(scenario) { it.scene.surfaces }
        val resources = read(scenario) { it.scene.surfaces.associate { s -> s.id to it.renderer.surfaceFor(s.id) } }
        val point = screen(scenario, Point(.5, .5))
        tap(point)
        assertEquals(2L, read(scenario) { it.scene.selectedId })
        assertEquals(layers, read(scenario) { it.scene.surfaces })
        assertEquals(resources, read(scenario) { it.scene.surfaces.associate { s -> s.id to it.renderer.surfaceFor(s.id) } })
        scenario.onActivity { it.undoEdit() }
        assertFalse(read(scenario) { it.canUndo })
        tap(screen(scenario, Point(.5, .5)))
        assertEquals(1L, read(scenario) { it.scene.selectedId })
        assertTrue(read(scenario) { it.canRedo })
    }

    @Test fun hiddenAndZeroOpacityLayersAreSkippedButTranslucentLayerIsSelectable() = withApp { scenario ->
        scenario.onActivity {
            it.duplicateSurface()
            it.updateSurface(it.scene.selected!!.copy(visible = false))
        }
        tap(screen(scenario, Point(.5, .5)))
        assertEquals(1L, read(scenario) { it.scene.selectedId })
        scenario.onActivity {
            val top = it.scene.surfaces.last()
            it.updateSurface(top.copy(visible = true, opacity = 0f))
            it.selectSurface(2)
        }
        tap(screen(scenario, Point(.5, .5)))
        assertEquals(1L, read(scenario) { it.scene.selectedId })
        scenario.onActivity { it.updateSurface(it.scene.surfaces.last().copy(opacity = .1f)) }
        tap(screen(scenario, Point(.5, .5)))
        assertEquals(2L, read(scenario) { it.scene.selectedId })
    }

    @Test fun hitTestingUsesActualWarpedQuadNotBoundingBoxAndRespectsZoomPan() = withApp { scenario ->
        scenario.onActivity {
            it.duplicateSurface()
            it.updateSurface(it.scene.selected!!.copy(quad = diamond))
            it.setPreviewTransform(PreviewTransform(2.0, .02, -.03))
        }
        tap(screen(scenario, Point(.3, .3)))
        assertEquals(1L, read(scenario) { it.scene.selectedId })
        tap(screen(scenario, Point(.5, .5)))
        assertEquals(2L, read(scenario) { it.scene.selectedId })
        // The fitted-frame margin and empty area must leave the current selection unchanged.
        tap(screen(scenario, Point(.02, .5)))
        assertEquals(2L, read(scenario) { it.scene.selectedId })
    }

    @Test fun swipesLongHoldsAndCancelledTouchesDoNotSelect() = withApp { scenario ->
        scenario.onActivity { it.duplicateSurface(); it.selectSurface(1) }
        val point = screen(scenario, Point(.5, .5))
        drag(point.x.toFloat(), point.y.toFloat(), point.x.toFloat() + 50, point.y.toFloat())
        assertEquals(1L, read(scenario) { it.scene.selectedId })
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x.toFloat(), point.y.toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(700)
        send(MotionEvent.ACTION_UP)
        assertEquals(1L, read(scenario) { it.scene.selectedId })
        send(MotionEvent.ACTION_DOWN)
        send(MotionEvent.ACTION_CANCEL)
        assertEquals(1L, read(scenario) { it.scene.selectedId })
    }

    @Test fun multiplePointersNeverSelectEvenIfTheyDoNotMove() = withApp { scenario ->
        scenario.onActivity { it.duplicateSurface(); it.selectSurface(1) }
        scenario.onActivity { activity ->
            val canvas = activity.findViewById<View>(R.id.video_canvas)
            val stage = canvas.parent as ViewGroup
            val down = SystemClock.uptimeMillis()
            fun send(action: Int, count: Int) {
                val properties = Array(count) { i -> MotionEvent.PointerProperties().apply {
                    id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }
                val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                    x = canvas.width / 2f + i * 40
                    y = canvas.height / 2f
                    pressure = 1f
                    size = 1f
                } }
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coords,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                stage.dispatchTouchEvent(event)
                event.recycle()
            }
            send(MotionEvent.ACTION_DOWN, 1)
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
            send(MotionEvent.ACTION_UP, 1)
        }
        assertEquals(1L, read(scenario) { it.scene.selectedId })
        assertEquals(PreviewTransform(), read(scenario) { it.previewTransform })
    }

    @Test fun selectedCornerDragKeepsOwnershipAndOutputNeverSelects() = withApp { scenario ->
        scenario.onActivity { it.duplicateSurface(); it.selectSurface(1) }
        val a = bounds(scenario, R.id.corner_a)
        drag(a.centerX().toFloat(), a.centerY().toFloat(), a.centerX() + 30f, a.centerY() + 20f)
        assertEquals(1L, read(scenario) { it.scene.selectedId })
        assertNotEquals(Quad.DEFAULT, read(scenario) { it.quad })
        assertEquals(Quad.DEFAULT, read(scenario) { it.scene.surfaces.last().quad })
        scenario.onActivity { it.setOutputMode(true) }
        await(scenario) { it.renderer.viewport == Viewport.fit(
            it.findViewById<View>(R.id.video_canvas).width, it.findViewById<View>(R.id.video_canvas).height, 0.0) }
        tap(screen(scenario, Point(.5, .5)))
        assertEquals(1L, read(scenario) { it.scene.selectedId })
    }

    private fun screen(scenario: androidx.test.core.app.ActivityScenario<MainActivity>, point: Point): Point {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return read(scenario) {
            val canvas = it.findViewById<View>(R.id.video_canvas)
            val location = IntArray(2)
            canvas.getLocationOnScreen(location)
            val base = Viewport.fit(canvas.width, canvas.height,
                if (it.outputMode) 0.0 else 28.0 * it.resources.displayMetrics.density)
            val viewport = if (it.outputMode) base else it.previewTransform.apply(base)
            val p = viewport.toScreen(point)
            Point(p.x + location[0], p.y + location[1])
        }
    }

    private fun tap(point: Point) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x.toFloat(), point.y.toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private val diamond = Quad(Point(.5, .2), Point(.8, .5), Point(.5, .8), Point(.2, .5))
}
