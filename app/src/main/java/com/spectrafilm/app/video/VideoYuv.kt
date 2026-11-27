/*
 * Film (Yousuf-eng-bot) — sRGB display frames to encoder-ready YUV420. GPLv3.
 * Film modeling powered by spektrafilm (Andrea Volpato).
 *
 * The LUT's output is sRGB-encoded display RGB (bakeCubeLut with the default
 * IoParams). The H.264 encoder consumes YUV 4:2:0; this module converts with the
 * BT.709 matrix coefficients and LIMITED range (16-235 / 16-240), the de-facto
 * standard for 8-bit video, and the encoded stream is tagged COLOR_TRANSFER_SRGB
 * (see VideoExport) so colour-aware players reproduce it exactly.
 *
 * Pure Kotlin (no android imports) so the constants and the plane layout are
 * unit-testable on the JVM; VideoExport copies the packed planes into the
 * MediaCodec Image's strided planes.
 */
package com.spectrafilm.app.video

internal object VideoYuv {

    /** Packed planar YUV420: full Y plane, then half-res U, then half-res V. */
    class Planes(w: Int, h: Int) {
        val width = w
        val height = h
        val y = ByteArray(w * h)
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
    }

    /**
     * Convert one ARGB frame (sRGB-encoded, as Bitmap.getPixels) into [out].
     * Chroma is the average of each 2x2 source block (converted once, post-average).
     */
    fun argbToYuv420(pixels: IntArray, w: Int, h: Int, out: Planes) {
        require(out.width == w && out.height == h) { "plane size mismatch" }
        require(pixels.size >= w * h) { "pixels too small" }

        fun rgb(p: Int): Triple<Float, Float, Float> = Triple(
            ((p shr 16) and 0xFF).toFloat(),
            ((p shr 8) and 0xFF).toFloat(),
            (p and 0xFF).toFloat(),
        )

        for (y in 0 until h) {
            val rowStart = y * w
            for (x in 0 until w) {
                val p = pixels[rowStart + x]
                val (r, g, b) = rgb(p)
                // BT.709 limited range: Y in 16..235.
                val yy = 16.5f + 65.738f * (r / 255f) + 129.057f * (g / 255f) + 25.064f * (b / 255f)
                out.y[rowStart + x] = yy.coerceIn(16f, 235f).toInt().toByte()
            }
        }
        for (cy in 0 until out.ch) {
            for (cx in 0 until out.cw) {
                val x0 = cx * 2
                val y0 = cy * 2
                val x1 = minOf(x0 + 1, w - 1)
                val y1 = minOf(y0 + 1, h - 1)
                val p00 = pixels[y0 * w + x0]
                val p01 = pixels[y0 * w + x1]
                val p10 = pixels[y1 * w + x0]
                val p11 = pixels[y1 * w + x1]
                // Average the 2x2 block in display RGB, then convert once.
                val r = (((p00 shr 16) and 0xFF) + ((p01 shr 16) and 0xFF) +
                    ((p10 shr 16) and 0xFF) + ((p11 shr 16) and 0xFF)) / 1020f
                val g = (((p00 shr 8) and 0xFF) + ((p01 shr 8) and 0xFF) +
                    ((p10 shr 8) and 0xFF) + ((p11 shr 8) and 0xFF)) / 1020f
                val b = ((p00 and 0xFF) + (p01 and 0xFF) +
                    (p10 and 0xFF) + (p11 and 0xFF)) / 1020f
                val ey = 0.2126f * r + 0.7152f * g + 0.0722f * b
                // BT.709 limited range: Cb/Cr in 16..240 around 128.
                val pb = (b - ey) / 1.8556f
                val pr = (r - ey) / 1.6396f
                val cb = 128.5f + 224f * pb
                val cr = 128.5f + 224f * pr
                val idx = cy * out.cw + cx
                out.u[idx] = cb.coerceIn(16f, 240f).toInt().toByte()
                out.v[idx] = cr.coerceIn(16f, 240f).toInt().toByte()
            }
        }
    }

    /**
     * Colour tags for the encoder MediaFormat, inlined from android.media.MediaFormat
     * so this stays JVM-testable. BT.709 matrix + sRGB transfer + limited range.
     */
    const val COLOR_STANDARD_BT709 = 1
    const val COLOR_TRANSFER_SRGB = 2
    const val COLOR_RANGE_LIMITED = 2
}
