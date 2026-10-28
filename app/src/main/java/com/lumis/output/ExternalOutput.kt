package com.lumis.output

import android.app.Activity
import android.app.Presentation
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager

class ExternalOutput(
    private val activity: Activity,
    private val target: (Surface?) -> Unit,
    private val changed: () -> Unit,
) : DisplayManager.DisplayListener {
    private val manager = activity.getSystemService(DisplayManager::class.java)
    private var listening = false
    private var closing = false
    private var presentation: CleanPresentation? = null
    var selectedId: Int? = null
        private set
    var enabled = false
        private set
    var active = false
        private set
    var error: String? = null
        private set
    @Suppress("DEPRECATION")
    val displays: List<Display> get() = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        .filter { it.displayId != activity.windowManager.defaultDisplay.displayId && it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
    val selected: Display? get() = displays.find { it.displayId == selectedId }
    val surfaceView: SurfaceView? get() = presentation?.view

    fun resume() {
        if (listening) return
        listening = true
        manager.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        refresh()
        if (enabled && error == null) presentation?.view?.visibility = View.VISIBLE
    }

    fun pause() {
        if (listening) manager.unregisterDisplayListener(this)
        listening = false
        target(null)
        active = false
        presentation?.view?.visibility = View.INVISIBLE
    }

    fun destroy() {
        if (listening) manager.unregisterDisplayListener(this)
        listening = false
        close()
    }

    fun select(id: Int) {
        close()
        enabled = false
        if (displays.none { it.displayId == id }) {
            error = activity.getString(com.lumis.R.string.external_disconnected)
            refresh()
            return
        }
        selectedId = id
        error = null
        changed()
    }

    fun enable(value: Boolean) {
        close()
        error = null
        enabled = value
        refresh()
    }

    fun rendererState(surface: Surface, detail: String?) {
        if (presentation?.view?.holder?.surface !== surface || !enabled) return
        active = detail == null
        error = detail
        if (detail != null) {
            // Cover the last frame without dismissing into Android screen mirroring.
            presentation?.view?.visibility = View.INVISIBLE
            target(null)
        }
        changed()
    }

    private fun refresh() {
        if (selected == null) {
            close()
            if (enabled) {
                enabled = false
                error = activity.getString(com.lumis.R.string.external_disconnected)
            }
            selectedId = displays.firstOrNull()?.displayId
        }
        val display = selected
        if (enabled && listening && display != null && presentation == null) {
            val next = CleanPresentation(display)
            presentation = next
            next.setOnDismissListener {
                if (!closing && presentation === next) {
                    target(null)
                    presentation = null
                    active = false
                    if (enabled && selected != null && listening) {
                        Handler(Looper.getMainLooper()).post { if (listening) refresh() }
                    } else {
                        enabled = false
                        error = activity.getString(com.lumis.R.string.external_disconnected)
                    }
                    changed()
                }
            }
            try {
                next.show()
            } catch (failure: WindowManager.InvalidDisplayException) {
                Log.e("LumisOutput", "Display disappeared while opening output", failure)
                close()
                error = activity.getString(com.lumis.R.string.external_disconnected)
                enabled = false
            }
        }
        changed()
    }

    private fun close() {
        target(null)
        active = false
        closing = true
        presentation?.dismiss()
        presentation = null
        closing = false
    }

    override fun onDisplayAdded(displayId: Int) = refresh()
    override fun onDisplayRemoved(displayId: Int) = refresh()
    override fun onDisplayChanged(displayId: Int) {
        // Presentation handles display-size changes; its SurfaceHolder requests a fresh frame.
        refresh()
    }

    @Suppress("DEPRECATION")
    private inner class CleanPresentation(display: Display) : Presentation(activity, display) {
        lateinit var view: SurfaceView
            private set

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
                addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    attributes = attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
                }
            }
            view = SurfaceView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        if (presentation === this@CleanPresentation && enabled && error == null) target(holder.surface)
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        if (presentation === this@CleanPresentation) target(null)
                    }
                })
            }
            setContentView(view)
        }
    }
}
