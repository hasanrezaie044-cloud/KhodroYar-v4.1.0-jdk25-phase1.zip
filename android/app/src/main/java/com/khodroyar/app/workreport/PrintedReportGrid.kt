package com.khodroyar.app.workreport

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

internal data class GridRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
internal data class CompanyGridRow(val cells: Map<CompanyReportTemplate.Column, GridRect>, val reliable: Boolean, val description: GridRect)

/**
 * Bounded, local-contrast line detection. Only printed lines establish cells;
 * no month, amount, row count or numeric value is used to invent geometry.
 * Kept independent of Android so skew, shadows and missing lines are testable.
 */
internal class ReportInk(val width: Int, val height: Int, val ink: BooleanArray) {
    init { require(width > 0 && height > 0 && width.toLong() * height == ink.size.toLong() && ink.size <= 2_000_000) }

    private fun peaks(counts: IntArray, minimum: Float, join: Int = 2): List<Int> {
        val groups = mutableListOf<MutableList<Int>>()
        for (i in counts.indices) if (counts[i] >= minimum) {
            if (groups.isEmpty() || i - groups.last().last() > join) groups += mutableListOf(i) else groups.last() += i
        }
        return groups.map { group -> (group.sumOf { it.toDouble() * counts[it] } / group.sumOf { counts[it] }).roundToInt() }
    }

    fun skewDegrees(checkCancelled: () -> Unit = {}): Float {
        val points = ink.indices.filter { ink[it] }.toIntArray()
        if (points.isEmpty()) return 0f
        fun score(degrees: Float): Double {
            checkCancelled()
            val slope = tan(Math.toRadians(degrees.toDouble()))
            val pad = (width * .1).roundToInt() + 4
            val votes = IntArray(height + 2 * pad)
            for (point in points) {
                val y = (point / width - slope * (point % width)).roundToInt() + pad
                if (y in votes.indices) votes[y]++
            }
            val thick = IntArray(votes.size) { i -> (maxOf(0, i - 1)..minOf(votes.lastIndex, i + 1)).sumOf { votes[it] } }
            val candidates = peaks(thick, width * .55f, 3)
            if (candidates.size < 5) return 0.0
            return candidates.sumOf { (thick[it] - width * .45).coerceAtLeast(0.0) }
        }
        val zero = score(0f)
        var best = 0f; var bestScore = zero
        for (n in -16..16) {
            val angle = n * .25f; val candidate = score(angle)
            if (candidate > bestScore) { best = angle; bestScore = candidate }
        }
        val coarse = best
        for (n in -4..4) {
            val angle = coarse + n * .05f; val candidate = score(angle)
            if (candidate > bestScore) { best = angle; bestScore = candidate }
        }
        return if (abs(best) >= .15f && bestScore > zero * 1.12) best else 0f
    }

