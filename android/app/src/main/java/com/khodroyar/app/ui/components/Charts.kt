package com.khodroyar.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.analytics.Analytics

/**
 * Trend chart.
 *
 * Deliberately built from layout primitives instead of a Canvas: the bar heights are
 * plain fractions of the container, so it is correct at any screen width and font
 * scale, it needs no text measuring, and it inherits RTL ordering for free. No charting
 * dependency was added.
 *
 * Every value comes from the caller's aggregation of stored records. There is no
 * placeholder data path — when there is not enough history the caller renders
 * [InsufficientData] instead.
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

@Composable
fun TrendBarChart(
    points: List<Analytics.TrendPoint>,
    metric: TrendMetric,
    modifier: Modifier = Modifier,
    barHeight: androidx.compose.ui.unit.Dp = 132.dp,
    masked: Boolean = false,
) {
    val values = points.map { it.metricValue(metric) }
    val max = values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
    val chartBrush = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
        )
    )

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(barHeight),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            points.forEachIndexed { index, _ ->
                val raw = values[index]
                val fraction = (raw / max).coerceIn(0.0, 1.0).toFloat()
                val animated by animateFloatAsState(
                    targetValue = fraction,
                    animationSpec = tween(420),
                    label = "bar$index",
                )
                val isLast = index == points.lastIndex
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (masked) "•••" else formatMetric(raw, metric),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isLast) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(if (isLast) 0.82f else 0.66f)
                            // A zero bucket still gets a hairline, so an empty period is
                            // visibly empty rather than simply absent.
                            .height((barHeight.value * animated).dp.coerceAtLeast(3.dp))
                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                            .background(
                                if (raw <= 0.0) Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.outlineVariant,
                                        MaterialTheme.colorScheme.outlineVariant,
                                    )
                                ) else chartBrush
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            points.forEachIndexed { index, point ->
                Text(
                    point.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == points.lastIndex) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
            .heightIn(min = 110.dp)
            .clip(RoundedCornerShape(14.dp))
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
