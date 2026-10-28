package com.lumis.scene

import com.lumis.geometry.Point
import org.junit.Assert.*
import org.junit.Test

class SceneTest {
    @Test fun additionHasStableIdsAndNoFourSurfaceLimit() {
        var scene = Scene()
        repeat(24) { scene = scene.add() }
        assertEquals(25, scene.surfaces.size)
        assertEquals(25L, scene.selectedId)
        assertEquals(26L, scene.nextId)
        assertEquals(25, scene.surfaces.map { it.id }.distinct().size)
    }

    @Test fun selectionDoesNotReorderOrMutateLayers() {
        val original = Scene().add().add()
        val selected = original.select(1)
        assertEquals(original.surfaces, selected.surfaces)
        assertEquals(1L, selected.selectedId)
    }

    @Test fun duplicateCopiesAllPropertiesWithIndependentGeometryAndIdentity() {
        val original = Scene().let { it.update(it.selected!!.copy(visible = false, opacity = .35f)) }
        val duplicate = original.duplicate()
        assertEquals(original.selected!!.copy(id = 2), duplicate.selected)
        val edited = duplicate.update(duplicate.selected!!.copy(quad = duplicate.selected!!.quad.moveCorner(0, Point(.2, .2))!!))
        assertEquals(original.selected, edited.surfaces.first())
        assertNotEquals(edited.surfaces.first().quad, edited.selected!!.quad)
    }

    @Test fun reorderVisibilityAndOpacityDoNotAffectOtherSurfaces() {
        val original = Scene().add().add()
        val changed = original.update(original.selected!!.copy(visible = false, opacity = 0f)).move(3, false)
        assertEquals(listOf(1L, 3L, 2L), changed.surfaces.map { it.id })
        assertEquals(original.surfaces.take(2), changed.surfaces.filterNot { it.id == 3L })
        assertSame(changed, changed.move(1, false))
        assertSame(changed, changed.move(2, true))
    }

    @Test fun deletionRetainsNeighborSelectionAndNeverReusesIds() {
        val scene = Scene().add().add().delete(3).delete(2).delete(1)
        assertTrue(scene.surfaces.isEmpty())
        assertNull(scene.selected)
        assertEquals(4L, scene.add().selectedId)
        assertEquals(1L, Scene().add().select(1).delete(2).selectedId)
    }

    @Test fun hitTestingSelectsFrontmostVisibleNonTransparentQuad() {
        val scene = Scene().add()
        assertEquals(2L, scene.hitTest(Point(.5, .5)))
        assertEquals(1L, scene.update(scene.selected!!.copy(visible = false)).hitTest(Point(.5, .5)))
        assertEquals(1L, scene.update(scene.selected!!.copy(opacity = 0f)).hitTest(Point(.5, .5)))
        assertNull(scene.hitTest(Point(.01, .01)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidOpacityIsRejected() { SceneSurface(1, opacity = Float.NaN) }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSelectionIsRejected() { Scene(selectedId = 99L) }
}
