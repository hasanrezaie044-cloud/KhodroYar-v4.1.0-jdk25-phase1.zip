package com.khodroyar.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.analytics.Analytics
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** Orbit charts. Native Canvas, finite geometry, real zero axes and short one-shot fades only. */
enum class TrendMetric(val label: String, val unit: String) {
    INCOME("درآمد", "تومان"), KM("کیلومتر", "کیلومتر"), HOURS("ساعت کارکرد", "ساعت"), COUNT("تعداد سرویس", "سرویس"),
}
fun Analytics.TrendPoint.metricValue(metric: TrendMetric): Double = when (metric) {
    TrendMetric.INCOME -> totalIncome.toDouble()
    TrendMetric.KM -> totalKm
    TrendMetric.HOURS -> totalHours
    TrendMetric.COUNT -> count.toDouble()
}
fun formatMetric(value: Double, metric: TrendMetric): String = when (metric) {
    TrendMetric.HOURS -> Analytics.formatHours(value)
    TrendMetric.COUNT -> Analytics.formatNumber(value.toLong())
    else -> Analytics.formatNumber(value)
}

@Composable
fun InsufficientData(message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().heightIn(min = 144.dp).clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.BarChart, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
}

@Composable
fun TrendBarChart(
    points: List<Analytics.TrendPoint>, metric: TrendMetric, modifier: Modifier = Modifier,
    barHeight: Dp = 190.dp, masked: Boolean = false,
) = SeriesChart(points.map { it.label }, points.map { it.metricValue(metric) }, modifier, barHeight,
    MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, emptySet(), masked, null,
    { formatMetric(it, metric) + " " + metric.unit }, bars = true)

/** Smooth segments have horizontal controls inside each interval: they cannot invent peaks/negative dips. */
@Composable
fun SmoothLineChart(
    labels: List<String>, values: List<Double>, modifier: Modifier = Modifier, height: Dp = 220.dp,
    lineColor: Color = MaterialTheme.colorScheme.primary, markerColor: Color = MaterialTheme.colorScheme.secondary,
    markers: Set<Int> = emptySet(), masked: Boolean = false, goalValue: Double? = null,
    valueText: (Double) -> String = { Analytics.formatNumber(it) },
) = SeriesChart(labels, values, modifier, height, lineColor, markerColor, markers, masked, goalValue, valueText)

