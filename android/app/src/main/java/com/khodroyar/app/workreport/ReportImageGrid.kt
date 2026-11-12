package com.khodroyar.app.workreport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One bounded analysis image; no OpenCV, network, camera or extra installation. */
internal object ReportImageGrid {
    fun ink(bitmap: Bitmap, edge: Int = 1000, checkCancelled: () -> Unit = {}): ReportInk {
        val scale = minOf(1f, edge.toFloat() / bitmap.width, 1800f / bitmap.height,
            sqrt(1_800_000f / (bitmap.width.toFloat() * bitmap.height)))
        val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        try {
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            val grey = ByteArray(pixels.size) { i ->
                val p = pixels[i]; ((77 * Color.red(p) + 150 * Color.green(p) + 29 * Color.blue(p)) / 256).toByte()
            }
            return ReportInk.fromGrey(small.width, small.height, grey, checkCancelled)
        } finally { if (small !== bitmap) small.recycle() }
    }

    /** Takes ownership only on success; retains the original on failure. */
    fun deskew(bitmap: Bitmap, checkCancelled: () -> Unit = {}): Bitmap {
        val angle = ink(bitmap, 600, checkCancelled).skewDegrees(checkCancelled)
        if (abs(angle) < .15f) return bitmap
        checkCancelled()
        val radians = Math.toRadians(angle.toDouble())
        val width = (bitmap.width * cos(radians) + bitmap.height * abs(sin(radians))).toFloat()
        val height = (bitmap.height * cos(radians) + bitmap.width * abs(sin(radians))).toFloat()
        val scale = minOf(1f, 3200f / maxOf(width, height), sqrt(6_000_000f / (width * height)))
        val output = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            Canvas(output).apply {
                drawColor(Color.WHITE)
                translate(output.width / 2f, output.height / 2f); scale(scale, scale); rotate(-angle)
                drawBitmap(bitmap, -bitmap.width / 2f, -bitmap.height / 2f, null)
            }
        } catch (e: Throwable) { output.recycle(); throw e }
        bitmap.recycle()
        return output
    }
}
