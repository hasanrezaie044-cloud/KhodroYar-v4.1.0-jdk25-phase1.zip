package com.khodroyar.app.workreport

/**
 * Three bounded photometric views of the SAME printed cell. No numeric values,
 * dates, neighbours or totals are used as correction hints. Kept pure for tests.
 */
internal object PrintedCellRaster {
    enum class View { GREY, OTSU, CONTRAST }
    const val MAX_PIXELS = 1_000_000

    fun grey(width: Int, height: Int, pixels: IntArray): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong() && pixels.size <= MAX_PIXELS)
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            (77 * ((p ushr 16) and 255) + 150 * ((p ushr 8) and 255) + 29 * (p and 255)) ushr 8
        }
    }
    private fun histogram(grey: IntArray): IntArray = IntArray(256).also { h ->
        require(grey.isNotEmpty() && grey.size <= MAX_PIXELS)
        grey.forEach { require(it in 0..255); h[it]++ }
    }
    fun threshold(grey: IntArray): Int {
        val histogram = histogram(grey)
        val sum = histogram.indices.sumOf { it.toDouble() * histogram[it] }
        var background = 0; var partial = 0.0; var variance = -1.0; var threshold = 128
        for (value in 0..254) {
            background += histogram[value]
            if (background == 0) continue
            val foreground = grey.size - background
            if (foreground == 0) break
            partial += value.toDouble() * histogram[value]
            val difference = partial / background - (sum - partial) / foreground
            val candidate = background.toDouble() * foreground * difference * difference
            if (candidate > variance) { variance = candidate; threshold = value }
        }
        return threshold.coerceIn(40, 210)
    }
    /** Enlargement must not turn a tiny dot/noise into legible full-height print. */
    fun legibleHeight(width: Int, height: Int, grey: IntArray): Boolean {
        require(width > 0 && height > 0 && width.toLong() * height == grey.size.toLong())
        val threshold = threshold(grey)
        var top = height; var bottom = -1; var count = 0
        for (i in grey.indices) if (grey[i] <= threshold) {
            top = minOf(top, i / width); bottom = maxOf(bottom, i / width); count++
        }
        return bottom - top + 1 >= 12 && count < grey.size * .60f
    }
    fun blank(grey: IntArray): Boolean {
        val threshold = threshold(grey)
        return grey.none { it <= threshold }
    }

    /** Remove only rules spanning almost the entire header strip, not letter strokes. */
    fun withoutRules(width: Int, height: Int, grey: IntArray): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == grey.size.toLong())
        val threshold = threshold(grey)
        val rows = BooleanArray(height) { y -> (0 until width).count { x -> grey[y * width + x] <= threshold } >= width * .85f }
        val columns = BooleanArray(width) { x -> (0 until height).count { y -> grey[y * width + x] <= threshold } >= height * .85f }
        return IntArray(grey.size) { i -> if (rows[i / width] || columns[i % width]) 255 else grey[i] }
    }
    /** Local mean threshold; no opening/erosion and no thin-stroke denoising. */
    fun adaptive(width: Int, height: Int, grey: IntArray): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == grey.size.toLong() && grey.size <= MAX_PIXELS)
        val stride = width + 1
        val sums = IntArray(stride * (height + 1))
        for (y in 0 until height) {
            var row = 0
            for (x in 0 until width) { row += grey[y * width + x]; sums[(y + 1) * stride + x + 1] = sums[y * stride + x + 1] + row }
        }
        val radius = maxOf(9, minOf(width, height) / 8)
        return IntArray(grey.size) { i ->
            val x = i % width; val y = i / width
            val l = maxOf(0, x - radius); val r = minOf(width, x + radius + 1)
            val t = maxOf(0, y - radius); val b = minOf(height, y + radius + 1)
            val mean = (sums[b * stride + r] - sums[t * stride + r] - sums[b * stride + l] + sums[t * stride + l]).toFloat() / ((b - t) * (r - l))
            if (grey[i] < (mean - 12f).coerceAtLeast(40f)) 0xff000000.toInt() else -1
        }
    }

    fun pixels(grey: IntArray, view: View): IntArray {
        val h = histogram(grey)
        val threshold = if (view == View.OTSU) threshold(grey) else 0
        fun percentile(fraction: Double): Int {
            val target = (grey.size * fraction).toInt().coerceAtLeast(1)
            var sum = 0
            for (i in h.indices) { sum += h[i]; if (sum >= target) return i }
            return 255
        }
        val low = percentile(.03); val high = percentile(.90)
        return IntArray(grey.size) { i ->
            val value = when (view) {
                View.GREY -> grey[i]
                View.OTSU -> if (grey[i] <= threshold) 0 else 255
                View.CONTRAST -> if (high - low < 12) grey[i] else ((grey[i] - low) * 255 / (high - low)).coerceIn(0, 255)
            }
            (0xff000000L or (value * 0x010101).toLong()).toInt()
        }
    }
}
