/*
 * Film (Yousuf-eng-bot) — GPU LUT video preview surface. GPLv3.
 * Film modeling powered by spektrafilm (Andrea Volpato).
 *
 * GLES 3.0 surface that applies a baked 3D film LUT to one video frame, per the
 * Phase-2 design (docs/VIDEO_PLAN.md): the CPU engine bakes the 33^3 lattice once
 * per look (SpektraEngine.bakeCubeLut with SHAPER_SRGB), the GPU trilinearly samples
 * it per frame — the same architecture as the editor's LutGpuPreview, which this
 * file deliberately mirrors (shader math kept byte-identical so the two paths can
 * never drift): a linear ProPhoto RGB16F frame texture, exposure gain applied
 * BEFORE the lookup, the shaper encoding mapping the lookup into the lattice, and
 * the (B,G,R) axis mapping that preserves the engine's blue-fastest emission order.
 *
 * The frame is submitted as a direct FloatBuffer of interleaved linear ProPhoto
 * values (VideoColors.argbToLinearProPhoto produces exactly that) — one upload per
 * frame, no Bitmap ever crosses to the GL thread.
 */
package com.spectrafilm.app.video

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** One immutable GPU generation: frame pixels, LUT, and gain are never mixed. */
internal data class VideoFrameSubmission(
    val pixels: FloatBuffer,
    val width: Int,
    val height: Int,
    val lut: TrilinearLut,
    val exposureGain: Float,
)

/**
 * Preview surface. The producer thread calls [submitFrame] (cheap copy + publish),
 * the GL thread uploads and draws. [onUnavailable] fires once if shaders fail to
 * build so the caller can show a plain (CPU) frame instead of a black box.
 */
internal class VideoLutView(
    context: Context,
    private val onUnavailable: () -> Unit = {},
) : GLSurfaceView(context) {

    private val renderer = VideoLutRenderer(onUnavailable)

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Submit one frame; safe from any thread. [pixels] is copied before returning. */
    fun submitFrame(pixels: FloatBuffer, width: Int, height: Int, lut: TrilinearLut, gain: Float) {
        val copy = ByteBuffer.allocateDirect(pixels.capacity() * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        copy.put(pixels)
        copy.position(0)
        queueEvent { renderer.publish(VideoFrameSubmission(copy, width, height, lut, gain)) }
        requestRender()
    }

    /** Drop the frame so a closed/failed clip leaves a cleared surface, not a stale one. */
    fun clearFrame() {
        queueEvent { renderer.clear() }
        requestRender()
    }
}

internal class VideoLutRenderer(private val onUnavailable: () -> Unit) : GLSurfaceView.Renderer {

    private val pending = AtomicReference<VideoFrameSubmission?>()
    private var program = 0
    private var frameTex = 0
    private var lutTex = 0
    private var frameW = 0
    private var frameH = 0
    private var viewW = 0
    private var viewH = 0
    private var haveFrame = false
    private var haveLut = false
    private var reportedFail = false
    private var lastGain = 1f

    fun publish(submission: VideoFrameSubmission) {
        pending.set(submission)
    }

    fun clear() {
        pending.set(null)
        haveFrame = false
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        haveFrame = false
        haveLut = false
        program = buildProgram(VERT, FRAG)
        if (program == 0) {
            if (!reportedFail) { reportedFail = true; onUnavailable() }
            return
        }
        val tex = IntArray(2)
        GLES30.glGenTextures(2, tex, 0)
        frameTex = tex[0]
        lutTex = tex[1]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val submission = pending.getAndSet(null)
        if (submission != null) {
            uploadFrame(submission)
            uploadLut(submission.lut)
            lastGain = submission.exposureGain
        }
        GLES30.glClearColor(0.06f, 0.06f, 0.06f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!haveFrame || !haveLut || program == 0) return

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uProxy"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1)
        var sx = 1f
        var sy = 1f
        if (frameW > 0 && frameH > 0 && viewW > 0 && viewH > 0) {
            val imgA = frameW.toFloat() / frameH
            val viewA = viewW.toFloat() / viewH
            if (viewA > imgA) sx = imgA / viewA else sy = viewA / imgA
        }
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uScale"), sx, sy)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uExposureGain"), lastGain)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun uploadFrame(submission: VideoFrameSubmission) {
        frameW = submission.width
        frameH = submission.height
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB16F, frameW, frameH, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, submission.pixels,
        )
        haveFrame = true
    }

    private fun uploadLut(lut: TrilinearLut) {
        // bakeCubeLut emits blue-fastest (B varies fastest, then G, then R). GL's
        // glTexImage3D expects the first axis (width=R here) varying fastest, so map
        // LUT axes to (width=B, height=G, depth=R) and sample tex(b,g,r) below —
        // keeping the engine's ordering without a CPU re-shuffle.
        val n = lut.size
        val fb: FloatBuffer = ByteBuffer.allocateDirect(lut.rgb.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(lut.rgb)
        fb.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, n, n, n, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, fb,
        )
        haveLut = true
    }

    private fun buildProgram(vsrc: String, fsrc: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vsrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fsrc)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { GLES30.glDeleteProgram(p); return 0 }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        return s
    }

    companion object {
        // Same shaders as LutGpuPreview (byte-identical math, see file header).
        private const val VERT = """#version 300 es
            uniform vec2 uScale;
            out vec2 vUv;
            void main() {
                float x = float(gl_VertexID & 1);
                float y = float((gl_VertexID >> 1) & 1);
                vUv = vec2(x, 1.0 - y);
                gl_Position = vec4((vec2(x, y) * 2.0 - 1.0) * uScale, 0.0, 1.0);
            }
        """
        private const val FRAG = """#version 300 es
            precision highp float;
            precision highp sampler3D;
            in vec2 vUv;
            uniform sampler2D uProxy;
            uniform sampler3D uLut;
            uniform float uExposureGain;
            out vec4 fragColor;
            // EXACT inverse of shaper_to_linear in spektra.cpp. The LUT's entries are
            // spaced evenly in this encoded space, so the lookup must be indexed
            // through it or every value lands on the wrong lattice cell.
            vec3 shaperEncode(vec3 c) {
                return mix(c * 12.92,
                           1.055 * pow(max(c, 0.0), vec3(1.0 / 2.4)) - 0.055,
                           step(vec3(0.0031308), c));
            }
            void main() {
                // Exposure gain FIRST (the bake is at unity gain), then clamp to the
                // LUT's [0,1] linear-ProPhoto domain.
                vec3 lin = texture(uProxy, vUv).rgb * uExposureGain;
                vec3 c = shaperEncode(clamp(lin, 0.0, 1.0));
                // LUT axes are (B,G,R) fastest->slowest, so index (b,g,r).
                vec3 outc = texture(uLut, vec3(c.b, c.g, c.r)).rgb;
                fragColor = vec4(outc, 1.0);
            }
        """
    }
}
