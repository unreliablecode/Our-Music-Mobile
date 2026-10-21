package com.github.musicyou.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES11Ext
import android.opengl.GLUtils
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.github.musicyou.audio.visualization.AudioVisualizationFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Canvas frames remain on the GPU before receiving their exact encoder timestamp. */
internal class EncoderCanvasSurface(
    private val input: Surface,
    private val width: Int,
    private val height: Int,
    private val preferHardware: Boolean = Build.VERSION.SDK_INT >= 29,
) : AutoCloseable {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private val texture = IntArray(1)
    private var hardwareProgram = 0
    private val hardwareTexture = IntArray(1)
    private var hardwareInput: HardwareCanvasInput? = null
    private var hardwareAttempted = false
    private var fallbackBitmap: Bitmap? = null
    private var fallbackCanvas: Canvas? = null
    private val textureMatrix = FloatArray(16)
    internal val hardwareAccelerated: Boolean get() = hardwareInput != null
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)); position(0)
    }
    private val hardwareVertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, 0x3142, 1, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No recordable graphics configuration is available." }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreateWindowSurface(display, configs[0], input, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            program = graphicsProgram(
                "attribute vec4 aPosition; attribute vec2 aTex; varying vec2 vTex; void main(){gl_Position=aPosition;vTex=aTex;}",
                "precision mediump float; varying vec2 vTex; uniform sampler2D uTexture; void main(){gl_FragColor=texture2D(uTexture,vTex);}",
            )
            GLES20.glGenTextures(1, texture, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        } catch (failure: Throwable) { close(); throw failure }
    }

    fun draw(painter: VideoFramePainter, frame: AudioVisualizationFrame?, presentationTimeUs: Long): Boolean {
        return drawCanvas(presentationTimeUs) { canvas -> painter.drawTo(canvas, frame, presentationTimeUs) }
    }

    /** One producer frame is consumed before another can be posted, preserving every export frame. */
    internal fun drawCanvas(presentationTimeUs: Long, draw: (Canvas) -> Unit): Boolean {
        makeCurrent()
        if (!hardwareAttempted) {
            hardwareAttempted = true
            if (preferHardware) prepareHardwareInput()
        }
        val hardware = hardwareInput
        val canvas = if (hardware != null) {
            // A device may expose the API without being able to allocate this surface configuration.
            // Fall back before drawing or queuing a frame, so no partially submitted frame is repeated.
            try { hardware.lockCanvas() } catch (_: RuntimeException) { releaseHardwareInput(); null }
        } else null
        if (canvas != null && hardware != null) {
            try { draw(canvas) } finally { hardware.post(canvas) }
            hardware.awaitFrame()
            makeCurrent()
            hardware.updateTexture(textureMatrix)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(hardwareProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, hardwareTexture[0])
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(hardwareProgram, "uTextureMatrix"), 1, false, textureMatrix, 0)
            drawTexture(hardwareProgram, hardwareVertices)
            present(presentationTimeUs)
            return true
        }
        val bitmap = fallbackBitmap ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { fallbackBitmap = it }
        val target = fallbackCanvas ?: Canvas(bitmap).also { fallbackCanvas = it }
        draw(target)
        draw(bitmap, presentationTimeUs)
        return false
    }

    fun draw(bitmap: Bitmap, presentationTimeUs: Long) {
        makeCurrent()
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        drawTexture(program, vertices)
        present(presentationTimeUs)
    }

    private fun makeCurrent() { check(EGL14.eglMakeCurrent(display, surface, surface, context)) }

    private fun drawTexture(program: Int, coordinates: java.nio.FloatBuffer) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val tex = GLES20.glGetAttribLocation(program, "aTex")
        coordinates.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, coordinates)
        coordinates.position(2); GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, coordinates)
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun present(presentationTimeUs: Long) {
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "The video renderer encountered a graphics error." }
        check(EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeUs * 1000L))
        check(EGL14.eglSwapBuffers(display, surface)) { "The video encoder rejected a frame." }
    }

    private fun prepareHardwareInput() {
        try {
            hardwareProgram = graphicsProgram(
                "attribute vec4 aPosition; attribute vec2 aTex; uniform mat4 uTextureMatrix; varying vec2 vTex; void main(){gl_Position=aPosition;vTex=(uTextureMatrix*vec4(aTex,0.0,1.0)).xy;}",
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTex; uniform samplerExternalOES uTexture; void main(){gl_FragColor=texture2D(uTexture,vTex);}",
            )
            GLES20.glGenTextures(1, hardwareTexture, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, hardwareTexture[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            hardwareInput = HardwareCanvasInput(hardwareTexture[0], width, height)
        } catch (_: RuntimeException) {
            releaseHardwareInput()
            // Consume any failed setup error before using the independent bitmap texture.
            repeat(16) { if (GLES20.glGetError() == GLES20.GL_NO_ERROR) return }
        }
    }

    private fun releaseHardwareInput() {
        hardwareInput?.close(); hardwareInput = null
        if (hardwareTexture[0] != 0) { GLES20.glDeleteTextures(1, hardwareTexture, 0); hardwareTexture[0] = 0 }
        if (hardwareProgram != 0) { GLES20.glDeleteProgram(hardwareProgram); hardwareProgram = 0 }
    }

    private fun graphicsProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = shader(GLES20.GL_VERTEX_SHADER, vertexSource)
        var fragment = 0
        var linkedProgram = 0
        try {
            fragment = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            linkedProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(linkedProgram, vertex); GLES20.glAttachShader(linkedProgram, fragment)
            GLES20.glLinkProgram(linkedProgram)
            val linked = IntArray(1); GLES20.glGetProgramiv(linkedProgram, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { "The video renderer could not link its graphics program." }
            return linkedProgram
        } catch (failure: Throwable) {
            if (linkedProgram != 0) GLES20.glDeleteProgram(linkedProgram)
            throw failure
        } finally { GLES20.glDeleteShader(vertex); if (fragment != 0) GLES20.glDeleteShader(fragment) }
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
        val compiled = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) { GLES20.glDeleteShader(shader); error("The video renderer could not compile its graphics program.") }
        return shader
    }

    override fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT) {
                runCatching { makeCurrent() }
                runCatching { releaseHardwareInput() }
                runCatching {
                    GLES20.glDeleteTextures(1, texture, 0); if (program != 0) GLES20.glDeleteProgram(program)
                }
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
        }
        surface = EGL14.EGL_NO_SURFACE; context = EGL14.EGL_NO_CONTEXT; display = EGL14.EGL_NO_DISPLAY
        fallbackBitmap?.recycle(); fallbackBitmap = null; fallbackCanvas = null
        input.release()
    }
}

