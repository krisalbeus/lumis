package com.lumis.editor

import com.lumis.geometry.Point
import com.lumis.geometry.Viewport
import org.junit.Assert.*
import org.junit.Test

class EditingTest {
    private val base = Viewport.fit(1080, 900, 73.5)

    @Test fun identityPreviewIsExactlyTheExistingViewport() {
        assertEquals(base, PreviewTransform().apply(base))
    }

    @Test fun zoomKeepsTheGestureAnchorAndPanUsesScreenDistance() {
        val from = Point(430.0, 390.0)
        val to = Point(490.0, 450.0)
        val original = PreviewTransform()
        val anchor = original.apply(base).toScene(from)
        val next = original.navigate(base, 2.0, from, to)
        assertEquals(2.0, next.zoom, 0.0)
        val actual = next.apply(base).toScreen(anchor)
        assertEquals(to.x, actual.x, 1e-9)
        assertEquals(to.y, actual.y, 1e-9)
    }

    @Test fun transformedViewportRoundTripsSceneCoordinatesAtAllZoomLevels() {
        for (zoom in listOf(.5, 1.0, 1.25, 2.0, 4.0)) {
            val viewport = PreviewTransform(zoom, .1, -.1).apply(base)
            for (p in listOf(Point(0.0, 0.0), Point(.37, .82), Point(1.0, 1.0))) {
                val actual = viewport.toScene(viewport.toScreen(p))
                assertEquals(p.x, actual.x, 1e-9)
                assertEquals(p.y, actual.y, 1e-9)
            }
        }
    }

    @Test fun zoomAndPanBoundsStayFiniteAndFitAlwaysResets() {
        val center = Point(540.0, 450.0)
        val max = PreviewTransform().navigate(base, 1000.0, center, center)
        assertEquals(4.0, max.zoom, 0.0)
        assertEquals(.5, max.navigate(base, .0001, center, center).zoom, 0.0)
        val far = max.navigate(base, 1.0, center, Point(1e9, -1e9))
        assertEquals(2.0, far.panX, 0.0)
        assertEquals(-2.0, far.panY, 0.0)
        assertEquals(base, PreviewTransform().apply(base))
    }

    @Test(expected = IllegalArgumentException::class) fun nonFiniteTransformIsRejected() {
        PreviewTransform(panX = Double.NaN)
    }

    @Test fun historyHasCorrectUndoRedoOrderAndIgnoresNoOps() {
        val history = EditHistory<String>()
        history.record("a", "a")
        assertFalse(history.canUndo)
        assertNull(history.undo())
        history.record("a", "b")
        history.record("b", "c")
        assertEquals("b", history.undo())
        assertEquals("a", history.undo())
        assertFalse(history.canUndo)
        assertEquals("b", history.redo())
        assertEquals("c", history.redo())
        assertNull(history.redo())
    }

    @Test fun newEditAfterUndoDiscardsOnlyTheRedoBranch() {
        val history = EditHistory<Int>()
        history.record(0, 1)
        history.record(1, 2)
        assertEquals(1, history.undo())
        history.record(1, 3)
        assertFalse(history.canRedo)
        assertEquals(1, history.undo())
        assertEquals(0, history.undo())
    }
}
