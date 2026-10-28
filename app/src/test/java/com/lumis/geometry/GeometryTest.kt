package com.lumis.geometry

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class GeometryTest {
    private fun assertPoint(expected: Point, actual: Point) {
        assertEquals(expected.x, actual.x, 1e-8)
        assertEquals(expected.y, actual.y, 1e-8)
    }

    @Test fun identityMapsEveryPointUnchanged() {
        val h = Homography(Quad(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0)))
        for (x in 0..10) for (y in 0..10) {
            val point = Point(x / 10.0, y / 10.0)
            assertPoint(point, h.map(point))
        }
    }

    @Test fun allFourCornersMatchForRandomConvexQuads() {
        val random = Random(42)
        repeat(200) {
            val q = Quad(
                Point(random.nextDouble(0.0, .3), random.nextDouble(0.0, .3)),
                Point(random.nextDouble(.7, 1.0), random.nextDouble(0.0, .3)),
                Point(random.nextDouble(.7, 1.0), random.nextDouble(.7, 1.0)),
                Point(random.nextDouble(0.0, .3), random.nextDouble(.7, 1.0)),
            )
            val h = Homography(q)
            listOf(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0))
                .forEachIndexed { index, p -> assertPoint(q.points[index], h.map(p)) }
            assertTrue(h.matrix.all { it.isFinite() })
        }
    }

    @Test fun trapezoidUsesPerspectiveRatherThanAffineMidpoints() {
        val q = Quad(Point(.3, .1), Point(.7, .1), Point(.9, .9), Point(.1, .9))
        val h = Homography(q)
        assertPoint(Point(.5, .3666666666666667), h.map(Point(.5, .5)))
        assertTrue(abs(h.map(Point(.5, .5)).y - .5) > .1)
    }

    @Test fun projectiveGridLinesRemainStraightAcrossTheTriangleDiagonal() {
        val h = Homography(Quad(Point(.2, .15), Point(.85, .3), Point(.95, .9), Point(.05, .75)))
        for (column in 1..9) {
            val x = column / 10.0
            val a = h.map(Point(x, 0.0))
            val b = h.map(Point(x, 1.0))
            for (row in 0..10) {
                val p = h.map(Point(x, row / 10.0))
                assertEquals(0.0, (p.x - a.x) * (b.y - a.y) - (p.y - a.y) * (b.x - a.x), 1e-8)
            }
        }
    }

    @Test fun gpuClipCoordinatesUseProjectiveW() {
        val quad = Quad(Point(.3, .1), Point(.7, .1), Point(.9, .9), Point(.1, .9))
        val h = Homography(quad)
        for (x in 0..10) for (y in 0..10) {
            val p = Point(x / 10.0, y / 10.0)
            val m = h.matrix
            val w = m[2] * p.x + m[5] * p.y + m[8]
            assertTrue(w > 0)
            val clipX = 2 * (m[0] * p.x + m[3] * p.y + m[6]) - w
            val clipY = w - 2 * (m[1] * p.x + m[4] * p.y + m[7])
            val mapped = h.map(p)
            assertEquals(mapped.x, (clipX / w + 1) / 2, 1e-6)
            assertEquals(mapped.y, (1 - clipY / w) / 2, 1e-6)
        }
    }

    @Test fun viewportRoundTripsWithPortraitLandscapeAndInsets() {
        for ((w, h) in listOf(1080 to 2400, 2400 to 1080, 360 to 240, 1 to 1)) {
            val viewport = Viewport.fit(w, h, 28.0)
            assertEquals(16.0 / 9, viewport.width / viewport.height, 1e-8)
            for (p in listOf(Point(0.0, 0.0), Point(.5, .5), Point(1.0, 1.0), Point(-.1, 1.2))) {
                assertPoint(p, viewport.toScene(viewport.toScreen(p)))
            }
        }
    }

    @Test fun editingRejectsFoldedDegenerateAndNonFiniteQuads() {
        val q = Quad.DEFAULT
        assertNull(q.moveCorner(0, q.c))
        assertNull(q.moveCorner(0, q.b))
        assertNull(q.moveCorner(1, Point(.05, .9)))
        assertNull(q.moveCorner(2, Point(Double.NaN, .5)))
        assertNull(q.moveCorner(2, Point(Double.POSITIVE_INFINITY, .5)))
        assertEquals(Quad.DEFAULT, q)
    }

    @Test fun independentCornerEditingAndClamping() {
        for (i in 0..3) {
            val q = Quad.DEFAULT
            val p = q.points[i]
            val moved = q.moveCorner(i, Point(p.x + .02, p.y + .03))!!
            q.points.indices.filter { it != i }.forEach { assertEquals(q.points[it], moved.points[it]) }
        }
        assertEquals(Point(0.0, 0.0), Quad.DEFAULT.moveCorner(0, Point(-3.0, -2.0))!!.a)
    }

    @Test fun coordinatesSerializeLosslessly() {
        val q = Quad(Point(.21, .1), Point(.78, .3), Point(.99, .87), Point(.03, .76))
        assertEquals(q, Quad.fromCoordinates(q.toCoordinates()))
    }

    @Test fun stationaryTouchDoesNotAccumulateFloatingPointDrift() {
        assertSame(Quad.DEFAULT, Quad.DEFAULT.moveCorner(0, Point(.11999999999999998, .11999999999999997)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptCoordinatesAreRejected() { Quad.fromCoordinates(doubleArrayOf(.1, .2)) }

    @Test fun smallAndOffCanvasQuadsAreSupportedByMath() {
        val q = Quad(Point(-.1, -.1), Point(.1, -.1), Point(.1, .1), Point(-.1, .1))
        assertPoint(Point(0.0, 0.0), Homography(q).map(Point(.5, .5)))
        val small = Quad(Point(.1, .1), Point(.13, .1), Point(.13, .13), Point(.1, .13))
        assertTrue(Homography(small).matrix.all { it.isFinite() })
    }
}
