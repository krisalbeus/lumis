package com.lumis.editor

import com.lumis.geometry.Point
import com.lumis.geometry.Viewport

/** Editor camera only: never alters scene coordinates or clean Output. */
data class PreviewTransform(val zoom: Double = 1.0, val panX: Double = 0.0, val panY: Double = 0.0) {
    init {
        require(zoom.isFinite() && zoom in MIN_ZOOM..MAX_ZOOM)
        require(panX.isFinite() && panY.isFinite())
    }

    fun apply(base: Viewport) = Viewport(
        base.left + base.width * ((1 - zoom) / 2 + panX),
        base.top + base.height * ((1 - zoom) / 2 + panY),
        base.width * zoom, base.height * zoom,
    )

    fun navigate(base: Viewport, factor: Double, from: Point, to: Point): PreviewTransform {
        require(factor.isFinite() && factor > 0)
        val anchor = apply(base).toScene(from)
        val nextZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        return PreviewTransform(nextZoom,
            ((to.x - base.left) / base.width - anchor.x * nextZoom - (1 - nextZoom) / 2).coerceIn(-nextZoom / 2, nextZoom / 2),
            ((to.y - base.top) / base.height - anchor.y * nextZoom - (1 - nextZoom) / 2).coerceIn(-nextZoom / 2, nextZoom / 2))
    }

    companion object {
        const val MIN_ZOOM = .5
        const val MAX_ZOOM = 4.0
    }
}
