package com.lumis.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import com.lumis.R
import com.lumis.geometry.Point
import com.lumis.geometry.Quad
import com.lumis.geometry.Viewport
import kotlin.math.hypot
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor") // Created programmatically with scene callbacks, not inflated.
class CornerOverlay(
    context: Context,
    private val scene: () -> Quad,
    private val move: (Int, Point) -> Boolean,
    private val transform: () -> PreviewTransform,
    private val beginEdit: () -> Unit,
    private val endEdit: () -> Unit,
    private val onInvalid: () -> Unit,
) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var activeCorner = -1
    private var pointerId = -1
    private var warned = false
    private var grabOffset = Point(0.0, 0.0)
    var viewport = Viewport.fit(1, 1, INSET_DP * density)
        private set
    private val handles = listOf(R.id.corner_a, R.id.corner_b, R.id.corner_c, R.id.corner_d)
        .mapIndexed { index, viewId ->
            Handle(context, index).apply { id = viewId; addView(this) }
        }

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun update() {
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (56 * density).roundToInt()
        handles.forEach { it.measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)) }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        viewport = transform().apply(Viewport.fit(width, height, INSET_DP * density))
        scene().points.forEachIndexed { index, point ->
            val screen = viewport.toScreen(point)
            val handle = handles[index]
            val x = screen.x.roundToInt() - handle.measuredWidth / 2
            val y = screen.y.roundToInt() - handle.measuredHeight / 2
            handle.layout(x, y, x + handle.measuredWidth, y + handle.measuredHeight)
            handle.visibility = if (x + handle.measuredWidth > 0 && y + handle.measuredHeight > 0 &&
                x < width && y < height) View.VISIBLE else View.INVISIBLE
        }
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = Color.rgb(35, 42, 55)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        canvas.drawRoundRect(
            viewport.left.toFloat(), viewport.top.toFloat(),
            (viewport.left + viewport.width).toFloat(), (viewport.top + viewport.height).toFloat(),
            4 * density, 4 * density, paint,
        )
        path.reset()
        scene().points.forEachIndexed { index, point ->
            val p = viewport.toScreen(point)
            if (index == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat())
        }
        path.close()
        paint.color = Color.rgb(103, 232, 249)
        paint.strokeWidth = 1.5f * density
        canvas.drawPath(path, paint)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return activeCorner >= 0
        activeCorner = scene().points.indices.minByOrNull {
            val p = viewport.toScreen(scene().points[it])
            hypot(event.x - p.x, event.y - p.y)
        }?.takeIf {
            val p = viewport.toScreen(scene().points[it])
            hypot(event.x - p.x, event.y - p.y) <= 28 * density
        } ?: -1
        if (activeCorner < 0) return false
        beginEdit()
        pointerId = event.getPointerId(0)
        val p = viewport.toScreen(scene().points[activeCorner])
        grabOffset = Point(event.x - p.x, event.y - p.y)
        warned = false
        parent.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (activeCorner < 0) return false
        val index = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (index >= 0) {
                val next = viewport.toScene(Point(event.getX(index) - grabOffset.x, event.getY(index) - grabOffset.y))
                if (!move(activeCorner, next) && !warned) {
                    warned = true
                    onInvalid()
                }
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                finishGesture()
            }
            MotionEvent.ACTION_CANCEL -> finishGesture()
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) finishGesture()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun finishGesture() {
        if (activeCorner >= 0) endEdit()
        activeCorner = -1
        pointerId = -1
        parent.requestDisallowInterceptTouchEvent(false)
    }

    private inner class Handle(context: Context, private val index: Int) : View(context) {
        private val letter = ('A' + index).toString()

        init {
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(R.string.corner_description, letter)
        }

        override fun onDraw(canvas: Canvas) {
            val x = width / 2f
            val y = height / 2f
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(9, 11, 16)
            canvas.drawCircle(x, y, 15 * density, paint)
            paint.style = Paint.Style.STROKE
            paint.color = Color.rgb(103, 232, 249)
            paint.strokeWidth = 2.5f * density
            canvas.drawCircle(x, y, 15 * density, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = 12 * density
            paint.isFakeBoldText = true
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(letter, x, y - (paint.ascent() + paint.descent()) / 2, paint)
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = Button::class.java.name
            info.isScrollable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            for ((actionId, label) in actions) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(actionId, context.getString(label)))
            }
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            val delta = when (action) {
                R.id.move_left, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> Point(-.01, 0.0)
                R.id.move_right, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> Point(.01, 0.0)
                R.id.move_up -> Point(0.0, -.01)
                R.id.move_down -> Point(0.0, .01)
                else -> return super.performAccessibilityAction(action, arguments)
            }
            val p = scene().points[index]
            beginEdit()
            if (!move(index, Point(p.x + delta.x, p.y + delta.y))) onInvalid()
            endEdit()
            return true
        }
    }

    companion object {
        /** Shared with the renderer so handles and GPU pixels use identical preview framing. */
        const val INSET_DP = 28.0
        private val actions = listOf(
            R.id.move_left to R.string.move_left, R.id.move_right to R.string.move_right,
            R.id.move_up to R.string.move_up, R.id.move_down to R.string.move_down,
        )
    }
}
