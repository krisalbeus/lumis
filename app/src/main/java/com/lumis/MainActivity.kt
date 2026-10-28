package com.lumis

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lumis.editor.CornerOverlay
import com.lumis.geometry.OutputCanvas
import com.lumis.geometry.Point
import com.lumis.geometry.Quad
import com.lumis.media.SamplePlayer
import com.lumis.renderer.VideoRenderer

class MainActivity : Activity() {
    var quad = Quad.DEFAULT
        private set
    lateinit var renderer: VideoRenderer
        private set
    lateinit var player: SamplePlayer
        private set
    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: CornerOverlay
    private lateinit var playButton: Button
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var root: LinearLayout
    private lateinit var editModeButton: Button
    private lateinit var outputModeButton: Button
    private val editorChrome = mutableListOf<View>()
    var outputMode = false
        private set
    private var backCallback: Any? = null
    private var resumed = false
    private var rendererFailed = false
    private var showFps = false
    private val background = Color.rgb(9, 11, 16)
    private val surface = Color.rgb(20, 24, 33)
    private val cyan = Color.rgb(103, 232, 249)
    private val violet = Color.rgb(167, 139, 250)
    private val secondary = Color.rgb(156, 168, 186)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getDoubleArray("quad")?.let { quad = Quad.fromCoordinates(it) }
        showFps = savedInstanceState?.getBoolean("fps") ?: false
        player = SamplePlayer(this, ::playbackChanged) { showError(false) }
        player.restore(savedInstanceState?.getBoolean("playing") ?: true, savedInstanceState?.getInt("position") ?: 0)
        buildUi()
        setOutputMode(savedInstanceState?.getBoolean("output") ?: false)
    }

    private fun buildUi() {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
            setPadding(dp(20), 0, dp(20), 0)
            setOnApplyWindowInsetsListener { view, insets ->
                if (outputMode) {
                    view.setPadding(0, 0, 0, 0)
                } else if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    view.setPadding(dp(20) + bars.left, bars.top + dp(8), dp(20) + bars.right, bars.bottom + dp(12))
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(dp(20) + insets.systemWindowInsetLeft, insets.systemWindowInsetTop + dp(8),
                        dp(20) + insets.systemWindowInsetRight, insets.systemWindowInsetBottom + dp(12))
                }
                insets
            }
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_lumis)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(text("Lumis", 24, Color.WHITE, true), LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(modeSwitch())
        root.addView(header)
        val instructions = text(getString(R.string.instructions), 14, secondary).apply {
            setPadding(0, dp(if (landscape) 0 else 14), 0, dp(4))
        }
        root.addView(instructions)
        val caption = outputCaption()
        if (!landscape) root.addView(caption, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        val body = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val stage = FrameLayout(this)
        glView = GLSurfaceView(this).apply {
            id = R.id.video_canvas
            contentDescription = getString(R.string.canvas_description)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = false
        }
        renderer = VideoRenderer(
            CornerOverlay.INSET_DP * resources.displayMetrics.density,
            { glView.requestRender() },
            { videoSurface ->
                runOnUiThread {
                    if (resumed && renderer.currentSurface === videoSurface) {
                        rendererFailed = false
                        errorPanel.visibility = View.GONE
                        player.attach(videoSurface)
                    }
                }
            },
            { detail ->
                runOnUiThread {
                    Log.e("Lumis", "Renderer unavailable: $detail")
                    if (resumed) showError(true)
                }
            },
            { renderFps, videoFps ->
                runOnUiThread {
                    if (player.wantsToPlay) metrics.text = getString(
                        if (landscape) R.string.fps_compact else R.string.fps, renderFps, videoFps,
                    )
                }
            },
        )
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        renderer.updateQuad(quad)
        stage.addView(glView, FrameLayout.LayoutParams(-1, -1))
        overlay = CornerOverlay(this, { quad }, ::moveCorner) {
            Toast.makeText(this, R.string.invalid_quad, Toast.LENGTH_SHORT).show()
        }
        overlay.id = R.id.corner_overlay
        stage.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        body.addView(stage, if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))

        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val boundedHeight = if (!landscape && View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.AT_MOST) {
                    val limit = (resources.displayMetrics.heightPixels * .35).toInt()
                    View.MeasureSpec.makeMeasureSpec(minOf(View.MeasureSpec.getSize(heightMeasureSpec), limit), View.MeasureSpec.AT_MOST)
                } else heightMeasureSpec
                super.onMeasure(widthMeasureSpec, boundedHeight)
            }
        }.apply {
            isFillViewport = false
            addView(panel)
        }
        body.addView(scroll, if (landscape) LinearLayout.LayoutParams(dp(232), -1) else LinearLayout.LayoutParams(-1, -2))
        if (landscape) panel.addView(caption, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        editorChrome += listOf(header, instructions, caption, overlay, scroll)

        errorPanel = LinearLayout(this).apply {
            id = R.id.error_panel
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(48, 30, 36))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        errorMessage = text("", 14, Color.WHITE)
        errorPanel.addView(errorMessage)
        errorPanel.addView(button(R.string.retry, false).apply {
            id = R.id.retry_playback
            setOnClickListener { retry() }
        })
        panel.addView(errorPanel)

        val source = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val sourceHeader = LinearLayout(this).apply {
            orientation = if (resources.configuration.fontScale > 1.3f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sourceHeader.addView(text(getString(R.string.sample_label), 16, Color.WHITE, true),
            if (sourceHeader.orientation == LinearLayout.VERTICAL) LinearLayout.LayoutParams(-1, -2)
            else LinearLayout.LayoutParams(0, -2, 1f))
        status = text(getString(R.string.loading), 12, cyan).apply {
            id = R.id.playback_state
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        sourceHeader.addView(status)
        source.addView(sourceHeader)
        source.addView(text(getString(R.string.sample_detail), 12, secondary).apply { setPadding(0, dp(5), 0, 0) })
        panel.addView(source)

        val controls = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            isBaselineAligned = false
            setPadding(0, dp(16), 0, 0)
        }
        playButton = button(R.string.pause, true).apply {
            id = R.id.play_pause
            isEnabled = false
            setOnClickListener { player.toggle() }
        }
        controls.addView(playButton,
            if (landscape) LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            else LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        controls.addView(button(R.string.reset, false).apply {
            id = R.id.reset_corners
            setOnClickListener {
                quad = Quad.DEFAULT
                renderer.updateQuad(quad)
                this@MainActivity.overlay.update()
            }
        }, if (landscape) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(controls)

        val diagnosticRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        @Suppress("UseSwitchCompatOrMaterialCode")
        val toggle = Switch(this).apply {
            id = R.id.fps_toggle
            text = getString(R.string.show_fps)
            setTextColor(secondary)
            textSize = 12f
            minHeight = dp(56)
            isChecked = showFps
            setOnCheckedChangeListener { _, checked ->
                showFps = checked
                metrics.visibility = if (checked) View.VISIBLE else View.INVISIBLE
            }
        }
        diagnosticRow.addView(toggle)
        metrics = text("", 11, cyan).apply {
            id = R.id.fps_text
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            visibility = if (showFps) View.VISIBLE else View.INVISIBLE
        }
        diagnosticRow.addView(metrics, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(diagnosticRow)
        if (!landscape) panel.addView(text(getString(R.string.offline_note), 11, secondary).apply { gravity = Gravity.CENTER })
        setContentView(root)
    }

    private fun modeSwitch() = LinearLayout(this).apply {
        background = GradientDrawable().apply {
            setColor(Color.rgb(13, 16, 23))
            setStroke(dp(1), Color.rgb(44, 50, 64))
            cornerRadius = dp(24).toFloat()
        }
        setPadding(dp(3), dp(3), dp(3), dp(3))
        isBaselineAligned = false
        editModeButton = modeButton(R.string.mode_edit, R.id.mode_edit) { setOutputMode(false) }
        outputModeButton = modeButton(R.string.mode_output, R.id.mode_output) { setOutputMode(true) }
        addView(editModeButton)
        addView(outputModeButton)
    }

    private fun modeButton(label: Int, viewId: Int, action: () -> Unit) = Button(this).apply {
        id = viewId
        setText(label)
        isAllCaps = false
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minWidth = dp(64)
        minimumWidth = dp(64)
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(14), 0, dp(14), 0)
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun outputCaption() = text(getString(R.string.output_size, OutputCanvas.WIDTH, OutputCanvas.HEIGHT), 11, secondary).apply {
        id = R.id.output_caption
        typeface = Typeface.MONOSPACE
        background = GradientDrawable().apply {
            setColor(Color.rgb(13, 16, 23))
            setStroke(dp(1), Color.rgb(44, 50, 64))
            cornerRadius = dp(8).toFloat()
        }
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    /** Edit and Output share the same GL view, scene, video surface, and player. */
    fun setOutputMode(enabled: Boolean) {
        outputMode = enabled
        editorChrome.forEach { it.visibility = if (enabled) View.GONE else View.VISIBLE }
        root.setBackgroundColor(if (enabled) Color.BLACK else background)
        window.decorView.setBackgroundColor(if (enabled) Color.BLACK else background)
        renderer.setOutputMode(enabled)
        glView.importantForAccessibility =
            if (enabled) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        glView.contentDescription = getString(if (enabled) R.string.output_description else R.string.canvas_description)
        styleMode(editModeButton, !enabled)
        styleMode(outputModeButton, enabled)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = when {
                    !enabled -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    android.os.Build.VERSION.SDK_INT >= 30 -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        applySystemBars()
        updateBackHandling()
        updateKeepScreenOn()
        root.requestApplyInsets()
    }

    private fun styleMode(button: Button, selected: Boolean) {
        button.isSelected = selected
        button.background = GradientDrawable().apply {
            setColor(if (selected) violet else Color.TRANSPARENT)
            cornerRadius = dp(22).toFloat()
        }
        button.setTextColor(if (selected) Color.rgb(29, 20, 55) else secondary)
    }

    private fun applySystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController ?: return
            if (outputMode) {
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(android.view.WindowInsets.Type.systemBars())
            } else {
                controller.show(android.view.WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (outputMode) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else 0
        }
    }

    private fun updateBackHandling() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val dispatcher = onBackInvokedDispatcher
        if (outputMode && backCallback == null) {
            val callback = android.window.OnBackInvokedCallback { setOutputMode(false) }
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (!outputMode) {
            (backCallback as? android.window.OnBackInvokedCallback)?.let { dispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Android 13+ uses OnBackInvokedCallback; older versions still deliver Back as a key.
        if (android.os.Build.VERSION.SDK_INT < 33 && outputMode && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) setOutputMode(false)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && outputMode) applySystemBars()
    }

    private fun updateKeepScreenOn() {
        if (outputMode || (player.wantsToPlay && playButton.isEnabled)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun moveCorner(index: Int, point: Point): Boolean {
        val moved = quad.moveCorner(index, point) ?: return false
        if (moved === quad) return true
        quad = moved
        renderer.updateQuad(moved)
        overlay.update()
        return true
    }

    private fun playbackChanged(ready: Boolean, playing: Boolean) {
        playButton.isEnabled = ready
        playButton.setText(if (playing) R.string.pause else R.string.play)
        status.setText(if (!ready) R.string.loading else if (playing) R.string.playing else R.string.paused)
        if (playing || outputMode) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!playing) metrics.setText(R.string.paused)
    }

    private fun showError(gpu: Boolean) {
        // Recovery UI belongs to the editor, never to clean output.
        if (outputMode) setOutputMode(false)
        rendererFailed = gpu
        player.release()
        playButton.isEnabled = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        errorMessage.setText(if (gpu) R.string.renderer_error else R.string.video_error)
        status.setText(R.string.error_title)
        errorPanel.visibility = View.VISIBLE
    }

    private fun retry() {
        if (rendererFailed) {
            recreate()
        } else {
            renderer.currentSurface?.let {
                errorPanel.visibility = View.GONE
                player.attach(it)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        glView.onResume()
    }

    override fun onPause() {
        resumed = false
        player.release()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        glView.queueEvent { renderer.release() }
        glView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putDoubleArray("quad", quad.toCoordinates())
        outState.putBoolean("playing", player.wantsToPlay)
        outState.putInt("position", player.position())
        outState.putBoolean("fps", showFps)
        outState.putBoolean("output", outputMode)
        super.onSaveInstanceState(outState)
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(label: Int, primary: Boolean) = Button(this).apply {
        setText(label)
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = rounded(if (primary) violet else surface)
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.rgb(50, 48, 67), if (primary) violet else surface),
        )
        setTextColor(if (primary) Color.rgb(29, 20, 55) else Color.WHITE)
        setPadding(dp(10), 0, dp(10), 0)
        minHeight = dp(48)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(16).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
