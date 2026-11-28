/*
 * Film (Yousuf-eng-bot) — CPU 3D LUT apply for the video export path. GPLv3.
 * Film modeling powered by spektrafilm (Andrea Volpato).
 *
 * Trilinear lookup on a lattice baked by SpektraEngine.bakeCubeLut with SHAPER_NONE:
 * entries are evenly spaced in LINEAR ProPhoto light, so the export path indexes raw
 * linear values directly (no shaper). Order is blue-fastest, matching the .cube
 * format and the engine's emitter: offset = ((ri*N + gi)*N + bi)*3.
 *
 * Preview and export SHARE this class: both apply the same lattice on the CPU
 * (VideoExport.colorizedArgb), so the scrubbed preview is pixel-identical to the
 * export, with no second GL context and lookup math that is unit-testable on the JVM.
 */
package com.spectrafilm.app.video

internal class TrilinearLut(val size: Int, val rgb: FloatArray) {
    init {
        require(size in 2..256) { "implausible LUT size $size" }
        require(rgb.size == size * size * size * 3) {
            "LUT data ${rgb.size} != ${size}^3*3"
        }
    }

    companion object {
        /**
         * Parse Adobe/Resolve `.cube` text as emitted by bakeCubeLut (same grammar the
         * GPU preview's CubeLut.parse accepts). Returns null on any malformed input.
         */
        fun parse(cube: String): TrilinearLut? = runCatching {
            var size = -1
            val vals = ArrayList<Float>(35937 * 3)
            for (raw in cube.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                when {
                    line.startsWith("LUT_3D_SIZE") ->
                        size = line.substringAfter("LUT_3D_SIZE").trim().toInt()
                    line.startsWith("TITLE") || line.startsWith("DOMAIN_") ||
                        line.startsWith("LUT_1D_SIZE") -> { /* metadata: ignore */ }
                    else -> {
                        val p = line.split(Regex("\\s+"))
                        if (p.size >= 3) {
                            vals.add(p[0].toFloat())
                            vals.add(p[1].toFloat())
                            vals.add(p[2].toFloat())
                        }
                    }
                }
            }
            if (size < 2 || vals.size != size * size * size * 3) return null
            TrilinearLut(size, vals.toFloatArray())
        }.getOrNull()
    }

    /**
     * Trilinear lookup, allocation-free. [r]/[g]/[b] are LINEAR ProPhoto values (the
     * LUT's input domain); values outside [0,1] clamp to the lattice edge. The result
     * (sRGB-encoded display RGB, per the bake's IoParams) is written to [dst] at [di].
     */
    fun apply(r: Float, g: Float, b: Float, dst: FloatArray, di: Int) {
        val n = size
        val last = n - 1
        val fr = r.coerceIn(0f, 1f) * last
        val fg = g.coerceIn(0f, 1f) * last
        val fb = b.coerceIn(0f, 1f) * last
        val ri = fr.toInt().coerceIn(0, last - 1)
        val gi = fg.toInt().coerceIn(0, last - 1)
        val bi = fb.toInt().coerceIn(0, last - 1)
        val tr = fr - ri
        val tg = fg - gi
        val tb = fb - bi

        for (k in 0 until 3) {
            val b000 = ((ri * n + gi) * n + bi) * 3
            val b100 = (((ri + 1) * n + gi) * n + bi) * 3
            val b010 = ((ri * n + (gi + 1)) * n + bi) * 3
            val b110 = (((ri + 1) * n + (gi + 1)) * n + bi) * 3
            val b001 = ((ri * n + gi) * n + (bi + 1)) * 3
            val b101 = (((ri + 1) * n + gi) * n + (bi + 1)) * 3
            val b011 = ((ri * n + (gi + 1)) * n + (bi + 1)) * 3
            val b111 = (((ri + 1) * n + (gi + 1)) * n + (bi + 1)) * 3

            val c00 = rgb[b000 + k] + (rgb[b100 + k] - rgb[b000 + k]) * tr
            val c10 = rgb[b010 + k] + (rgb[b110 + k] - rgb[b010 + k]) * tr
            val c01 = rgb[b001 + k] + (rgb[b101 + k] - rgb[b001 + k]) * tr
            val c11 = rgb[b011 + k] + (rgb[b111 + k] - rgb[b011 + k]) * tr
            val c0 = c00 + (c10 - c00) * tg
            val c1 = c01 + (c11 - c01) * tg
            dst[di + k] = c0 + (c1 - c0) * tb
        }
    }
}
