package com.lumis.scene

import com.lumis.geometry.Point
import com.lumis.geometry.Quad
import com.lumis.media.MediaSource

data class SceneSurface(
    val id: Long,
    val source: MediaSource = MediaSource.BundledSample,
    val quad: Quad = Quad.DEFAULT,
    val visible: Boolean = true,
    val opacity: Float = 1f,
) {
    init {
        require(id > 0)
        require(opacity.isFinite() && opacity in 0f..1f)
    }

    fun contains(point: Point): Boolean = quad.points.indices.all { i ->
        val a = quad.points[i]
        val b = quad.points[(i + 1) % 4]
        (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x) >= 0
    }
}

/** Back-to-front layer order; selection never changes drawing order. */
data class Scene(
    val surfaces: List<SceneSurface> = listOf(SceneSurface(1)),
    val selectedId: Long? = surfaces.lastOrNull()?.id,
    val nextId: Long = (surfaces.maxOfOrNull { it.id } ?: 0) + 1,
) {
    init {
        require(surfaces.map { it.id }.distinct().size == surfaces.size)
        require(selectedId == null || surfaces.any { it.id == selectedId })
        require(surfaces.isEmpty() == (selectedId == null))
        require(nextId > (surfaces.maxOfOrNull { it.id } ?: 0))
    }

    val selected: SceneSurface? get() = surfaces.find { it.id == selectedId }

    fun select(id: Long): Scene {
        require(surfaces.any { it.id == id })
        return copy(selectedId = id)
    }

    fun update(surface: SceneSurface): Scene {
        require(surfaces.any { it.id == surface.id })
        return copy(surfaces = surfaces.map { if (it.id == surface.id) surface else it })
    }

    fun add(source: MediaSource = MediaSource.BundledSample): Scene =
        copy(surfaces = surfaces + SceneSurface(nextId, source), selectedId = nextId, nextId = nextId + 1)

    fun duplicate(): Scene {
        val original = requireNotNull(selected)
        val index = surfaces.indexOf(original) + 1
        val layers = surfaces.toMutableList().apply { add(index, original.copy(id = nextId)) }
        return copy(surfaces = layers, selectedId = nextId, nextId = nextId + 1)
    }

    fun delete(id: Long): Scene {
        require(surfaces.any { it.id == id })
        val index = surfaces.indexOfFirst { it.id == id }
        val remaining = surfaces.filterNot { it.id == id }
        val selection = if (selectedId != id) selectedId else remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))?.id
        return copy(surfaces = remaining, selectedId = selection)
    }

    fun move(id: Long, forward: Boolean): Scene {
        val index = surfaces.indexOfFirst { it.id == id }
        require(index >= 0)
        val target = (index + if (forward) 1 else -1).coerceIn(0, surfaces.lastIndex)
        if (index == target) return this
        val layers = surfaces.toMutableList()
        layers.add(target, layers.removeAt(index))
        return copy(surfaces = layers)
    }

    fun hitTest(point: Point): Long? =
        surfaces.asReversed().firstOrNull { it.visible && it.opacity > 0f && it.contains(point) }?.id
}
