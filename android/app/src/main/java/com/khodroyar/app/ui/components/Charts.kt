package com.khodroyar.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khodroyar.app.core.analytics.Analytics
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared chart primitives for KhodroYar — no external charting library.
 * All interaction is Compose-native (tap / selection / soft animation).
 */

enum class TrendMetric(val label: String, val unit: String) {
    INCOME("درآمد", "تومان"),
    KM("کیلومتر", "کیلومتر"),
    HOURS("ساعت کارکرد", "ساعت"),
    COUNT("تعداد سرویس", "سرویس"),
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

/* ---------------------------------------------------------------- bar chart */

@Composable
fun TrendBarChart(
    points: List<Analytics.TrendPoint>,
    metric: TrendMetric,
    modifier: Modifier = Modifier,
    barHeight: androidx.compose.ui.unit.Dp = 160.dp,
    masked: Boolean = false,
) {
    val values = points.map { it.metricValue(metric) }
    val max = values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
    var selected by remember { mutableStateOf<Int?>(null) }
    val primary = MaterialTheme.colorScheme.primary
    val mutedBar = MaterialTheme.colorScheme.outlineVariant
    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLowest

    Column(modifier.fillMaxWidth()) {
        selected?.let { idx ->
            if (idx in values.indices) {
                ChartTooltip(
                    title = points[idx].label,
                    value = if (masked) "•••" else formatMetric(values[idx], metric) + " " + metric.unit,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(surfaceLow)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            points.forEachIndexed { index, _ ->
                val raw = values[index]
                val fraction = (raw / max).coerceIn(0.0, 1.0).toFloat()
                val animated by animateFloatAsState(
                    targetValue = fraction,
                    animationSpec = tween(520, easing = FastOutSlowInEasing),
                    label = "bar$index",
                )
                val isSel = selected == index
                val isLast = index == points.lastIndex
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { selected = if (isSel) null else index },
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(if (isSel || isLast) 0.88f else 0.72f)
                            .height((barHeight.value * 0.78f * animated).dp.coerceAtLeast(4.dp))
                            .shadow(if (isSel) 6.dp else 0.dp, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                            .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                            .then(
                                if (raw <= 0.0) {
                                    Modifier.background(mutedBar)
                                } else {
                                    Modifier.background(
                                        Brush.verticalGradient(
                                            listOf(
                                                primary.copy(alpha = if (isSel) 1f else 0.95f),
                                                primary.copy(alpha = if (isSel) 0.75f else 0.45f),
                                            )
                                        )
                                    )
                                }
                            ),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            points.forEachIndexed { index, point ->
                Text(
                    point.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected == index || index == points.lastIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Honest empty state: shown instead of inventing numbers we do not have. */
@Composable
fun InsufficientData(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

/* ---------------------------------------------------------------- line chart */

/**
 * Smooth interactive line chart (Catmull-Rom → cubic Bézier).
 * Tap near a point to select it and show a floating tooltip.
 */
@Composable
fun SmoothLineChart(
    labels: List<String>,
    values: List<Double>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 210.dp,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    markerColor: Color = MaterialTheme.colorScheme.secondary,
    markers: Set<Int> = emptySet(),
    masked: Boolean = false,
    goalValue: Double? = null,
    valueText: (Double) -> String = { Analytics.formatNumber(it) },
) {
    if (values.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val goalColor = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.surface
    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLowest
    val calloutStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
    val dataMax = values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
    val maxValue = maxOf(dataMax, goalValue?.takeIf { it > 0.0 } ?: 0.0).takeIf { it > 0.0 } ?: 1.0
    val maxIndex = values.indices.maxByOrNull { values[it] } ?: 0
    var selected by remember(labels, values) { mutableStateOf<Int?>(null) }

    val reveal = remember { Animatable(0f) }
    LaunchedEffect(values) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(640, easing = FastOutSlowInEasing))
    }

    Column(modifier.fillMaxWidth()) {
        selected?.let { idx ->
            if (idx in values.indices) {
                ChartTooltip(
                    title = labels.getOrElse(idx) { "" },
                    value = if (masked) "•••" else valueText(values[idx]),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(16.dp))
                .background(surfaceLow)
                .padding(4.dp),
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(values, labels) {
                        detectTapGestures { tap ->
                            val topPad = 28f
                            val bottomPad = 14f
                            val sidePad = 14f
                            val usableW = (size.width - sidePad * 2).coerceAtLeast(1f)
                            val usableH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
                            val step = if (values.size > 1) usableW / (values.size - 1) else 0f
                            fun px(i: Int) = sidePad + step * i
                            fun py(v: Double): Float {
                                val f = (v / maxValue).coerceIn(0.0, 1.0).toFloat()
                                return topPad + usableH * (1f - f)
                            }
                            var best = -1
                            var bestDist = Float.MAX_VALUE
                            values.indices.forEach { i ->
                                val dx = tap.x - px(i)
                                val dy = tap.y - py(values[i])
                                val d = hypot(dx, dy)
                                if (d < bestDist) {
                                    bestDist = d
                                    best = i
                                }
                            }
                            selected = if (best >= 0 && bestDist < 56.dp.toPx()) {
                                if (selected == best) null else best
                            } else {
                                null
                            }
                        }
                    },
            ) {
                val topPad = 28f
                val bottomPad = 14f
                val sidePad = 14f
                val usableW = (size.width - sidePad * 2).coerceAtLeast(1f)
                val usableH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
                val step = if (values.size > 1) usableW / (values.size - 1) else 0f
                val progress = reveal.value

                fun px(i: Int) = sidePad + step * i
                fun py(v: Double): Float {
                    val f = (v / maxValue).coerceIn(0.0, 1.0).toFloat()
                    return topPad + usableH * (1f - f)
                }

                // horizontal guides
                for (k in 0..3) {
                    val y = topPad + usableH * (k / 3f)
                    drawLine(
                        color = grid,
                        start = Offset(sidePad, y),
                        end = Offset(size.width - sidePad, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                // goal dashed line
                val goal = goalValue
                if (goal != null && goal > 0.0) {
                    val gy = py(goal)
                    val dash = 8.dp.toPx()
                    var x = sidePad
                    while (x < size.width - sidePad) {
                        val x2 = (x + dash).coerceAtMost(size.width - sidePad)
                        drawLine(
                            color = goalColor.copy(alpha = 0.9f),
                            start = Offset(x, gy),
                            end = Offset(x2, gy),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        x += dash * 1.9f
                    }
                    val goalLabel = if (masked) "هدف" else "هدف " + valueText(goal)
                    val gLayout = measurer.measure(text = goalLabel, style = calloutStyle)
                    val gx = (size.width - sidePad - gLayout.size.width - 4f).coerceAtLeast(sidePad)
                    val gly = (gy - gLayout.size.height - 6f).coerceAtLeast(2f)
                    drawText(gLayout, color = goalColor, topLeft = Offset(gx, gly))
                }

                val pts = values.indices.map { Offset(px(it), py(values[it])) }

                val line = Path()
                line.moveTo(pts.first().x, pts.first().y)
                if (pts.size == 1) {
                    line.lineTo(pts.first().x, pts.first().y)
                } else {
                    for (i in 0 until pts.size - 1) {
                        val p0 = pts[if (i == 0) 0 else i - 1]
                        val p1 = pts[i]
                        val p2 = pts[i + 1]
                        val p3 = pts[if (i + 2 > pts.size - 1) pts.size - 1 else i + 2]
                        val c1x = p1.x + (p2.x - p0.x) / 6f
                        val c1y = p1.y + (p2.y - p0.y) / 6f
                        val c2x = p2.x - (p3.x - p1.x) / 6f
                        val c2y = p2.y - (p3.y - p1.y) / 6f
                        line.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
                    }
                }

                val area = Path()
                area.addPath(line)
                area.lineTo(pts.last().x, topPad + usableH)
                area.lineTo(pts.first().x, topPad + usableH)
                area.close()
                drawPath(
                    path = area,
                    brush = Brush.verticalGradient(
                        listOf(
                            lineColor.copy(alpha = 0.32f * progress),
                            lineColor.copy(alpha = 0.08f * progress),
                            lineColor.copy(alpha = 0f),
                        ),
                        startY = topPad,
                        endY = topPad + usableH,
                    ),
                )
                drawPath(
                    path = line,
                    color = lineColor.copy(alpha = 0.25f + 0.75f * progress),
                    style = Stroke(
                        width = 3.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )

                pts.forEachIndexed { i, p ->
                    val isEdge = i == 0 || i == pts.lastIndex || i == maxIndex
                    val isMarker = markers.contains(i)
                    val isSel = selected == i
                    val r = when {
                        isSel -> 8.dp.toPx()
                        isMarker -> 6.dp.toPx()
                        isEdge -> 5.dp.toPx()
                        else -> 3.5.dp.toPx()
                    } * (0.55f + 0.45f * progress)
                    if (isSel) {
                        drawCircle(color = lineColor.copy(alpha = 0.18f), radius = r * 2.2f, center = p)
                    }
                    drawCircle(
                        color = if (isMarker) markerColor else lineColor,
                        radius = r,
                        center = p,
                    )
                    drawCircle(color = surface, radius = r * 0.42f, center = p)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.runtime.CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Ltr,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                labels.forEachIndexed { index, label ->
                    Text(
                        label,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                selected = if (selected == index) null else index
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            selected == index -> lineColor
                            markers.contains(index) -> markerColor
                            index == labels.lastIndex -> onSurface
                            else -> muted
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
fun TrendLineChart(
    points: List<Analytics.TrendPoint>,
    metric: TrendMetric,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
    goalValue: Double? = null,
) {
    SmoothLineChart(
        labels = points.map { it.label },
        values = points.map { it.metricValue(metric) },
        modifier = modifier,
        masked = masked,
        goalValue = goalValue,
        valueText = { formatMetric(it, metric) },
    )
}

/* ---------------------------------------------------------------- interactive donut */

data class DonutSlice(
    val id: String,
    val label: String,
    val value: Double,
    val color: Color,
)

/**
 * Interactive donut: tap ring or legend to select a slice.
 * Center shows label, value, percent, and total.
 */
@Composable
fun InteractiveDonutChart(
    slices: List<DonutSlice>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    centerTitle: String = "",
    centerSubtitle: String = "",
    size: androidx.compose.ui.unit.Dp = 236.dp,
) {
    val total = slices.sumOf { it.value.coerceAtLeast(0.0) }.coerceAtLeast(0.0001)
    val selected = slices.firstOrNull { it.id == selectedId }
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLowest

    val reveal = remember { Animatable(0f) }
    LaunchedEffect(slices.map { it.id to it.value }) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    val selBoost by animateFloatAsState(
        targetValue = if (selectedId != null) 1f else 0f,
        animationSpec = tween(280),
        label = "donutSel",
    )

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .shadow(8.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(surfaceLow),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(slices, selectedId, total) {
                        detectTapGestures { tap ->
                            val inputWidth = this.size.width.toFloat()
                            val inputHeight = this.size.height.toFloat()
                            val cx = inputWidth / 2f
                            val cy = inputHeight / 2f
                            val dx = tap.x - cx
                            val dy = tap.y - cy
                            val dist = sqrt(dx * dx + dy * dy)
                            val outer = min(inputWidth, inputHeight) / 2f
                            val stroke = outer * 0.28f
                            val inner = outer - stroke - 6.dp.toPx()
                            if (dist < inner * 0.55f || dist > outer - 2.dp.toPx()) {
                                // center / outside — deselect
                                if (selectedId != null) onSelect("")
                                return@detectTapGestures
                            }
                            // angle: -90° is top, clockwise positive like drawArc
                            var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            // atan2: 0 = east, convert to start at top (-90)
                            deg = (deg + 90f + 360f) % 360f
                            var start = 0f
                            for (slice in slices) {
                                val sweep = ((slice.value.coerceAtLeast(0.0) / total) * 360.0).toFloat()
                                if (deg >= start && deg < start + sweep) {
                                    onSelect(if (slice.id == selectedId) "" else slice.id)
                                    return@detectTapGestures
                                }
                                start += sweep
                            }
                        }
                    },
            ) {
                val canvasMinDimension = this.size.minDimension
                val strokeBase = canvasMinDimension * 0.28f
                val radiusPad = strokeBase / 2f + 6.dp.toPx()
                val diam = canvasMinDimension - radiusPad * 2
                val topLeft = Offset(radiusPad, radiusPad)
                val arcSize = Size(diam, diam)
                var start = -90f
                val progress = reveal.value
                slices.forEach { slice ->
                    val fullSweep = ((slice.value.coerceAtLeast(0.0) / total) * 360.0).toFloat()
                    val sweep = fullSweep * progress
                    val isSel = slice.id == selectedId
                    val gap = 2.8f
                    val strokeW = strokeBase * if (isSel) (1f + 0.12f * selBoost) else 1f
                    val inset = if (isSel) 0f else 3.dp.toPx()
                    val alpha = when {
                        selectedId == null -> 1f
                        isSel -> 1f
                        else -> 0.38f
                    }
                    drawArc(
                        color = slice.color.copy(alpha = alpha),
                        startAngle = start + gap / 2f,
                        sweepAngle = (sweep - gap).coerceAtLeast(0.4f),
                        useCenter = false,
                        topLeft = Offset(topLeft.x + inset, topLeft.y + inset),
                        size = Size(arcSize.width - inset * 2, arcSize.height - inset * 2),
                        style = Stroke(width = strokeW, cap = StrokeCap.Butt),
                    )
                    start += fullSweep
                }
                // inner disc
                drawCircle(
                    color = surface,
                    radius = (diam / 2f) - strokeBase * 0.55f,
                )
            }

            // center labels
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 28.dp),
            ) {
                if (selected != null && selected.id != "empty") {
                    Text(
                        selected.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = selected.color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        Analytics.formatNumber(selected.value),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val pct = ((selected.value / total) * 100.0)
                    Text(
                        String.format("%.1f%%", pct),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                } else {
                    if (centerTitle.isNotBlank()) {
                        Text(
                            centerTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = muted,
                            maxLines = 1,
                        )
                    }
                    Text(
                        centerSubtitle.ifBlank { Analytics.formatNumber(total) },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    if (centerSubtitle.isNotBlank() && slices.none { it.id == "empty" }) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "مجموع " + Analytics.formatNumber(total),
                            style = MaterialTheme.typography.labelSmall,
                            color = muted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Legend — tappable chips
        slices.chunked(3).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                row.forEach { slice ->
                    val selectedSlice = slice.id == selectedId
                    val pct = ((slice.value / total) * 100.0)
                    Surface(
                        modifier = Modifier.clickable {
                            onSelect(if (selectedSlice) "" else slice.id)
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = if (selectedSlice) {
                            slice.color.copy(alpha = 0.20f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        border = BorderStroke(
                            1.dp,
                            slice.color.copy(alpha = if (selectedSlice) 0.95f else 0.35f),
                        ),
                        shadowElevation = if (selectedSlice) 2.dp else 0.dp,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(slice.color),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                slice.label,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selectedSlice) slice.color else onSurface,
                                fontWeight = if (selectedSlice) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (selectedSlice && slice.id != "empty") {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    String.format("%.0f%%", pct),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = muted,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- tooltip */

@Composable
private fun ChartTooltip(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 1,
            )
        }
    }
}