/** The callback has its own Looper because the export thread waits for each submitted frame. */
private class HardwareCanvasInput(texture: Int, width: Int, height: Int) : AutoCloseable {
    private val available = Semaphore(0)
    private val callbacks = HandlerThread("Video canvas frames").apply { start() }
    private val stream: SurfaceTexture
    private val producer: Surface

    init {
        var createdStream: SurfaceTexture? = null
        try {
            stream = SurfaceTexture(texture).also { createdStream = it }
            stream.setDefaultBufferSize(width, height)
            stream.setOnFrameAvailableListener({ available.release() }, Handler(callbacks.looper))
            producer = Surface(stream)
        } catch (failure: Throwable) {
            createdStream?.release(); callbacks.quitSafely(); throw failure
        }
    }

    fun lockCanvas(): Canvas { available.drainPermits(); return producer.lockHardwareCanvas() }
    fun post(canvas: Canvas) = producer.unlockCanvasAndPost(canvas)
    fun awaitFrame() { check(available.tryAcquire(15, TimeUnit.SECONDS)) { "The graphics renderer stopped producing video frames." } }
    fun updateTexture(matrix: FloatArray) { stream.updateTexImage(); stream.getTransformMatrix(matrix) }

    override fun close() {
        stream.setOnFrameAvailableListener(null)
        producer.release()
        stream.release()
        callbacks.quitSafely()
    }
}