    fun companyRows(header: CompanyReportTemplate.Header, checkCancelled: () -> Unit = {}): List<CompanyGridRow> {
        val columns = header.columns
        val hours = columns[CompanyReportTemplate.Column.HOURS] ?: return emptyList()
        val date = columns[CompanyReportTemplate.Column.DATE] ?: return emptyList()
        val left = ((hours - .035f).coerceAtLeast(0f) * width).toInt()
        val right = ((date + .08f).coerceAtMost(1f) * width).toInt()
        if (right <= left) return emptyList()
        val counts = IntArray(height) { y -> (left until right).count { x -> ink[y * width + x] } }
        val lines = peaks(counts, (right - left) * .50f)
        val first = lines.indexOfFirst { it.toFloat() / height >= header.bottom - .008f && it.toFloat() / height > header.top + .008f }
        if (first < 0 || lines[first].toFloat() / height > header.bottom + .035f) return emptyList()
        val subsequent = lines.drop(first)
        val gaps = subsequent.zipWithNext().map { (a, b) -> b - a }.filter { it > height * .005f && it < height * .12f }.sorted()
        if (gaps.size < 2) return emptyList()
        val pitch = gaps[gaps.size / 2]
        val bars = mutableListOf(subsequent.first())
        for (y in subsequent.drop(1)) {
            if (y - bars.last() > pitch * 2.4f) break
            if (y - bars.last() >= pitch * .45f) bars += y
        }
        if (bars.size < 3) return emptyList()
        fun vertical(top: Int, bottom: Int): List<Int> {
            val a = (top + 2).coerceAtMost(bottom - 1); val b = (bottom - 2).coerceAtLeast(a + 1)
            val projection = IntArray(width) { x -> (a until b).count { y -> ink[y * width + x] } }
            return peaks(projection, (b - a) * .65f)
        }
        // The image edge is a real clipping boundary for a cropped outer cell,
        // not a guessed continuation. Never invent unseen ordinals/other cells.
        fun boundaries(top: Int, bottom: Int) = (listOf(0) + vertical(top, bottom) + (width - 1)).distinct().sorted()
        val reference = boundaries(bars[0], bars[1])
        fun cellIndex(x: Float) = reference.zipWithNext().indexOfFirst { (l, r) -> x * width > l && x * width < r }
        val dateIndex = cellIndex(date)
        val kmIndex = cellIndex(columns.getValue(CompanyReportTemplate.Column.KM))
        val hoursIndex = cellIndex(hours)
        // Fixed template order, calibrated to actual lines, not hard-coded x coordinates.
        if (dateIndex < 3 || dateIndex - kmIndex != 3 || kmIndex - hoursIndex != 1) return emptyList()
        val indices = mapOf(CompanyReportTemplate.Column.DATE to dateIndex, CompanyReportTemplate.Column.ORIGIN to dateIndex - 1,
            CompanyReportTemplate.Column.DESTINATION to dateIndex - 2, CompanyReportTemplate.Column.KM to kmIndex,
            CompanyReportTemplate.Column.HOURS to hoursIndex) +
            if (columns.containsKey(CompanyReportTemplate.Column.ORDINAL) && dateIndex + 2 < reference.size)
                mapOf(CompanyReportTemplate.Column.ORDINAL to dateIndex + 1) else emptyMap()
        if (columns.any { (column, x) -> indices[column]?.let { cellIndex(x) != it } == true }) return emptyList()
        return bars.zipWithNext().map { (top, bottom) ->
            checkCancelled()
            val vertical = boundaries(top, bottom)
            val rects = indices.mapNotNull { (column, index) ->
                val l = vertical.minByOrNull { abs(it - reference[index]) } ?: return@mapNotNull null
                val r = vertical.minByOrNull { abs(it - reference[index + 1]) } ?: return@mapNotNull null
                if (abs(l - reference[index]) > width * .025f || abs(r - reference[index + 1]) > width * .025f || r - l < width * .012f) return@mapNotNull null
                column to GridRect((l + 3f) / width, (top + 3f) / height, (r - 3f) / width, (bottom - 3f) / height)
            }.toMap()
            CompanyGridRow(rects, bottom - top in (pitch * .55f).toInt()..(pitch * 1.6f).toInt() && rects.size == indices.size,
                GridRect((reference[kmIndex + 1] + 3f) / width, (top + 3f) / height, (reference[dateIndex + 1] - 3f) / width, (bottom - 3f) / height))
        }
    }

    companion object {
        fun fromGrey(width: Int, height: Int, grey: ByteArray, checkCancelled: () -> Unit = {}): ReportInk {
            require(width > 0 && height > 0 && width.toLong() * height == grey.size.toLong() && grey.size <= 2_000_000)
            val stride = width + 1
            val integral = IntArray(stride * (height + 1))
            for (y in 0 until height) {
                checkCancelled()
                var row = 0
                for (x in 0 until width) {
                    row += grey[y * width + x].toInt() and 255
                    integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
                }
            }
            val radius = maxOf(8, width / 65)
            val ink = BooleanArray(grey.size)
            for (y in 0 until height) {
                checkCancelled()
                val t = maxOf(0, y - radius); val b = minOf(height, y + radius + 1)
                for (x in 0 until width) {
                    val l = maxOf(0, x - radius); val r = minOf(width, x + radius + 1)
                    val sum = integral[b * stride + r] - integral[t * stride + r] - integral[b * stride + l] + integral[t * stride + l]
                    ink[y * width + x] = (grey[y * width + x].toInt() and 255) < sum.toFloat() / ((b - t) * (r - l)) - 12f
                }
            }
            return ReportInk(width, height, ink)
        }
    }
}
