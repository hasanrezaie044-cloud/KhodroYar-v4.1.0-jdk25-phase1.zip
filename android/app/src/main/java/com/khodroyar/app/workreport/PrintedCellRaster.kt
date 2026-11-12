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
