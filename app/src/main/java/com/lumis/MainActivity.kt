package com.lumis

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
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
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lumis.editor.CornerOverlay
import com.lumis.geometry.OutputCanvas
import com.lumis.geometry.Point
import com.lumis.geometry.Quad
import com.lumis.media.MediaFailure
import com.lumis.media.MediaSource
import com.lumis.media.SamplePlayer
import com.lumis.renderer.VideoRenderer
import com.lumis.scene.Scene
import com.lumis.scene.SceneSurface
import java.io.FileNotFoundException
import java.io.IOException

class MainActivity : Activity() {
    var scene = Scene()
        private set
    val quad: Quad get() = scene.selected?.quad ?: Quad.DEFAULT
    lateinit var renderer: VideoRenderer
        private set
    private class MediaSession(val player: SamplePlayer) {
        var ready = false
        var failure: MediaFailure? = null
        var missing: MediaSource? = null
    }
    private val sessions = mutableMapOf<Long, MediaSession>()
    val player: SamplePlayer get() = requireNotNull(sessions[scene.selectedId]).player
    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: CornerOverlay
    private lateinit var playButton: Button
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var sourceDetail: TextView
    private lateinit var sourceAction: Button
    private lateinit var errorAction: Button
    private lateinit var root: LinearLayout
    private lateinit var editModeButton: Button
    private lateinit var outputModeButton: Button
    private lateinit var layersButton: Button
    private lateinit var propertiesButton: Button
    private lateinit var emptyCanvas: TextView
    private val editorChrome = mutableListOf<View>()
    var outputMode = false
        private set
    private var backCallback: Any? = null
    private var resumed = false
    private var rendererFailed = false
    private var showFps = false
    private val source: MediaSource get() = scene.selected?.source ?: MediaSource.BundledSample
    private var pickerAddsSurface = false
    private var pickerTargetId: Long? = null
    private var compositionDialog: AlertDialog? = null
    private val background = Color.rgb(9, 11, 16)
    private val surface = Color.rgb(20, 24, 33)
    private val cyan = Color.rgb(103, 232, 249)
    private val violet = Color.rgb(167, 139, 250)
    private val secondary = Color.rgb(156, 168, 186)
    val currentSource: MediaSource get() = source

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showFps = savedInstanceState?.getBoolean("fps") ?: false
        savedInstanceState?.getBundle("scene")?.let { saved ->
            val layers = requireNotNull(saved.getLongArray("ids")).map { id ->
                val layer = requireNotNull(saved.getBundle(id.toString()))
                SceneSurface(id, readSource(layer), Quad.fromCoordinates(requireNotNull(layer.getDoubleArray("quad"))),
                    layer.getBoolean("visible"), layer.getFloat("opacity"))
            }
            scene = Scene(layers, saved.getLong("selected").takeIf { it > 0 }, saved.getLong("next"))
        }
        scene.surfaces.forEach { layer ->
            val session = newSession(layer.id)
            savedInstanceState?.getBundle("scene")?.getBundle(layer.id.toString())?.let {
                session.player.restore(it.getBoolean("playing"), it.getInt("position"))
                session.missing = it.getBundle("missing")?.let(::readSource)
                session.failure = it.getString("failure")?.let(MediaFailure::valueOf)
            }
        }
        pickerAddsSurface = savedInstanceState?.getBoolean("picker_add") ?: false
        pickerTargetId = savedInstanceState?.getLong("picker_target")?.takeIf { it > 0 }
        buildUi()
        setOutputMode(savedInstanceState?.getBoolean("output") ?: false)
        refreshEditor()
    }

    private fun newSession(id: Long): MediaSession {
        val mediaPlayer = SamplePlayer(this, { ready, _ ->
            sessions[id]?.ready = ready
            if (scene.selectedId == id) refreshPlayback()
            updateKeepScreenOn()
        }, { failure ->
            if (sessions.containsKey(id)) {
                selectSurface(id)
                showError(failure, fallbackToSample = failure == MediaFailure.UNAVAILABLE)
            }
        })
        return MediaSession(mediaPlayer).also { sessions[id] = it }
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
            { id, media, videoSurface ->
                runOnUiThread {
                    if (resumed && renderer.surfaceFor(id) === videoSurface &&
                        scene.surfaces.any { it.id == id && it.source == media }) {
                        rendererFailed = false
                        attachSource(id, videoSurface)
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
                    if (scene.selectedId != null && player.wantsToPlay) metrics.text = getString(
                        if (landscape) R.string.fps_compact else R.string.fps, renderFps, videoFps,
                    )
                }
            },
        )
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        renderer.updateScene(scene)
        stage.addView(glView, FrameLayout.LayoutParams(-1, -1))
        overlay = CornerOverlay(this, { quad }, ::moveCorner) {
            Toast.makeText(this, R.string.invalid_quad, Toast.LENGTH_SHORT).show()
        }
        overlay.id = R.id.corner_overlay
        stage.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        emptyCanvas = text(getString(R.string.empty_canvas), 16, secondary).apply {
            id = R.id.empty_canvas
            gravity = Gravity.CENTER
        }
        stage.addView(emptyCanvas, FrameLayout.LayoutParams(-1, -1))
        editorChrome += emptyCanvas
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
        errorAction = button(R.string.retry, false).apply {
            id = R.id.retry_playback
            setOnClickListener { recoverError() }
        }
        errorPanel.addView(errorAction)
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
        sourceLabel = text("", 16, Color.WHITE, true).apply { id = R.id.source_label }
        sourceHeader.addView(sourceLabel,
            if (sourceHeader.orientation == LinearLayout.VERTICAL) LinearLayout.LayoutParams(-1, -2)
            else LinearLayout.LayoutParams(0, -2, 1f))
        status = text(getString(R.string.loading), 12, cyan).apply {
            id = R.id.playback_state
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        sourceHeader.addView(status)
        source.addView(sourceHeader)
        sourceDetail = text("", 12, secondary).apply {
            id = R.id.source_detail
            setPadding(0, dp(5), 0, 0)
        }
        source.addView(sourceDetail)
        sourceAction = button(R.string.import_media, false).apply {
            id = R.id.import_media
            setOnClickListener { launchPicker() }
        }
        source.addView(sourceAction, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        panel.addView(source)
        val composition = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        layersButton = button(R.string.layers, false).apply {
            id = R.id.layers
            setOnClickListener { showLayers() }
        }
        composition.addView(layersButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        composition.addView(button(R.string.add_surface, false).apply {
            id = R.id.add_surface
            setOnClickListener { showAddSurface() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(composition, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        propertiesButton = button(R.string.surface_properties, false).apply {
            id = R.id.surface_properties
            setOnClickListener { showProperties() }
        }
        panel.addView(propertiesButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

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
                scene.selected?.let { updateSurface(it.copy(quad = Quad.DEFAULT)) }
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
        compositionDialog?.dismiss()
        outputMode = enabled
        editorChrome.forEach { it.visibility = if (enabled) View.GONE else View.VISIBLE }
        overlay.visibility = if (!enabled && scene.selected?.visible == true) View.VISIBLE else View.GONE
        emptyCanvas.visibility = if (!enabled && scene.surfaces.isEmpty()) View.VISIBLE else View.GONE
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
        if (outputMode || scene.surfaces.any { layer ->
                val session = sessions[layer.id]
                layer.visible && session?.ready == true && session.player.wantsToPlay
            }) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun launchPicker() {
        pickerTargetId = scene.selectedId
        pickerAddsSurface = false
        openPicker("*/*")
    }

    private fun openPicker(mime: String) {
        val intent = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).apply {
                if (mime != "*/*") type = mime
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                if (mime == "*/*") putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            }
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_MEDIA)
        } catch (error: RuntimeException) {
            Log.e("Lumis", "Unable to launch media picker", error)
            showError(MediaFailure.UNAVAILABLE, fallbackToSample = false)
        }
    }

    private fun retainReadPermission(uri: Uri) {
        if (uri.scheme != "content") return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (error: SecurityException) {
            Log.w("Lumis", "Provider grants only temporary media access", error)
        } catch (error: IllegalArgumentException) {
            Log.w("Lumis", "Provider does not support persistent media access", error)
        }
    }

    fun importFromUri(uri: Uri) {
        retainReadPermission(uri)
        val next = when (inspectUri(uri).kind) {
            InspectedKind.IMAGE -> MediaSource.Image(uri)
            InspectedKind.VIDEO -> MediaSource.Video(uri)
            InspectedKind.UNSUPPORTED -> {
                showError(MediaFailure.UNSUPPORTED)
                return
            }
            InspectedKind.UNAVAILABLE -> {
                showError(MediaFailure.UNAVAILABLE, fallbackToSample = false)
                return
            }
        }
        if (next is MediaSource.Image) {
            // Validate before replacing the selected source. Upload follows resource creation.
            val bitmap = decodeImage(uri) ?: return
            bitmap.recycle()
        }
        val selected = scene.selected
        if (selected == null) addSurface(next) else {
            val session = requireNotNull(sessions[selected.id])
            session.player.release()
            session.ready = false
            session.failure = null
            session.missing = null
            session.player.restore(true, 0)
            updateSurface(selected.copy(source = next))
            if (next == selected.source) renderer.surfaceFor(selected.id)?.let { attachSource(selected.id, it) }
        }
    }

    private fun attachSource(id: Long, surface: android.view.Surface) {
        val layer = scene.surfaces.find { it.id == id } ?: return
        val session = requireNotNull(sessions[id])
        when (val current = layer.source) {
            MediaSource.BundledSample, is MediaSource.Video -> {
                session.player.setSource(current, resetPosition = false)
                session.player.attach(surface)
            }
            is MediaSource.Image -> {
                session.player.release()
                val bitmap = decodeImage(current.uri) { failure ->
                    selectSurface(id)
                    showError(failure, current, fallbackToSample = failure == MediaFailure.UNAVAILABLE)
                } ?: return
                renderer.setImage(id, current, bitmap)
            }
        }
        if (scene.selectedId == id) refreshEditor()
    }

    private enum class InspectedKind { IMAGE, VIDEO, UNSUPPORTED, UNAVAILABLE }

    private data class InspectResult(val kind: InspectedKind)

    private fun inspectUri(uri: Uri): InspectResult {
        return try {
            val resolverType = contentResolver.getType(uri)
            if (resolverType?.startsWith("image/") == true) return InspectResult(InspectedKind.IMAGE)
            if (resolverType?.startsWith("video/") == true) return InspectResult(InspectedKind.VIDEO)
            val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            if (!extension.isNullOrBlank()) {
                val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
                if (guessed?.startsWith("image/") == true) return InspectResult(InspectedKind.IMAGE)
                if (guessed?.startsWith("video/") == true) return InspectResult(InspectedKind.VIDEO)
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)
                if (options.outWidth > 0 && options.outHeight > 0) return InspectResult(InspectedKind.IMAGE)
            } ?: return InspectResult(InspectedKind.UNAVAILABLE)
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                if (!width.isNullOrBlank() || !duration.isNullOrBlank()) InspectResult(InspectedKind.VIDEO)
                else InspectResult(InspectedKind.UNSUPPORTED)
            } finally {
                retriever.release()
            }
        } catch (_: FileNotFoundException) {
            InspectResult(InspectedKind.UNAVAILABLE)
        } catch (_: SecurityException) {
            InspectResult(InspectedKind.UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            InspectResult(InspectedKind.UNSUPPORTED)
        } catch (_: RuntimeException) {
            InspectResult(InspectedKind.UNSUPPORTED)
        }
    }

    private fun decodeImage(uri: Uri, onFailure: (MediaFailure) -> Unit = { showError(it) }) = try {
        val bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                val scale = minOf(1.0, 2048.0 / maxOf(info.size.width, info.size.height))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options()
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
        bitmap?.takeIf { it.width > 0 && it.height > 0 } ?: run {
            onFailure(MediaFailure.UNSUPPORTED)
            null
        }
    } catch (error: FileNotFoundException) {
        Log.e("Lumis", "Image source unavailable", error)
        onFailure(MediaFailure.UNAVAILABLE)
        null
    } catch (error: SecurityException) {
        Log.e("Lumis", "Image access denied", error)
        onFailure(MediaFailure.UNAVAILABLE)
        null
    } catch (error: IOException) {
        Log.e("Lumis", "Image decoding failed", error)
        onFailure(MediaFailure.UNSUPPORTED)
        null
    } catch (error: IllegalArgumentException) {
        Log.e("Lumis", "Image data unsupported", error)
        onFailure(MediaFailure.UNSUPPORTED)
        null
    }

    private fun mediaName(uri: Uri): String {
        if (uri.scheme == "android.resource") return getString(R.string.custom_source)
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)
                else null
            } ?: getString(R.string.custom_source)
        } catch (_: RuntimeException) {
            getString(R.string.custom_source)
        }
    }

    private fun updateSourceUi() {
        if (scene.selected == null) {
            sourceLabel.setText(R.string.empty_canvas_title)
            sourceDetail.setText(R.string.empty_canvas)
            sourceAction.isEnabled = false
            return
        }
        sourceAction.isEnabled = true
        when (val current = source) {
            MediaSource.BundledSample -> {
                sourceLabel.setText(R.string.sample_label)
                sourceDetail.setText(R.string.sample_detail)
                sourceAction.setText(R.string.import_media)
            }
            is MediaSource.Video -> {
                sourceLabel.text = mediaName(current.uri)
                sourceDetail.setText(R.string.imported_video)
                sourceAction.setText(R.string.replace_media)
            }
            is MediaSource.Image -> {
                sourceLabel.text = mediaName(current.uri)
                sourceDetail.setText(R.string.imported_image)
                sourceAction.setText(R.string.replace_media)
            }
        }
    }

    private fun refreshEditor() {
        updateSourceUi()
        refreshPlayback()
        layersButton.text = getString(R.string.layers_count, scene.surfaces.size)
        propertiesButton.isEnabled = scene.selected != null
        findViewById<View>(R.id.reset_corners).isEnabled = scene.selected != null
        overlay.visibility = if (!outputMode && scene.selected?.visible == true) View.VISIBLE else View.GONE
        emptyCanvas.visibility = if (!outputMode && scene.surfaces.isEmpty()) View.VISIBLE else View.GONE
        overlay.update()
        val failure = sessions[scene.selectedId]?.failure
        if (!rendererFailed) {
            errorPanel.visibility = if (failure == null) View.GONE else View.VISIBLE
            failure?.let {
                errorMessage.setText(when (it) {
                    MediaFailure.UNAVAILABLE -> R.string.source_unavailable
                    MediaFailure.UNSUPPORTED -> R.string.source_unsupported
                    MediaFailure.DECODER -> R.string.video_error
                })
                errorAction.setText(if (it == MediaFailure.UNAVAILABLE) R.string.replace_media else R.string.retry)
            }
        }
    }

    fun selectSurface(id: Long) {
        scene = scene.select(id)
        renderer.updateScene(scene)
        refreshEditor()
    }

    fun updateSurface(surface: SceneSurface) {
        scene = scene.update(surface)
        renderer.updateScene(scene)
        refreshEditor()
    }

    fun addSurface(source: MediaSource = MediaSource.BundledSample) {
        scene = scene.add(source)
        newSession(requireNotNull(scene.selectedId))
        renderer.updateScene(scene)
        refreshEditor()
    }

    fun duplicateSurface() {
        val originalId = requireNotNull(scene.selectedId)
        val original = requireNotNull(sessions[originalId])
        scene = scene.duplicate()
        newSession(requireNotNull(scene.selectedId)).also {
            it.player.restore(original.player.wantsToPlay, original.player.position())
            it.failure = original.failure
            it.missing = original.missing
        }
        renderer.updateScene(scene)
        refreshEditor()
    }

    private fun deleteSurface(id: Long) {
        sessions.remove(id)?.player?.release()
        scene = scene.delete(id)
        renderer.updateScene(scene)
        refreshEditor()
    }

    private fun showLayers() {
        val panel = dialogPanel()
        scene.surfaces.asReversed().forEach { layer ->
            val row = LinearLayout(this).apply { isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
            row.addView(button(R.string.surface_name, layer.id == scene.selectedId).apply {
                text = getString(R.string.surface_number, layer.id)
                isSelected = layer.id == scene.selectedId
                contentDescription = getString(R.string.select_surface, layer.id)
                setOnClickListener {
                    selectSurface(layer.id)
                    compositionDialog?.dismiss()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            @Suppress("UseSwitchCompatOrMaterialCode")
            row.addView(Switch(this).apply {
                minHeight = dp(48)
                setTextColor(secondary)
                text = getString(R.string.show_surface)
                contentDescription = getString(R.string.visibility_surface, layer.id)
                isChecked = layer.visible
                setOnCheckedChangeListener { _, visible ->
                    scene.surfaces.find { it.id == layer.id }?.let { updateSurface(it.copy(visible = visible)) }
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        if (scene.surfaces.isEmpty()) panel.addView(text(getString(R.string.empty_canvas), 14, secondary))
        showPanel(R.string.layers, panel)
    }

    private fun showAddSurface() {
        val panel = dialogPanel()
        listOf(R.string.bundled_sample to null, R.string.add_image to "image/*", R.string.add_video to "video/*").forEach { (label, mime) ->
            panel.addView(button(label, label == R.string.bundled_sample).apply {
                setOnClickListener {
                    compositionDialog?.dismiss()
                    if (mime == null) addSurface() else {
                        pickerAddsSurface = true
                        pickerTargetId = null
                        openPicker(mime)
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        showPanel(R.string.add_surface, panel)
    }

    private fun showProperties() {
        val selected = scene.selected ?: return
        val id = selected.id
        val panel = dialogPanel()
        panel.addView(text(getString(R.string.surface_number, id), 18, Color.WHITE, true))
        @Suppress("UseSwitchCompatOrMaterialCode")
        val visible = Switch(this).apply {
            this.id = R.id.surface_visibility
            text = getString(R.string.show_surface)
            setTextColor(secondary)
            minHeight = dp(48)
            isChecked = selected.visible
            setOnCheckedChangeListener { _, value ->
                scene.surfaces.find { it.id == id }?.let { updateSurface(it.copy(visible = value)) }
            }
        }
        panel.addView(visible)
        val opacityLabel = text(getString(R.string.opacity_percent, (selected.opacity * 100).toInt()), 14, secondary)
        panel.addView(opacityLabel)
        panel.addView(SeekBar(this).apply {
            this.id = R.id.surface_opacity
            contentDescription = getString(R.string.opacity)
            minimumHeight = dp(48)
            max = 100
            progress = (selected.opacity * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    opacityLabel.text = getString(R.string.opacity_percent, progress)
                    scene.surfaces.find { it.id == id }?.let { updateSurface(it.copy(opacity = progress / 100f)) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        val backward = button(R.string.move_backward, false).apply { this.id = R.id.move_backward }
        val forward = button(R.string.move_forward, false).apply { this.id = R.id.move_forward }
        fun updateOrderButtons() {
            val index = scene.surfaces.indexOfFirst { it.id == id }
            backward.isEnabled = index > 0
            forward.isEnabled = index < scene.surfaces.lastIndex
        }
        listOf(backward to false, forward to true).forEach { (view, ahead) ->
            view.setOnClickListener {
                scene = scene.move(id, ahead)
                renderer.updateScene(scene)
                updateOrderButtons()
            }
            panel.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        updateOrderButtons()
        panel.addView(button(R.string.duplicate_surface, false).apply {
            this.id = R.id.duplicate_surface
            setOnClickListener {
                duplicateSurface()
                compositionDialog?.dismiss()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        panel.addView(button(R.string.delete_surface, false).apply {
            this.id = R.id.delete_surface
            setTextColor(Color.rgb(251, 113, 133))
            setOnClickListener {
                compositionDialog?.dismiss()
                confirmDelete(id)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        showPanel(R.string.surface_properties, panel)
    }

    private fun confirmDelete(id: Long) {
        compositionDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_surface_title, id))
            .setMessage(R.string.delete_surface_message)
            .setNegativeButton(R.string.keep_surface, null)
            .setPositiveButton(R.string.delete_surface) { _, _ -> deleteSurface(id) }
            .show()
            .also { it.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(251, 113, 133)) }
    }

    private fun dialogPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(12))
        setBackgroundColor(this@MainActivity.background)
    }

    private fun showPanel(title: Int, panel: LinearLayout) {
        compositionDialog?.dismiss()
        compositionDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton(R.string.done, null)
            .show()
    }

    private fun moveCorner(index: Int, point: Point): Boolean {
        val moved = quad.moveCorner(index, point) ?: return false
        if (moved === quad) return true
        val selected = scene.selected ?: return false
        scene = scene.update(selected.copy(quad = moved))
        renderer.updateScene(scene)
        overlay.update()
        return true
    }

    private fun refreshPlayback() {
        val session = sessions[scene.selectedId]
        val ready = session?.ready == true
        val playing = ready && session?.player?.wantsToPlay == true
        val image = source is MediaSource.Image
        playButton.isEnabled = ready && !image
        playButton.setText(if (playing && !image) R.string.pause else R.string.play)
        status.setText(
            when {
                scene.selected == null -> R.string.empty_canvas_title
                session?.failure != null -> R.string.error_title
                image -> R.string.still_image
                !ready -> R.string.loading
                playing -> R.string.playing
                else -> R.string.paused
            },
        )
        updateKeepScreenOn()
        if (!playing) metrics.setText(R.string.paused)
    }

    private fun showError(
        failure: MediaFailure,
        fallbackSource: MediaSource = source,
        fallbackToSample: Boolean = false,
    ) {
        if (outputMode) setOutputMode(false)
        rendererFailed = false
        val selected = scene.selected
        val session = sessions[scene.selectedId]
        session?.failure = failure
        if (fallbackToSample && selected != null && session != null) {
            session.missing = fallbackSource
            session.player.release()
            session.ready = false
            session.player.restore(true, 0)
            updateSurface(selected.copy(source = MediaSource.BundledSample))
        }
        refreshPlayback()
        errorMessage.setText(
            when (failure) {
                MediaFailure.UNAVAILABLE -> R.string.source_unavailable
                MediaFailure.UNSUPPORTED -> R.string.source_unsupported
                MediaFailure.DECODER -> R.string.video_error
            },
        )
        status.setText(R.string.error_title)
        errorAction.setText(if (failure == MediaFailure.UNAVAILABLE) R.string.replace_media else R.string.retry)
        errorPanel.visibility = View.VISIBLE
    }

    private fun showError(gpu: Boolean) {
        if (gpu) {
            if (outputMode) setOutputMode(false)
            rendererFailed = true
            sessions.values.forEach { it.player.release(); it.ready = false }
            playButton.isEnabled = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            errorMessage.setText(R.string.renderer_error)
            status.setText(R.string.error_title)
            errorAction.setText(R.string.retry)
            errorPanel.visibility = View.VISIBLE
            return
        }
        showError(MediaFailure.DECODER)
    }

    private fun recoverError() {
        if (rendererFailed) {
            recreate()
            return
        }
        val session = sessions[scene.selectedId]
        if (session?.failure == MediaFailure.UNAVAILABLE) {
            launchPicker()
            return
        }
        session?.failure = null
        errorPanel.visibility = View.GONE
        scene.selectedId?.let { id ->
            renderer.surfaceFor(id)?.let { attachSource(id, it) }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        glView.onResume()
    }

    override fun onPause() {
        resumed = false
        sessions.values.forEach { it.player.release(); it.ready = false }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        glView.queueEvent { renderer.release() }
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        compositionDialog?.dismiss()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_MEDIA || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (pickerAddsSurface) {
            retainReadPermission(uri)
            val next = when (inspectUri(uri).kind) {
                InspectedKind.IMAGE -> MediaSource.Image(uri)
                InspectedKind.VIDEO -> MediaSource.Video(uri)
                InspectedKind.UNSUPPORTED -> { showError(MediaFailure.UNSUPPORTED); return }
                InspectedKind.UNAVAILABLE -> { showError(MediaFailure.UNAVAILABLE); return }
            }
            if (next is MediaSource.Image) {
                val bitmap = decodeImage(uri) ?: return
                bitmap.recycle()
            }
            addSurface(next)
        } else {
            pickerTargetId?.takeIf { id -> scene.surfaces.any { it.id == id } }?.let(::selectSurface)
            importFromUri(uri)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("fps", showFps)
        outState.putBoolean("output", outputMode)
        outState.putBoolean("picker_add", pickerAddsSurface)
        outState.putLong("picker_target", pickerTargetId ?: 0)
        outState.putBundle("scene", Bundle().apply {
            putLongArray("ids", scene.surfaces.map { it.id }.toLongArray())
            putLong("selected", scene.selectedId ?: 0)
            putLong("next", scene.nextId)
            scene.surfaces.forEach { layer ->
                val session = requireNotNull(sessions[layer.id])
                putBundle(layer.id.toString(), sourceBundle(layer.source).apply {
                    putDoubleArray("quad", layer.quad.toCoordinates())
                    putBoolean("visible", layer.visible)
                    putFloat("opacity", layer.opacity)
                    putBoolean("playing", session.player.wantsToPlay)
                    putInt("position", session.player.position())
                    putString("failure", session.failure?.name)
                    session.missing?.let { putBundle("missing", sourceBundle(it)) }
                })
            }
        })
        super.onSaveInstanceState(outState)
    }

    private fun sourceBundle(source: MediaSource) = Bundle().apply {
        when (source) {
            MediaSource.BundledSample -> putString("type", "sample")
            is MediaSource.Image -> { putString("type", "image"); putString("uri", source.uri.toString()) }
            is MediaSource.Video -> { putString("type", "video"); putString("uri", source.uri.toString()) }
        }
    }

    private fun readSource(bundle: Bundle): MediaSource = when (bundle.getString("type")) {
        "image" -> MediaSource.Image(Uri.parse(requireNotNull(bundle.getString("uri"))))
        "video" -> MediaSource.Video(Uri.parse(requireNotNull(bundle.getString("uri"))))
        else -> MediaSource.BundledSample
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

    companion object {
        private const val REQUEST_PICK_MEDIA = 2030
    }
}
