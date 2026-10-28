package com.lumis.renderer

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import com.lumis.geometry.Homography
import com.lumis.editor.PreviewTransform
import com.lumis.geometry.Viewport
import com.lumis.media.MediaSource
import com.lumis.scene.Scene
import com.lumis.scene.SceneSurface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VideoRenderer(
    private val editInset: Double,
    private val requestFrame: () -> Unit,
    private val onSurface: (Long, MediaSource, Surface) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onMetrics: (Double, Double) -> Unit,
    private val onExternalState: (Surface, String?) -> Unit = { _, _ -> },
) : GLSurfaceView.Renderer {
    enum class ContentType { VIDEO, IMAGE }

    private data class Layer(val surface: SceneSurface, val transform: FloatArray = Homography(surface.quad).matrix)
    private data class ImageUpload(val source: MediaSource, val bitmap: Bitmap)
    private class Texture(val source: MediaSource, val id: Int) {
        val available = AtomicBoolean(false)
        var stream: SurfaceTexture? = null
        var surface: Surface? = null
        val matrix = FloatArray(16)
        var ready = false
    }

    private class Program(val id: Int) {
        val position = GLES20.glGetAttribLocation(id, "aSource")
        val homography = GLES20.glGetUniformLocation(id, "uHomography")
        val matrix = GLES20.glGetUniformLocation(id, "uTextureMatrix")
        val sampler = GLES20.glGetUniformLocation(id, "uMedia")
        val opacity = GLES20.glGetUniformLocation(id, "uOpacity")
        val viewport = GLES20.glGetUniformLocation(id, "uViewport")
    }

    @Volatile private var layers: List<Layer> = emptyList()
    @Volatile private var selectedId: Long? = null
    @Volatile private var preview = PreviewTransform()
    private val surfaces = ConcurrentHashMap<Long, Surface>()
    private val pendingImages = ConcurrentHashMap<Long, ImageUpload>()
    private val frameCounts = ConcurrentHashMap<Long, Long>()
    private val textures = mutableMapOf<Long, Texture>()
    private var videoProgram: Program? = null
    private var imageProgram: Program? = null
    private var failed = false
    private val externalLock = Any()
    private var externalTarget: Surface? = null
    private var externalFailed = false
    private var externalAnnounced = false
    private var external: ExternalEglOutput? = null
    @Volatile var externalFrames = 0L
        private set
    @Volatile var externalViewport: Viewport? = null
        private set
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var metricsStart = 0L
    private var metricDraws = 0
    private var metricFrames = 0
    @Volatile var renderedFrames = 0L
        private set
    @Volatile var videoFrames = 0L
        private set
    @Volatile var outputMode = false
        private set
    @Volatile var viewport = Viewport.fit(1, 1, 0.0)
        private set

    val currentSurface: Surface? get() = selectedId?.let(surfaces::get)
    val contentType: ContentType
        get() = if (layers.any { it.surface.id == selectedId && it.surface.source is MediaSource.Image }) ContentType.IMAGE else ContentType.VIDEO
    fun surfaceFor(id: Long): Surface? = surfaces[id]
    fun framesFor(id: Long): Long = frameCounts[id] ?: 0L

    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f)); position(0) }

    fun updateScene(scene: Scene) {
        selectedId = scene.selectedId
        layers = scene.surfaces.map(::Layer)
        requestFrame()
    }

    /** Ownership of the bitmap transfers to the GL thread, including disposal. */
    fun setImage(id: Long, source: MediaSource, bitmap: Bitmap) {
        pendingImages.put(id, ImageUpload(source, bitmap))?.bitmap?.recycle()
        requestFrame()
    }

    fun setOutputMode(enabled: Boolean) {
        outputMode = enabled
        requestFrame()
    }

    fun setPreview(transform: PreviewTransform) {
        preview = transform
        requestFrame()
    }

    fun setExternalTarget(surface: Surface?) {
        synchronized(externalLock) {
            externalTarget = surface
            externalFailed = false
            externalAnnounced = false
            externalViewport = null
        }
        requestFrame()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        failed = false
        external = ExternalEglOutput(requireNotNull(config))
        metricsStart = System.nanoTime()
        metricDraws = 0
        metricFrames = 0
        try {
            check(GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains("GL_OES_EGL_image_external") == true) {
                "External video textures are not supported."
            }
            videoProgram = Program(createProgram(VIDEO_VERTEX_SHADER, VIDEO_FRAGMENT_SHADER))
            imageProgram = Program(createProgram(IMAGE_VERTEX_SHADER, IMAGE_FRAGMENT_SHADER))
            checkGl("initializing compositor")
        } catch (error: IllegalStateException) {
            fail(error)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val output = outputMode
        val base = Viewport.fit(surfaceWidth, surfaceHeight, if (output) 0.0 else editInset)
        val fitted = if (output) base else preview.apply(base)
        viewport = fitted
        clearTarget(surfaceWidth, surfaceHeight, fitted, output)
        if (failed || videoProgram == null || imageProgram == null) return
        try {
            val snapshot = layers
            val desired = snapshot.associate { it.surface.id to it.surface.source }
            frameCounts.keys.filterNot(desired::containsKey).forEach { frameCounts.remove(it) }
            textures.keys.toList().filter { desired[it] != textures[it]?.source }.forEach { id ->
                surfaces.remove(id)
                releaseTexture(requireNotNull(textures.remove(id)))
            }
            snapshot.forEach { layer ->
                val s = layer.surface
                val texture = textures.getOrPut(s.id) { createTexture(s) }
                pendingImages.remove(s.id)?.let { image ->
                    try {
                        if (image.source == s.source && s.source is MediaSource.Image) {
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.id)
                            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, image.bitmap, 0)
                            texture.ready = true
                        }
                    } finally {
                        image.bitmap.recycle()
                    }
                }
                if (texture.available.compareAndSet(true, false)) {
                    texture.stream?.updateTexImage()
                    texture.stream?.getTransformMatrix(texture.matrix)
                    texture.ready = true
                    videoFrames++
                    frameCounts[s.id] = framesFor(s.id) + 1
                    metricFrames++
                }
            }
            drawScene(snapshot, fitted, surfaceWidth, surfaceHeight)
            drawExternal(snapshot)
            pendingImages.keys.filterNot(desired::containsKey).forEach { pendingImages.remove(it)?.bitmap?.recycle() }
            checkGl("compositing scene")
            renderedFrames++
            metricDraws++
            val now = System.nanoTime()
            val seconds = (now - metricsStart) / 1e9
            if (seconds >= 1.0) {
                onMetrics(metricDraws / seconds, metricFrames / seconds)
                metricsStart = now
                metricDraws = 0
                metricFrames = 0
            }
        } catch (error: IllegalStateException) {
            fail(error)
        }
    }

    private fun clearTarget(width: Int, height: Int, fitted: Viewport, output: Boolean) {
        GLES20.glViewport(0, 0, width, height)
        if (output) GLES20.glClearColor(0f, 0f, 0f, 1f) else GLES20.glClearColor(9f / 255, 11f / 255, 16f / 255, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(fitted.left.roundToInt(), (height - fitted.top - fitted.height).roundToInt(),
            fitted.width.roundToInt(), fitted.height.roundToInt())
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    private fun drawScene(snapshot: List<Layer>, fitted: Viewport, width: Int, height: Int) {
        snapshot.forEach { layer ->
            val texture = textures[layer.surface.id]
            if (layer.surface.visible && layer.surface.opacity > 0 && texture?.ready == true) {
                draw(layer, texture, fitted, width, height)
            }
        }
    }

    private fun drawExternal(snapshot: List<Layer>) = synchronized(externalLock) {
        val target = externalTarget
        if (target == null) {
            external?.release()
            return@synchronized
        }
        if (externalFailed) return@synchronized
        try {
            requireNotNull(external).draw(target) { width, height ->
                val fitted = Viewport.fit(width, height, 0.0)
                clearTarget(width, height, fitted, true)
                drawScene(snapshot, fitted, width, height)
                checkGl("drawing external output")
                externalViewport = fitted
            }
            externalFrames++
            if (!externalAnnounced) {
                externalAnnounced = true
                onExternalState(target, null)
            }
        } catch (error: IllegalStateException) {
            if (error is ExternalEglOutput.EditorContextLost) throw error
            externalFailed = true
            Log.e("LumisRenderer", "External output failed", error)
            onExternalState(target, error.message ?: "External output failed")
        }
    }

    private fun createTexture(layer: SceneSurface): Texture {
        val image = layer.source is MediaSource.Image
        val target = if (image) GLES20.GL_TEXTURE_2D else GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        GLES20.glBindTexture(target, names[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val texture = Texture(layer.source, names[0])
        // Image upload and video attachment are requested only after this texture exists.
        val stream = if (image) SurfaceTexture(false) else SurfaceTexture(names[0])
        texture.stream = stream
        stream.setOnFrameAvailableListener {
            texture.available.set(true)
            requestFrame()
        }
        val surface = Surface(stream)
        texture.surface = surface
        surfaces[layer.id] = surface
        onSurface(layer.id, layer.source, surface)
        return texture
    }

    private fun draw(layer: Layer, texture: Texture, fitted: Viewport, width: Int, height: Int) {
        val image = layer.surface.source is MediaSource.Image
        val program = requireNotNull(if (image) imageProgram else videoProgram)
        GLES20.glUseProgram(program.id)
        GLES20.glEnable(GLES20.GL_BLEND)
        // Android bitmap uploads are premultiplied; video is opaque before surface opacity.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(if (image) GLES20.GL_TEXTURE_2D else GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture.id)
        GLES20.glUniform1i(program.sampler, 0)
        GLES20.glUniform1f(program.opacity, layer.surface.opacity)
        GLES20.glUniform4f(program.viewport,
            (fitted.width / width).toFloat(), (fitted.height / height).toFloat(),
            ((2 * fitted.left + fitted.width) / width - 1).toFloat(),
            (1 - (2 * fitted.top + fitted.height) / height).toFloat())
        GLES20.glUniformMatrix3fv(program.homography, 1, false, layer.transform, 0)
        if (!image) GLES20.glUniformMatrix4fv(program.matrix, 1, false, texture.matrix, 0)
        GLES20.glEnableVertexAttribArray(program.position)
        GLES20.glVertexAttribPointer(program.position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(program.position)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        synchronized(externalLock) { external?.release(); external = null; externalViewport = null }
        surfaces.clear()
        textures.values.forEach(::releaseTexture)
        textures.clear()
        pendingImages.keys.toList().forEach { pendingImages.remove(it)?.bitmap?.recycle() }
        videoProgram?.let { GLES20.glDeleteProgram(it.id) }
        imageProgram?.let { GLES20.glDeleteProgram(it.id) }
        videoProgram = null
        imageProgram = null
    }

    private fun releaseTexture(texture: Texture) {
        texture.stream?.setOnFrameAvailableListener(null)
        texture.surface?.release()
        texture.stream?.release()
        GLES20.glDeleteTextures(1, intArrayOf(texture.id), 0)
    }

    private fun fail(error: IllegalStateException) {
        failed = true
        Log.e("LumisRenderer", "Scene compositing failed", error)
        synchronized(externalLock) {
            externalTarget?.let { onExternalState(it, error.message ?: "Scene compositing failed") }
        }
        onFailure(error.message ?: "Unknown OpenGL error")
    }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "OpenGL error $error while $operation" }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetProgramInfoLog(result)
            GLES20.glDeleteProgram(result)
            error("Media shader link failed: $message")
        }
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Media shader compilation failed: $message")
        }
        return shader
    }

    companion object {
        // Preserve projective w so both triangles interpolate texture coordinates perspectively.
        private const val VIDEO_VERTEX_SHADER = """
            attribute vec2 aSource;
            uniform mat3 uHomography;
            uniform vec4 uViewport;
            uniform mat4 uTextureMatrix;
            varying vec2 vTexture;
            void main() {
                vec3 p = uHomography * vec3(aSource, 1.0);
                gl_Position = vec4(2.0 * p.x - p.z, p.z - 2.0 * p.y, 0.0, p.z);
                gl_Position.xy = gl_Position.xy * uViewport.xy + uViewport.zw * gl_Position.w;
                vTexture = (uTextureMatrix * vec4(aSource.x, 1.0 - aSource.y, 0.0, 1.0)).xy;
            }
        """
        private const val IMAGE_VERTEX_SHADER = """
            attribute vec2 aSource;
            uniform mat3 uHomography;
            uniform vec4 uViewport;
            varying vec2 vTexture;
            void main() {
                vec3 p = uHomography * vec3(aSource, 1.0);
                gl_Position = vec4(2.0 * p.x - p.z, p.z - 2.0 * p.y, 0.0, p.z);
                gl_Position.xy = gl_Position.xy * uViewport.xy + uViewport.zw * gl_Position.w;
                vTexture = aSource;
            }
        """
        private const val VIDEO_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uMedia;
            uniform float uOpacity;
            varying vec2 vTexture;
            void main() { gl_FragColor = texture2D(uMedia, vTexture) * uOpacity; }
        """
        private const val IMAGE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uMedia;
            uniform float uOpacity;
            varying vec2 vTexture;
            void main() { gl_FragColor = texture2D(uMedia, vTexture) * uOpacity; }
        """
    }
}
