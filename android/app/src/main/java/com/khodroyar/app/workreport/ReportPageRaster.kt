package com.khodroyar.app.workreport

/** Deterministic illumination correction. No erosion/median filter can erase thin digits/slashes. */
internal object ReportPageRaster {
    const val MAX_PIXELS = 6_000_000
    const val MAX_EDGE = 3200

    /** Bright local paper estimates; interpolate them to avoid tile-edge artefacts. */
    fun illuminated(width: Int, height: Int, pixels: IntArray, checkCancelled: () -> Unit = {}, inPlace: Boolean = false): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong() && pixels.size <= MAX_PIXELS && maxOf(width, height) <= MAX_EDGE)
        val tile = maxOf(32, width / 40)
        val columns = (width + tile - 1) / tile; val rows = (height + tile - 1) / tile
        val histograms = Array(columns * rows) { IntArray(256) }
        fun grey(p: Int) = (77 * ((p ushr 16) and 255) + 150 * ((p ushr 8) and 255) + 29 * (p and 255)) ushr 8
        for (y in 0 until height) {
            checkCancelled()
            for (x in 0 until width) histograms[(y / tile) * columns + x / tile][grey(pixels[y * width + x])]++
        }
        val paper = IntArray(histograms.size) { i ->
            val histogram = histograms[i]; val target = (histogram.sum() * .80f).toInt().coerceAtLeast(1)
            var count = 0; var value = 255
            for (v in histogram.indices) { count += histogram[v]; if (count >= target) { value = v; break } }
            value.coerceAtLeast(100)
        }
        val sorted = paper.sorted()
        if (sorted.last() - sorted.first() < 24 && sorted[sorted.size / 2] >= 225) return if (inPlace) pixels else pixels.copyOf()
        fun at(x: Int, y: Int) = paper[y.coerceIn(0, rows - 1) * columns + x.coerceIn(0, columns - 1)].toFloat()
        val output = if (inPlace) pixels else IntArray(pixels.size)
        for (y in 0 until height) {
            checkCancelled()
            val fy = (y + .5f) / tile - .5f; val ty = kotlin.math.floor(fy).toInt(); val dy = fy - ty
            for (x in 0 until width) {
                val fx = (x + .5f) / tile - .5f; val tx = kotlin.math.floor(fx).toInt(); val dx = fx - tx
                val a = at(tx, ty) * (1 - dx) + at(tx + 1, ty) * dx
                val b = at(tx, ty + 1) * (1 - dx) + at(tx + 1, ty + 1) * dx
                val background = a * (1 - dy) + b * dy
                val original = grey(pixels[y * width + x])
                // Mild gain only. Preserve every original dark pixel and geometry.
                val value = (original * minOf(1.45f, 250f / background)).toInt().coerceIn(0, 255)
                output[y * width + x] = (0xff000000L or (value * 0x010101).toLong()).toInt()
            }
        }
        return output
    }

    /**
     * ROI crop with coordinate-preserving white padding, not resampling. Keep the
     * whole title/month strip. Only actual, closed multi-column rules can supply
     * bounds; otherwise retain the complete page, including unreadable rows.
     */
    fun tableOnly(width: Int, height: Int, pixels: IntArray, table: GridRect?, checkCancelled: () -> Unit = {}, inPlace: Boolean = false): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong() && pixels.size <= MAX_PIXELS && maxOf(width, height) <= MAX_EDGE)
        val result = if (inPlace) pixels else pixels.copyOf()
        if (table == null || listOf(table.left, table.top, table.right, table.bottom).any { !it.isFinite() || it !in 0f..1f } || table.left >= table.right || table.top >= table.bottom) return result
        val left = ((table.left - .015f).coerceAtLeast(0f) * width).toInt()
        val right = ((table.right + .015f).coerceAtMost(1f) * width).toInt()
        val top = ((table.top - .015f).coerceAtLeast(0f) * height).toInt()
        val bottom = ((table.bottom + .015f).coerceAtMost(1f) * height).toInt()
        for (y in top until height) {
            checkCancelled()
            for (x in 0 until width) if (y >= bottom || x < left || x >= right) result[y * width + x] = -1
        }
        return result
    }
}
