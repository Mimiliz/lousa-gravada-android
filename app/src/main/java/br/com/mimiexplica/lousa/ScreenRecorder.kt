package br.com.mimiexplica.lousa

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Grava a tela do app, recortando só a área da lousa, com o microfone.
 * A imagem da tela chega numa textura (OpenGL), é recortada e vai para o codificador de vídeo do tablet.
 */
class ScreenRecorder(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val screenW: Int,
    private val screenH: Int,
    private val dpi: Int,
    private val crop: Rect,
    private val outW: Int,
    private val outH: Int,
    val file: File,
    private val withMic: Boolean
) {
    private val thread = HandlerThread("gravador").apply { start() }
    private val h = Handler(thread.looper)
    private lateinit var mr: MediaRecorder
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var tex = 0
    private var program = 0
    private var st: SurfaceTexture? = null
    private var inSurface: Surface? = null
    private var vd: VirtualDisplay? = null
    private val texM = FloatArray(16)
    private var lastTs = 0L
    @Volatile private var running = false

    fun start(onError: (String) -> Unit) {
        try {
            mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            if (withMic) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (withMic) {
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioSamplingRate(48000)
                mr.setAudioEncodingBitRate(128_000)
                mr.setAudioChannels(1)
            }
            mr.setVideoSize(outW, outH)
            mr.setVideoFrameRate(30)
            mr.setVideoEncodingBitRate(if (outW * outH > 1_500_000) 10_000_000 else 6_000_000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
        } catch (e: Exception) {
            onError("Não consegui preparar a gravação: ${e.message}")
            thread.quitSafely(); return
        }
        h.post {
            try {
                setupEgl(mr.surface)
                setupGl()
                val t = SurfaceTexture(tex)
                t.setDefaultBufferSize(screenW, screenH)
                t.setOnFrameAvailableListener({ drawFrame() }, h)
                st = t
                inSurface = Surface(t)
                mr.start()
                running = true
                vd = projection.createVirtualDisplay(
                    "lousa", screenW, screenH, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inSurface, null, h
                )
            } catch (e: Exception) {
                onError("Não consegui iniciar a gravação: ${e.message}")
            }
        }
    }

    fun pause() = h.post { try { mr.pause() } catch (_: Exception) {} }
    fun resume() = h.post { try { mr.resume() } catch (_: Exception) {} }

    fun stop(done: (File?) -> Unit) {
        h.post {
            var ok = running
            running = false
            try { vd?.release() } catch (_: Exception) {}
            try { mr.stop() } catch (_: Exception) { ok = false }
            try { mr.release() } catch (_: Exception) {}
            try { st?.release(); inSurface?.release() } catch (_: Exception) {}
            releaseEgl()
            thread.quitSafely()
            done(if (ok && file.length() > 0) file else null)
        }
    }

    private fun drawFrame() {
        val t = st ?: return
        t.updateTexImage()
        if (!running) return
        val ts = t.timestamp
        if (ts - lastTs < 30_000_000L) return // no máximo 30 quadros por segundo
        lastTs = ts
        t.getTransformMatrix(texM)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexM"), 1, false, texM, 0)
        val u0 = crop.left.toFloat() / screenW
        val u1 = crop.right.toFloat() / screenW
        val v0 = 1f - crop.bottom.toFloat() / screenH
        val v1 = 1f - crop.top.toFloat() / screenH
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uCrop"), u0, v0, u1, v1)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aUv = GLES20.glGetAttribLocation(program, "aUv")
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, surface, ts)
        EGL14.eglSwapBuffers(display, surface)
    }

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )); position(0)
    }

    private fun setupEgl(target: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(display, ver, 0, ver, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreateWindowSurface(display, configs[0], target, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    private fun releaseEgl() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        return s
    }

    private fun setupGl() {
        val vs = """
            attribute vec2 aPos; attribute vec2 aUv;
            uniform mat4 uTexM; uniform vec4 uCrop;
            varying vec2 vUv;
            void main(){
              gl_Position = vec4(aPos, 0.0, 1.0);
              vec2 uv = vec2(mix(uCrop.x, uCrop.z, aUv.x), mix(uCrop.y, uCrop.w, aUv.y));
              vUv = (uTexM * vec4(uv, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTex;
            varying vec2 vUv;
            void main(){ gl_FragColor = texture2D(sTex, vUv); }
        """.trimIndent()
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(program)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }
}
