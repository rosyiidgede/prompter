package app.prompter

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/** CameraX effect that runs frames through [FilterProcessor] for both preview and recorded video. */
class FilterEffect(executor: Executor, processor: FilterProcessor) :
    CameraEffect(PREVIEW or VIDEO_CAPTURE, executor, processor, Consumer<Throwable> { })

/** Minimal OpenGL pipeline: camera OES texture -> colour-grade shader -> every output surface. */
class FilterProcessor : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    private val thread = HandlerThread("filter-gl").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    @Volatile var preset: FilterPreset = Filters.ORIGINAL
    @Volatile var strength: Float = 1f

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val outputs = HashMap<SurfaceOutput, EGLSurface>()
    private val texMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)

    private val vertices: FloatBuffer = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoords: FloatBuffer = floatBuf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    private fun floatBuf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a)
            position(0)
        }

    override fun onInputSurface(request: SurfaceRequest) {
        initGl()
        makeCurrentPbuffer()
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(tex[0])
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(st)
        st.setOnFrameAvailableListener(this, handler)
        surfaceTexture = st
        texId = tex[0]
        request.provideSurface(surface, executor) {
            st.setOnFrameAvailableListener(null)
            if (surfaceTexture === st) surfaceTexture = null
            makeCurrentPbuffer()
            GLES20.glDeleteTextures(1, intArrayOf(tex[0]), 0)
            st.release()
            surface.release()
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        initGl()
        val surface = output.getSurface(executor) {
            outputs.remove(output)?.let { EGL14.eglDestroySurface(display, it) }
            output.close()
        }
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        outputs[output] = eglSurface
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (surfaceTexture !== st || display == EGL14.EGL_NO_DISPLAY) return
        makeCurrentPbuffer()
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        val ts = st.timestamp
        val p = preset
        val s = if (p === Filters.ORIGINAL) 0f else strength
        for ((output, eglSurface) in outputs) {
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
            output.updateTransformMatrix(outMatrix, texMatrix)
            draw(output.size.width, output.size.height, p, s)
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, ts)
            EGL14.eglSwapBuffers(display, eglSurface)
        }
    }

    private fun draw(w: Int, h: Int, p: FilterPreset, strength: Float) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vertices)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, texCoords)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, outMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSat"), p.sat)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uContrast"), p.contrast)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uBright"), p.bright)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLift"), p.lift)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uStrength"), strength)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(program, "uGain"), 1, p.gain, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(program, "uShadow"), 1, p.shadow, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(program, "uHigh"), 1, p.high, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    private fun makeCurrentPbuffer() {
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
    }

    private fun initGl() {
        if (display != EGL14.EGL_NO_DISPLAY) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(display, ver, 0, ver, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0)
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        makeCurrentPbuffer()
        program = buildProgram()
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "link: " + GLES20.glGetProgramInfoLog(prog) }
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "shader: " + GLES20.glGetShaderInfoLog(sh) }
        return sh
    }

    fun release() {
        handler.post {
            if (display != EGL14.EGL_NO_DISPLAY) {
                outputs.values.forEach { EGL14.eglDestroySurface(display, it) }
                outputs.clear()
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                display = EGL14.EGL_NO_DISPLAY
            }
            thread.quitSafely()
        }
    }

    private companion object {
        const val VERTEX = """
attribute vec4 aPos;
attribute vec4 aTex;
uniform mat4 uTexMatrix;
varying vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = (uTexMatrix * aTex).xy;
}"""
        const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTex;
uniform samplerExternalOES sTex;
uniform float uSat;
uniform float uContrast;
uniform float uBright;
uniform float uLift;
uniform float uStrength;
uniform vec3 uGain;
uniform vec3 uShadow;
uniform vec3 uHigh;
void main() {
    vec3 c = texture2D(sTex, vTex).rgb;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec3 g = mix(vec3(l), c, uSat);
    g = (g - 0.5) * uContrast + 0.5 + uBright;
    g = g * uGain;
    g = mix(vec3(uLift), vec3(1.0), g);
    float sh = 1.0 - smoothstep(0.0, 0.5, l);
    float hi = smoothstep(0.5, 1.0, l);
    g += uShadow * sh + uHigh * hi;
    gl_FragColor = vec4(mix(c, clamp(g, 0.0, 1.0), uStrength), 1.0);
}"""
    }
}
