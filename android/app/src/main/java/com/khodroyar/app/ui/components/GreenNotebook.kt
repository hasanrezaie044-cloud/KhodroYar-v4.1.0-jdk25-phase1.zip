package com.khodroyar.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.khodroyar.app.ui.theme.LocalAppAccents
import com.khodroyar.app.ui.theme.appAccents

/**
 * The app's existing green "notebook" look (5.9.1), extracted from the personnel ID booklet:
 * the hero green gradient, a darker binding spine with gold stitching and rounded corners.
 * Static drawing only: no images, no animation, no new colours.
 */
val NotebookGold = Color(0xFFE2C58B)

/** Text colours on a [NotebookCover]; white/gold on the dark green gradient keeps AA contrast. */
object NotebookInk {
    val primary = Color.White
    val secondary = Color.White.copy(alpha = 0.78f)
    val accent = NotebookGold
}

@Composable
fun NotebookCover(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    border: BorderStroke? = null,
    spine: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val cover = LocalAppAccents.current.heroGradient
    val tap = if (onClick == null) Modifier else Modifier.clip(shape).clickable(onClickLabel = onClickLabel, onClick = onClick)
    Surface(modifier = modifier.then(tap), shape = shape, color = Color.Transparent, border = border) {
        Row(Modifier.background(Brush.linearGradient(cover)).height(IntrinsicSize.Min)) {
            NotebookSpine(Modifier.width(spine).fillMaxHeight())
            Column(Modifier.weight(1f).padding(contentPadding), content = content)
        }
    }
}

/** Binding spine with stitching, as on the personnel booklet. */
@Composable
fun NotebookSpine(modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black.copy(alpha = 0.22f))) {
        Canvas(Modifier.matchParentSize()) {
            val x = size.width / 2
            var y = 8.dp.toPx()
            while (y < size.height - 8.dp.toPx()) {
                drawLine(NotebookGold.copy(alpha = 0.55f), Offset(x, y), Offset(x, y + 5.dp.toPx()), 1.dp.toPx())
                y += 10.dp.toPx()
            }
        }
    }
}

/** A small stat chip that sits on a [NotebookCover]. */
@Composable
fun NotebookStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.10f), modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = NotebookInk.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.titleSmall, color = NotebookInk.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Light green "paper" of a notebook page; text on it uses onPrimaryContainer / primary. */
@Composable
fun notebookPaper(): Color = MaterialTheme.colorScheme.primaryContainer

/** Binding edge colour of a notebook page. */
@Composable
fun notebookBinding(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)

/**
 * Persistent, static marker for a delivered service: a green side accent plus a soft green
 * outer glow. Nothing flashes or animates; the same look is used wherever a service card is shown.
 */
fun Modifier.deliveredGlow(delivered: Boolean, shape: Shape, green: Color): Modifier =
    if (!delivered) this else this.shadow(6.dp, shape, clip = false, ambientColor = green, spotColor = green)

@Composable
fun DeliveredAccent(modifier: Modifier = Modifier) {
    val green = appAccents.positive
    Box(modifier.width(5.dp).fillMaxHeight().background(green, RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)))
}
