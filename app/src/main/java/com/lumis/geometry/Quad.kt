package com.lumis.geometry

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

data class Point(val x: Double, val y: Double)

data class Quad(val a: Point, val b: Point, val c: Point, val d: Point) {
    val points: List<Point> get() = listOf(a, b, c, d)

    init {
        require(isValid(listOf(a, b, c, d))) { "A surface must be a non-degenerate clockwise convex quad." }
    }

    fun moveCorner(index: Int, point: Point): Quad? {
        require(index in 0..3)
        if (!point.x.isFinite() || !point.y.isFinite()) return null
        val moved = points.toMutableList()
        val clamped = Point(point.x.coerceIn(0.0, 1.0), point.y.coerceIn(0.0, 1.0))
        if (hypot(clamped.x - moved[index].x, clamped.y - moved[index].y) < 1e-9) return this
        moved[index] = clamped
        return if (isValid(moved)) fromPoints(moved) else null
    }

    fun toCoordinates(): DoubleArray = points.flatMap { listOf(it.x, it.y) }.toDoubleArray()

    companion object {
        val DEFAULT = Quad(Point(.12, .12), Point(.88, .12), Point(.88, .88), Point(.12, .88))

        fun fromCoordinates(values: DoubleArray): Quad {
            require(values.size == 8)
            return fromPoints(values.toList().chunked(2).map { Point(it[0], it[1]) })
        }

        private fun fromPoints(p: List<Point>) = Quad(p[0], p[1], p[2], p[3])

        private fun isValid(p: List<Point>): Boolean {
            if (p.any { !it.x.isFinite() || !it.y.isFinite() }) return false
            for (i in 0..3) {
                val a = p[i]
                val b = p[(i + 1) % 4]
                val c = p[(i + 2) % 4]
                if (hypot(a.x - b.x, a.y - b.y) < .025) return false
                val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
                if (cross < .0005) return false
            }
            return true
        }
    }
}

/** Unit source square to normalized output, in column-major OpenGL layout. */
class Homography(quad: Quad) {
    val matrix: FloatArray
    private val values: DoubleArray

    init {
        val (a, b, c, d) = quad.points
        val dx1 = b.x - c.x
        val dx2 = d.x - c.x
        val dx3 = a.x - b.x + c.x - d.x
        val dy1 = b.y - c.y
        val dy2 = d.y - c.y
        val dy3 = a.y - b.y + c.y - d.y
        val determinant = dx1 * dy2 - dx2 * dy1
        require(abs(determinant) > 1e-10)
        val g = (dx3 * dy2 - dx2 * dy3) / determinant
        val h = (dx1 * dy3 - dx3 * dy1) / determinant
        values = doubleArrayOf(
            b.x - a.x + g * b.x, b.y - a.y + g * b.y, g,
            d.x - a.x + h * d.x, d.y - a.y + h * d.y, h,
            a.x, a.y, 1.0,
        )
        matrix = values.map { it.toFloat() }.toFloatArray()
    }

    fun map(source: Point): Point {
        val w = values[2] * source.x + values[5] * source.y + values[8]
        return Point(
            (values[0] * source.x + values[3] * source.y + values[6]) / w,
            (values[1] * source.x + values[4] * source.y + values[7]) / w,
        )
    }
}

/** The fixed logical projector frame. Scene points are normalized to this frame. */
object OutputCanvas {
    const val WIDTH = 1920
    const val HEIGHT = 1080

    fun toPixels(point: Point) = Point(point.x * WIDTH, point.y * HEIGHT)
    fun fromPixels(point: Point) = Point(point.x / WIDTH, point.y / HEIGHT)
}

/** Where the logical output canvas appears on a physical surface, in surface pixels. */
data class Viewport(val left: Double, val top: Double, val width: Double, val height: Double) {
    val scale: Double get() = width / OutputCanvas.WIDTH

    fun toScreen(point: Point) = Point(left + point.x * width, top + point.y * height)
    fun toScene(point: Point) = Point((point.x - left) / width, (point.y - top) / height)

    companion object {
        fun fit(width: Int, height: Int, inset: Double): Viewport {
            val availableWidth = (width - inset * 2).coerceAtLeast(1.0)
            val availableHeight = (height - inset * 2).coerceAtLeast(1.0)
            val w = min(availableWidth, availableHeight * OutputCanvas.WIDTH / OutputCanvas.HEIGHT)
            val h = w * OutputCanvas.HEIGHT / OutputCanvas.WIDTH
            return Viewport((width - w) / 2, (height - h) / 2, w, h)
        }
    }
}
