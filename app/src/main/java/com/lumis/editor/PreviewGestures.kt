package com.lumis.editor

import android.view.MotionEvent
import com.lumis.geometry.Point
import kotlin.math.hypot

class PreviewGestures(private val navigate: (Double, Point, Point) -> Unit) {
    private var first = -1
    private var second = -1
    private var midpoint: Point? = null
    private var distance = 0.0

    fun touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> reset()
            MotionEvent.ACTION_POINTER_DOWN -> if (first < 0 && event.pointerCount >= 2) {
                first = event.getPointerId(0)
                second = event.getPointerId(1)
                sample(event, false)
            }
            MotionEvent.ACTION_MOVE -> sample(event, true)
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == first ||
                event.getPointerId(event.actionIndex) == second) reset()
        }
        return true
    }

    private fun sample(event: MotionEvent, moving: Boolean) {
        val a = event.findPointerIndex(first)
        val b = event.findPointerIndex(second)
        if (a < 0 || b < 0) return
        val center = Point((event.getX(a) + event.getX(b)) / 2.0, (event.getY(a) + event.getY(b)) / 2.0)
        val span = hypot((event.getX(a) - event.getX(b)).toDouble(), (event.getY(a) - event.getY(b)).toDouble())
        val previous = midpoint
        if (moving && previous != null && distance > 1 && span > 1) navigate(span / distance, previous, center)
        midpoint = center
        distance = span
    }

    private fun reset() {
        first = -1
        second = -1
        midpoint = null
        distance = 0.0
    }
}
