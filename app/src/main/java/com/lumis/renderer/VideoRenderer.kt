package com.lumis.renderer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import com.lumis.geometry.Homography
import com.lumis.geometry.Quad
import com.lumis.geometry.Viewport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VideoRenderer(
    private val editInset: Double,
    private val requestFrame: () -> Unit,
    private val onSurface: (Surface) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onMetrics: (Double, Double) -> Unit,
) : GLSurfaceView.Renderer {
    @Volatile private var transform = Homography(Quad.DEFAULT).matrix
    @Volatile var currentSurface: Surface? = null
        private set
    @Volatile var renderedFrames = 0L
        private set
    @Volatile var videoFrames = 0L
        private set
    private val frameAvailable = AtomicBoolean(false)
    private var texture: SurfaceTexture? = null
    private var textureId = 0
    private var program = 0
    private var positionLocation = 0
    private var homographyLocation = 0
    private var textureMatrixLocation = 0
    private var samplerLocation = 0
    private val viewportBounds = IntArray(4)
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    @Volatile var outputMode = false
        private set
    @Volatile var viewport = Viewport.fit(1, 1, 0.0)
        private set
    private var failed = false
    private var hasFrame = false
    private var metricsStart = 0L
    private var metricDraws = 0
    private var metricFrames = 0
    private val textureMatrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f)); position(0) }

    fun updateQuad(quad: Quad) {
        transform = Homography(quad).matrix
        requestFrame()
    }

    /** Output reuses this scene and video texture; only the presentation framing changes. */
    fun setOutputMode(enabled: Boolean) {
        outputMode = enabled
        requestFrame()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        failed = false
        hasFrame = false
        frameAvailable.set(false)
        metricsStart = System.nanoTime()
        metricDraws = 0
        metricFrames = 0
        try {
            check(GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains("GL_OES_EGL_image_external") == true) {
                "External video textures are not supported."
            }
            program = createProgram()
            positionLocation = GLES20.glGetAttribLocation(program, "aSource")
            homographyLocation = GLES20.glGetUniformLocation(program, "uHomography")
            textureMatrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix")
            samplerLocation = GLES20.glGetUniformLocation(program, "uVideo")
            val names = IntArray(1)
            GLES20.glGenTextures(1, names, 0)
            textureId = names[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val newTexture = SurfaceTexture(textureId)
            newTexture.setOnFrameAvailableListener {
                frameAvailable.set(true)
                requestFrame()
            }
            texture = newTexture
            val surface = Surface(newTexture)
            currentSurface = surface
            onSurface(surface)
            checkGl("initializing video texture")
        } catch (error: IllegalStateException) {
            fail(error)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    private fun updateViewport(): Boolean {
        val output = outputMode
        val fitted = Viewport.fit(surfaceWidth, surfaceHeight, if (output) 0.0 else editInset)
        viewport = fitted
        viewportBounds[0] = fitted.left.roundToInt()
        viewportBounds[1] = (surfaceHeight - fitted.top - fitted.height).roundToInt()
        viewportBounds[2] = fitted.width.roundToInt()
        viewportBounds[3] = fitted.height.roundToInt()
        GLES20.glViewport(viewportBounds[0], viewportBounds[1], viewportBounds[2], viewportBounds[3])
        return output
    }

    override fun onDrawFrame(gl: GL10?) {
        if (updateViewport()) GLES20.glClearColor(0f, 0f, 0f, 1f)
        else GLES20.glClearColor(9f / 255, 11f / 255, 16f / 255, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(viewportBounds[0], viewportBounds[1], viewportBounds[2], viewportBounds[3])
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        if (failed || program == 0) return
        try {
            if (frameAvailable.compareAndSet(true, false)) {
                texture?.updateTexImage()
                texture?.getTransformMatrix(textureMatrix)
                hasFrame = true
                videoFrames++
                metricFrames++
            }
            if (hasFrame) {
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES20.glUniform1i(samplerLocation, 0)
                GLES20.glUniformMatrix3fv(homographyLocation, 1, false, transform, 0)
                GLES20.glUniformMatrix4fv(textureMatrixLocation, 1, false, textureMatrix, 0)
                GLES20.glEnableVertexAttribArray(positionLocation)
                GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertices)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(positionLocation)
                renderedFrames++
                metricDraws++
                checkGl("drawing video")
            }
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

    fun release() {
        currentSurface?.release()
        currentSurface = null
        texture?.setOnFrameAvailableListener(null)
        texture?.release()
        texture = null
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        textureId = 0
        program = 0
        hasFrame = false
    }

    private fun fail(error: IllegalStateException) {
        failed = true
        Log.e("LumisRenderer", "GPU video rendering failed", error)
        onFailure(error.message ?: "Unknown OpenGL error")
    }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "OpenGL error $error while $operation" }
    }

    private fun createProgram(): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
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
            error("Video shader link failed: $message")
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
            error("Video shader compilation failed: $message")
        }
        return shader
    }

    companion object {
        // Keeping projective w in clip space makes the GPU interpolate texture UVs
        // perspectively across both triangles, without a diagonal seam.
        private const val VERTEX_SHADER = """
            attribute vec2 aSource;
            uniform mat3 uHomography;
            uniform mat4 uTextureMatrix;
            varying vec2 vTexture;
            void main() {
                vec3 p = uHomography * vec3(aSource, 1.0);
                gl_Position = vec4(2.0 * p.x - p.z, p.z - 2.0 * p.y, 0.0, p.z);
                vTexture = (uTextureMatrix * vec4(aSource.x, 1.0 - aSource.y, 0.0, 1.0)).xy;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uVideo;
            varying vec2 vTexture;
            void main() {
                gl_FragColor = texture2D(uVideo, vTexture);
            }
        """
    }
}