@Composable
private fun SeriesChart(
    labels: List<String>, values: List<Double>, modifier: Modifier, height: Dp,
    lineColor: Color, markerColor: Color, markers: Set<Int>, masked: Boolean, goalValue: Double?,
    valueText: (Double) -> String, bars: Boolean = false,
) {
    if (masked) {
        InsufficientData(tr("نمودار مالی در حالت حریم خصوصی پنهان است", "Financial chart hidden in privacy mode"), modifier)
        return
    }
    if (values.isEmpty() || values.none { it.isFinite() }) {
        InsufficientData(tr("هنوز داده‌ای برای نمودار وجود ندارد", "No chart data yet"), modifier)
        return
    }
    val data = remember(values) { values.map(ChartGeometry::finite) }
    val scale = remember(data, goalValue) { ChartGeometry.scale(data, goalValue) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var selected by remember(labels, data) { mutableIntStateOf(data.lastIndex) }
    val index = selected.coerceIn(data.indices)
    val label = labels.getOrElse(index) { (index + 1).toString() }
    val title = tr("نمودار روند؛ برای جزئیات نقطه‌ای را لمس کنید", "Trend chart; tap a point for details")
    val previousText = tr("نقطهٔ قبل", "Previous point")
    val nextText = tr("نقطهٔ بعد", "Next point")
    val goalText = tr("هدف", "Goal")
    val markerText = tr("نشان رنگی: ماه دریافت وام", "Coloured marker: loan received")
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerLowest
    val goalColor = MaterialTheme.colorScheme.tertiary
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(data) { reveal.snapTo(0f); reveal.animateTo(1f, tween(360)) }
    val visibleLabels = remember(data.size) { ChartGeometry.visibleLabels(data.size) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Reserved space avoids a layout jump when a user changes selection.
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(valueText(data[index]), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (index in markers) markerColor else lineColor))
        }
        Canvas(Modifier.fillMaxWidth().height(height).semantics {
            contentDescription = title
            stateDescription = "$label: ${valueText(data[index])}"
            customActions = listOf(
                CustomAccessibilityAction(previousText) { selected = (index - 1).coerceAtLeast(0); true },
                CustomAccessibilityAction(nextText) { selected = (index + 1).coerceAtMost(data.lastIndex); true },
            )
        }.pointerInput(data, rtl) {
            detectTapGestures { tap ->
                val axis = 52.dp.toPx()
                val left = if (rtl) 10.dp.toPx() else axis
                val right = size.width - if (rtl) axis else 10.dp.toPx()
                val inset = if (bars) 14.dp.toPx() else 0f
                ChartGeometry.nearestIndex((tap.x - left - inset) / (right - left - 2 * inset).coerceAtLeast(1f), data.size, rtl)?.let { selected = it }
            }
        }) {
            val axis = 52.dp.toPx()
            val left = if (rtl) 10.dp.toPx() else axis
            val right = size.width - if (rtl) axis else 10.dp.toPx()
            val top = 12.dp.toPx()
            val bottom = size.height - 32.dp.toPx()
            val plotW = (right - left).coerceAtLeast(1f)
            val plotH = (bottom - top).coerceAtLeast(1f)
            val barInset = if (bars) 14.dp.toPx() else 0f
            fun px(i: Int) = left + barInset + ChartGeometry.xFraction(i, data.size, rtl) * (plotW - 2 * barInset).coerceAtLeast(1f)
            fun py(v: Double) = bottom - scale.fraction(v) * plotH
            val zeroY = py(0.0)
            scale.ticks.forEach { tick ->
                val y = py(tick)
                drawLine(grid.copy(alpha = if (abs(tick) < scale.step * 0.01) 0.95f else 0.6f), Offset(left, y), Offset(right, y),
                    strokeWidth = 1.dp.toPx(), pathEffect = if (tick == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())))
                val text = measurer.measure(compactAxis(tick), axisStyle)
                drawText(text, color = muted, topLeft = Offset(if (rtl) right + 8.dp.toPx() else left - text.size.width - 8.dp.toPx(), y - text.size.height / 2))
            }
            goalValue?.takeIf { it.isFinite() && it > 0 }?.let { goal ->
                drawLine(goalColor, Offset(left, py(goal)), Offset(right, py(goal)), strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())))
            }
            val selectionX = px(index)
            drawLine(lineColor.copy(alpha = 0.18f), Offset(selectionX, top), Offset(selectionX, bottom), strokeWidth = 1.dp.toPx())
            if (bars) {
                val width = min(28.dp.toPx(), plotW / (data.size + 1) * 0.58f)
                data.forEachIndexed { i, v ->
                    val y = py(v)
                    drawRoundRect(if (i == index) lineColor else lineColor.copy(alpha = 0.24f),
                        Offset(px(i) - width / 2, min(y, zeroY)), Size(width, abs(y - zeroY).coerceAtLeast(2.dp.toPx())), CornerRadius(5.dp.toPx()))
                }
            } else {
                val points = data.indices.map { Offset(px(it), py(data[it])) }
                val line = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.zipWithNext().forEach { (a, b) ->
                        val third = (b.x - a.x) / 3f
                        cubicTo(a.x + third, a.y, b.x - third, b.y, b.x, b.y)
                    }
                }
                val area = Path().apply {
                    addPath(line); lineTo(points.last().x, zeroY); lineTo(points.first().x, zeroY); close()
                }
                drawPath(area, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.18f * reveal.value), lineColor.copy(alpha = 0.015f)), top, bottom))
                drawPath(line, lineColor.copy(alpha = reveal.value), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                points.forEachIndexed { i, p ->
                    val color = if (i in markers) markerColor else lineColor
                    if (i == index) drawCircle(color.copy(alpha = 0.14f), 10.dp.toPx(), p)
                    if (i == index || i in markers || points.size == 1) {
                        drawCircle(surface, 5.5.dp.toPx(), p)
                        drawCircle(color, 3.5.dp.toPx(), p)
                    }
                }
            }
            visibleLabels.forEach { i ->
                val text = measurer.measure(labels.getOrElse(i) { "${i + 1}" }, axisStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = (plotW / 4).toInt().coerceAtLeast(1)))
                drawText(text, color = if (i == index) lineColor else muted,
                    topLeft = Offset((px(i) - text.size.width / 2).coerceIn(left, (right - text.size.width).coerceAtLeast(left)), bottom + 9.dp.toPx()))
            }
        }
        if (data.any { abs(it) >= 1_000.0 }) Text(tr("K هزار · M میلیون · B میلیارد", "K thousand · M million · B billion"),
            style = MaterialTheme.typography.labelSmall, color = muted)
        if (goalValue != null && goalValue > 0 && goalValue.isFinite()) Text("$goalText · ${valueText(goalValue)}",
            style = MaterialTheme.typography.labelSmall, color = goalColor)
        if (markers.isNotEmpty()) Text(markerText, style = MaterialTheme.typography.labelSmall, color = markerColor)
        // Every point remains selectable at a >=48dp target, even when axis labels are thinned.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(data) { i, v ->
                Surface(shape = RoundedCornerShape(10.dp), color = if (i == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(labels.getOrElse(i) { "${i + 1}" }, Modifier.selectable(i == index, role = Role.RadioButton, onClick = { selected = i })
                        .semantics { contentDescription = "${labels.getOrElse(i) { "${i + 1}" }}: ${valueText(v)}" }
                        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelSmall, color = if (i == index) lineColor else muted)
                }
            }
        }
    }
}

