package com.lumis.renderer

import android.view.Surface
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLSurface

/** GL-thread-only window target sharing the editor's context, textures, and decoder frames. */
internal class ExternalEglOutput(private val config: EGLConfig) {
    class EditorContextLost(message: String) : IllegalStateException(message)
    private val egl = EGLContext.getEGL() as EGL10
    private var window: EGLSurface? = null
    private var native: Surface? = null
    private var display = EGL10.EGL_NO_DISPLAY

    fun draw(surface: Surface, render: (Int, Int) -> Unit) {
        val currentDisplay = egl.eglGetCurrentDisplay()
        val context = egl.eglGetCurrentContext()
        val draw = egl.eglGetCurrentSurface(EGL10.EGL_DRAW)
        val read = egl.eglGetCurrentSurface(EGL10.EGL_READ)
        if (native !== surface) {
            release()
            display = currentDisplay
            window = egl.eglCreateWindowSurface(display, config, surface, null).also {
                check(it != null && it != EGL10.EGL_NO_SURFACE) { "Cannot create external EGL window: 0x${egl.eglGetError().toString(16)}" }
            }
            native = surface
        }
        val target = requireNotNull(window)
        try {
            check(egl.eglMakeCurrent(display, target, target, context)) { "Cannot bind external display: 0x${egl.eglGetError().toString(16)}" }
            val width = IntArray(1)
            val height = IntArray(1)
            check(egl.eglQuerySurface(display, target, EGL10.EGL_WIDTH, width) &&
                egl.eglQuerySurface(display, target, EGL10.EGL_HEIGHT, height)) { "Cannot query external display size" }
            check(width[0] > 0 && height[0] > 0) { "External display has no drawable area" }
            render(width[0], height[0])
            check(egl.eglSwapBuffers(display, target)) { "Cannot present external output: 0x${egl.eglGetError().toString(16)}" }
        } finally {
            if (!egl.eglMakeCurrent(currentDisplay, draw, read, context)) {
                throw EditorContextLost("Cannot restore editor EGL target: 0x${egl.eglGetError().toString(16)}")
            }
        }
    }

    fun release() {
        window?.let { check(egl.eglDestroySurface(display, it)) { "Cannot release external EGL window" } }
        window = null
        native = null
    }
}
