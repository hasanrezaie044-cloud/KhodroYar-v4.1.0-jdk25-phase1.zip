package com.khodroyar.app.workreport

import kotlin.math.sqrt

/** Preserve useful print resolution instead of unnecessarily halving a 6.1 MP scan. */
internal data class ReportRasterPlan(val width: Int, val height: Int, val sample: Int, val density: Int) {
    companion object {
        fun of(width: Int, height: Int, maxPixels: Int, maxEdge: Int): ReportRasterPlan {
            require(width > 0 && height > 0 && maxPixels > 0 && maxEdge > 0)
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(width, height), sqrt(maxPixels.toDouble() / (width.toDouble() * height)))
            val w = (width * scale).toInt().coerceAtLeast(1); val h = (height * scale).toInt().coerceAtLeast(1)
            var sample = 1
            while (sample <= (1 shl 28) && maxOf(1, width / (sample * 2)) >= w && maxOf(1, height / (sample * 2)) >= h) sample *= 2
            val density = (1_000_000 * minOf(1.0, w.toDouble() * sample / width, h.toDouble() * sample / height)).toInt().coerceIn(1, 1_000_000)
            return ReportRasterPlan(w, h, sample, density)
        }
    }
}