private fun compactAxis(value: Double): String {
    val v = abs(value)
    val divisor = when { v >= 1e9 -> 1e9; v >= 1e6 -> 1e6; v >= 1e3 -> 1e3; else -> 1.0 }
    val suffix = when (divisor) { 1e9 -> "B"; 1e6 -> "M"; 1e3 -> "K"; else -> "" }
    return String.format(Locale.ROOT, if (v / divisor >= 10 || v == 0.0) "%.0f%s" else "%.1f%s", value / divisor, suffix)
}

@Composable
fun TrendLineChart(
    points: List<Analytics.TrendPoint>, metric: TrendMetric, modifier: Modifier = Modifier,
    masked: Boolean = false, goalValue: Double? = null,
) = SmoothLineChart(labels = points.map { it.label }, values = points.map { it.metricValue(metric) }, modifier = modifier,
    masked = masked, goalValue = goalValue, valueText = { formatMetric(it, metric) + " " + metric.unit })

data class DonutSlice(val id: String, val label: String, val value: Double, val color: Color)

/** Separated thin arcs + a readable, full-width legend. No fake unit slice for empty data. */
@Composable
fun InteractiveDonutChart(
    slices: List<DonutSlice>, selectedId: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier,
    centerTitle: String = "", centerSubtitle: String = "", size: Dp = 216.dp,
    masked: Boolean = LocalPrivacyMode.current,
) {
    if (masked) {
        InsufficientData(tr("نمودار مالی در حالت حریم خصوصی پنهان است", "Financial chart hidden in privacy mode"), modifier)
        return
    }
    val data = remember(slices) { slices.filter { it.id != "empty" && it.value.isFinite() && it.value > 0 } }
    if (data.isEmpty()) {
        InsufficientData(tr("در این بازه رکوردی ثبت نشده است", "No records in this period"), modifier)
        return
    }
    val shares = remember(data) { ChartGeometry.shares(data.map { it.value }) }
    val total = data.sumOf { it.value }
    val selected = data.firstOrNull { it.id == selectedId }
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val ringDescription = tr("نمودار سهم دسته‌ها؛ جزئیات در فهرست زیر", "Category shares; details in the list below")
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(data) { reveal.snapTo(0f); reveal.animateTo(1f, tween(360)) }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.sizeIn(maxWidth = size, maxHeight = size).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = ringDescription }.pointerInput(data, selectedId) {
                detectTapGestures { tap ->
                    val stroke = 18.dp.toPx()
                    val radius = min(this.size.width, this.size.height) / 2f - stroke
                    val i = ChartGeometry.ringIndex(tap.x - this.size.width / 2f, tap.y - this.size.height / 2f, radius, stroke + 12.dp.toPx(), shares)
                    onSelect(i?.let { if (data[it].id == selectedId) "" else data[it].id } ?: "")
                }
            }) {
                val stroke = 18.dp.toPx()
                val radius = min(this.size.width, this.size.height) / 2f - stroke
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2, radius * 2)
                drawCircle(track, radius, style = Stroke(stroke))
                var start = -90f
                data.forEachIndexed { i, slice ->
                    val sweep = (shares[i] * 360).toFloat()
                    val gap = min(3f, sweep * 0.14f)
                    drawArc(slice.color.copy(alpha = reveal.value * if (selected == null || selected.id == slice.id) 1f else 0.35f),
                        start + gap / 2, (sweep - gap).coerceAtLeast(0f), false, topLeft, arcSize,
                        style = Stroke(if (selected?.id == slice.id) stroke + 4.dp.toPx() else stroke))
                    start += sweep
                }
            }
            Column(Modifier.fillMaxWidth(0.63f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(selected?.label ?: centerTitle, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, textAlign = TextAlign.Center)
                Text(money(selected?.value ?: total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                Text(tr("تومان", "Toman"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (centerSubtitle.isNotBlank()) Text(centerSubtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            data.forEachIndexed { i, slice ->
                val active = selected?.id == slice.id
                Surface(modifier = Modifier.fillMaxWidth().selectable(active, role = Role.RadioButton,
                    onClick = { onSelect(if (active) "" else slice.id) }), shape = RoundedCornerShape(12.dp),
                    color = if (active) slice.color.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp).heightIn(min = 28.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(slice.color))
                        Column(Modifier.weight(1f)) {
                            Text(slice.label, style = MaterialTheme.typography.labelLarge)
                            Text("${percentage(shares[i])}٪", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(money(slice.value), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            maxLines = 2, textAlign = TextAlign.End, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun percentage(share: Double): String = String.format(Locale.ROOT, "%.1f", share * 100).removeSuffix(".0")
