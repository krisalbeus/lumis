package com.lumis.editor

/** Snapshots contain immutable model state, never bitmaps, decoders, or views. */
class EditHistory<T> {
    private data class Change<T>(val before: T, val after: T)
    private val past = ArrayDeque<Change<T>>()
    private val future = ArrayDeque<Change<T>>()
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun record(before: T, after: T) {
        if (before == after) return
        past.addLast(Change(before, after))
        future.clear()
    }

    fun undo(): T? = past.removeLastOrNull()?.let { future.addLast(it); it.before }
    fun redo(): T? = future.removeLastOrNull()?.let { past.addLast(it); it.after }
}
