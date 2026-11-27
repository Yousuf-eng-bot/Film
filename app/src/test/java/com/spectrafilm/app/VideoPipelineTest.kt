/*
 * Film (Yousuf-eng-bot) — video pipeline tests. GPLv3.
 *
 * The frame conversion itself is NOT re-tested here: video frames go through
 * ImagePipeline.bitmapToLinearProPhoto — the exact same matrix/tables the photo
 * decode uses — and that path is pinned by the engine parity gates. What needs its
 * own tests is everything video-specific: the trilinear LUT lookup (export path),
 * the .cube grammar, the YUV packer, and the rate/size clamps.
 */
package com.spectrafilm.app

import com.spectrafilm.app.video.TrilinearLut
import com.spectrafilm.app.video.VideoExport
import com.spectrafilm.app.video.VideoYuv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPipelineTest {

    private fun close(a: Float, b: Float, tol: Float = 1e-6f) = Math.abs(a - b) <= tol

    @Test
    fun `trilinear lut lookup is exact at lattice points`() {
        val n = 3
        val rgb = FloatArray(n * n * n * 3) { i -> (i % 97) / 97f }
        val lut = TrilinearLut(n, rgb)
        val dst = FloatArray(3)
        for (r in 0 until n) {
            for (g in 0 until n) {
                for (b in 0 until n) {
                    lut.apply(
                        r / (n - 1).toFloat(),
                        g / (n - 1).toFloat(),
                        b / (n - 1).toFloat(),
                        dst, 0,
                    )
                    val idx = ((r * n + g) * n + b) * 3
                    assertTrue("r=$r g=$g b=$b", close(dst[0], rgb[idx], 1e-5f))
                    assertTrue(close(dst[1], rgb[idx + 1], 1e-5f))
                    assertTrue(close(dst[2], rgb[idx + 2], 1e-5f))
                }
            }
        }
    }

    @Test
    fun `trilinear lut interpolates monotonically between nodes`() {
        // A one-channel ramp along blue: value == blue coord everywhere.
        val n = 5
        val rgb = FloatArray(n * n * n * 3)
        for (r in 0 until n) for (g in 0 until n) for (b in 0 until n) {
            val idx = ((r * n + g) * n + b) * 3
            rgb[idx] = b / (n - 1).toFloat()
            rgb[idx + 1] = b / (n - 1).toFloat()
            rgb[idx + 2] = b / (n - 1).toFloat()
        }
        val lut = TrilinearLut(n, rgb)
        val dst = FloatArray(3)
        val step = 1f / (n - 1)
        var b = 0f
        while (b <= 1f) {
            lut.apply(0f, 0f, b, dst, 0)
            assertTrue("b=$b got ${dst[0]}", close(dst[0], b, 1e-5f))
            b += step / 4
        }
    }

    @Test
    fun `trilinear lut clamps out of range inputs`() {
        val n = 2
        val rgb = FloatArray(n * n * n * 3) { 0.25f }
        rgb[0] = 0.75f // node (0,0,0)
        val lut = TrilinearLut(n, rgb)
        val dst = FloatArray(3)
        lut.apply(-5f, -5f, -5f, dst, 0)
        assertTrue(close(dst[0], 0.75f))
        lut.apply(5f, 5f, 5f, dst, 0)
        assertTrue(close(dst[0], 0.25f))
    }

    @Test
    fun `cube parser accepts the engine bake format`() {
        val cube = """
            # Created by bakeCubeLut
            TITLE "Film test"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            0.1 0.2 0.3
            0.4 0.5 0.6
            0.7 0.8 0.9
            1.0 1.0 1.0
            0.2 0.3 0.4
            0.5 0.6 0.7
            0.8 0.9 1.0
            0.0 0.1 0.2
        """.trimIndent()
        val lut = TrilinearLut.parse(cube)
        assertNotNull(lut)
        assertEquals(2, lut!!.size)
        val dst = FloatArray(3)
        // Blue-fastest order (Adobe .cube spec: blue varies fastest, then green, then
        // red), matching bakeCubeLut's emitter and the ((r*N+g)*N+b) index:
        // rows: (r0,g0): b0,b1 | (r0,g1): b0,b1 | (r1,g0): b0,b1 | (r1,g1): b0,b1
        lut.apply(0f, 0f, 0f, dst, 0)
        assertTrue(close(dst[0], 0.1f))
        assertTrue(close(dst[1], 0.2f))
        assertTrue(close(dst[2], 0.3f))
        // Node (0,0,1) is the second row (blue varies fastest).
        lut.apply(0f, 0f, 1f, dst, 0)
        assertTrue(close(dst[0], 0.4f))
        // Node (0,1,0) is row 2: green steps after all blue entries for g=0.
        lut.apply(0f, 1f, 0f, dst, 0)
        assertTrue(close(dst[0], 0.7f))
        // Node (1,0,0) is row 4: red steps after all blue-green entries for r=0.
        lut.apply(1f, 0f, 0f, dst, 0)
        assertTrue(close(dst[0], 0.2f))
    }

    @Test
    fun `cube parser rejects malformed input`() {
        assertTrue(TrilinearLut.parse("LUT_3D_SIZE 2\n0.1 0.2 0.3") == null)
        assertTrue(TrilinearLut.parse("") == null)
    }

    @Test
    fun `yuv constants match android mediaformat`() {
        // Inlined from android.media.MediaFormat so the exporter stays JVM-testable.
        assertEquals(1, VideoYuv.COLOR_STANDARD_BT709)
        assertEquals(2, VideoYuv.COLOR_TRANSFER_SRGB)
        assertEquals(2, VideoYuv.COLOR_RANGE_LIMITED)
    }

    @Test
    fun `yuv conversion places black white and blue correctly`() {
        val w = 4
        val h = 4
        val pixels = IntArray(w * h)
        // Top half black, bottom half pure blue (sRGB-encoded display values).
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (y < 2) 0xFF000000.toInt() else 0xFF0000FF.toInt()
            }
        }
        val planes = VideoYuv.Planes(w, h)
        VideoYuv.argbToYuv420(pixels, w, h, planes)
        assertEquals(16, planes.y[0].toInt() and 0xFF) // black -> Y 16
        // Pure blue: Y = 16.5 + 25.064*(255/255) = 41.5 -> 41
        assertEquals(41, planes.y[2 * w].toInt() and 0xFF)
        // Bottom 2x2 chroma: pure blue -> Cb = 128.5 + 224*(1-0.0722)/1.8556 = 240
        assertEquals(240, planes.u[planes.cw].toInt() and 0xFF)
        // Cr for pure blue = 128.5 + 224*(0-0.0722)/1.6396 = 118.6 -> 118
        assertEquals(118, planes.v[planes.cw].toInt() and 0xFF)
        // Top chroma (black): neutral 128.
        assertEquals(128, planes.u[0].toInt() and 0xFF)
    }

    @Test
    fun `yuv white stays in range`() {
        val w = 2
        val h = 2
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        val planes = VideoYuv.Planes(w, h)
        VideoYuv.argbToYuv420(pixels, w, h, planes)
        assertEquals(235, planes.y[0].toInt() and 0xFF)
        assertEquals(128, planes.u[0].toInt() and 0xFF)
        assertEquals(128, planes.v[0].toInt() and 0xFF)
    }

    @Test
    fun `bitrate scales with resolution`() {
        val base = VideoExport.bitrateFor(1920, 1080, 24)
        assertTrue("base=$base", base in 10_000_000..14_000_000)
        val small = VideoExport.bitrateFor(640, 360, 24)
        assertTrue("small=$small", small >= 2_000_000)
        val capped = VideoExport.bitrateFor(4096, 2160, 60)
        assertTrue("capped=$capped", capped <= 40_000_000)
    }

    @Test
    fun `export edges are clamped`() {
        assertEquals(1920, VideoExport.exportLongEdge(3840))
        assertEquals(1080, VideoExport.exportLongEdge(1080))
        assertEquals(720, VideoExport.previewLongEdge(1920))
        assertEquals(480, VideoExport.previewLongEdge(480))
    }
}
