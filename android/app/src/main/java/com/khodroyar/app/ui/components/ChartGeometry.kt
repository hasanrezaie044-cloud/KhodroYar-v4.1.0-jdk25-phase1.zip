package com.khodroyar.app.ui.components

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Display-only geometry. Never changes a stored amount or an Analytics/export calculation. */
internal object ChartGeometry {
    fun finite(value: Double) = if (value.isFinite() && abs(value) >= 1e-100) value.coerceIn(-1e100, 1e100) else 0.0

    data class Scale(val min: Double, val max: Double, val step: Double) {
        fun fraction(value: Double) = ((finite(value) - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
        val ticks: List<Double> get() = (0..((max - min) / step).roundToInt().coerceIn(1, 12)).map { min + step * it }
    }

    /** A true zero baseline, readable steps, valid for all-zero, signed and one-point series. */
    fun scale(values: List<Double>, goal: Double? = null): Scale {
        val all = values.map(::finite) + listOfNotNull(goal?.takeIf { it.isFinite() }?.let(::finite)) + 0.0
        val low = all.minOrNull() ?: 0.0
        val high = all.maxOrNull() ?: 0.0
        if (low == high) return Scale(0.0, 1.0, 0.25)
        val raw = (high - low) / 4.0
        val magnitude = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= raw } * magnitude
        return Scale(floor(low / step) * step, ceil(high / step) * step, step)
    }

    /** Chronological index zero starts at the reading edge in both draw and hit-test. */
    fun xFraction(index: Int, count: Int, rtl: Boolean): Float {
        val f = if (count <= 1) 0.5f else index.coerceIn(0, count - 1).toFloat() / (count - 1)
        return if (rtl) 1f - f else f
    }

    fun nearestIndex(fraction: Float, count: Int, rtl: Boolean): Int? {
        if (count <= 0 || !fraction.isFinite()) return null
        val f = fraction.coerceIn(0f, 1f).let { if (rtl) 1f - it else it }
        return (f * (count - 1)).roundToInt().coerceIn(0, count - 1)
    }

    /** Divide by the largest item first so summation cannot overflow. Invalid/negative slices have no area. */
    fun shares(values: List<Double>): List<Double> {
        val clean = values.map { finite(it).coerceAtLeast(0.0) }
        val max = clean.maxOrNull() ?: 0.0
        if (max <= 0.0) return clean.map { 0.0 }
        val scaled = clean.map { it / max }
        val total = scaled.sum()
        return scaled.map { it / total }
    }

    /** Ring is clockwise from twelve o'clock. The centre and outside never select a slice. */
    fun ringIndex(dx: Float, dy: Float, radius: Float, stroke: Float, shares: List<Double>): Int? {
        if (radius <= 0 || stroke <= 0) return null
        val distance = hypot(dx, dy)
        if (!distance.isFinite() || distance < radius - stroke / 2 || distance > radius + stroke / 2) return null
        val angle = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 450.0) % 360.0
        var start = 0.0
        shares.forEachIndexed { index, share ->
            val sweep = share * 360.0
            val gap = minOf(3.0, sweep * 0.14)
            if (share > 0 && angle >= start + gap / 2 && angle <= start + sweep - gap / 2) return index
            start += sweep
        }
        return null
    }

    fun visibleLabels(count: Int, maxLabels: Int = 5): Set<Int> {
        if (count <= 0) return emptySet()
        if (count <= maxLabels) return (0 until count).toSet()
        return (0 until maxLabels.coerceAtLeast(2)).map { (it.toDouble() * (count - 1) / (maxLabels.coerceAtLeast(2) - 1)).roundToInt() }.toSet()
    }
}
