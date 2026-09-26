package com.khodroyar.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.ui.theme.AppDimens
import com.khodroyar.app.ui.theme.appAccents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/* ------------------------------------------------------------------ formatting */

/** Thousands separator identical to the legacy `formatNumber`. */
fun money(value: Long): String = Analytics.formatNumber(value)
fun money(value: Double): String = Analytics.formatNumber(value)
fun money(value: Int): String = Analytics.formatNumber(value.toLong())

private val PERSIAN_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
private val ARABIC_DIGITS = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

/** Accepts Persian/Arabic-Indic digits from the keyboard and normalises them. */
fun String.toLatinDigits(): String {
    val sb = StringBuilder(length)
    for (c in this) {
        val p = PERSIAN_DIGITS.indexOf(c)
        val a = ARABIC_DIGITS.indexOf(c)
        when {
            p >= 0 -> sb.append(p)
            a >= 0 -> sb.append(a)
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

fun String.asDouble(): Double =
    toLatinDigits().replace(",", "").replace("٬", "").replace("٫", ".").trim().toDoubleOrNull() ?: 0.0

fun String.asInt(): Int =
    toLatinDigits().replace(",", "").replace("٬", "").trim().toDoubleOrNull()?.toInt() ?: 0

/** Keeps only the digits, so a stored "1500000.0" edits cleanly as money. */
fun String.digitsOnly(): String = toLatinDigits().substringBefore('.').filter { it.isDigit() }

/**
 * Groups a raw digit string in threes: "1000000" -> "1,000,000".
 * Presentation only — the caller always keeps the ungrouped digits in state, so the
 * stored numeric value can never drift.
 */
fun groupDigits(raw: String): String {
    val s = raw.trimStart('0').ifEmpty { if (raw.isEmpty()) "" else "0" }
    if (s.isEmpty()) return ""
    val sb = StringBuilder(s.length + s.length / 3)
    for ((i, ch) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
        sb.append(ch)
    }
    return sb.toString()
}

val CardShape = RoundedCornerShape(20.dp)
val FieldShape = RoundedCornerShape(14.dp)
val TileShape = RoundedCornerShape(18.dp)

/* -------------------------------------------------------------- privacy mode */

/** When on, every amount in the UI renders masked. Restored from the legacy app. */
val LocalPrivacyMode = staticCompositionLocalOf { false }

@Composable
fun amount(value: Long): String = if (LocalPrivacyMode.current) "••••••" else money(value)

@Composable
fun amount(value: Double): String = if (LocalPrivacyMode.current) "••••••" else money(value)

/* --------------------------------------------------------------------- toast */

/** Native stand-in for the legacy ToastContext: one snackbar host, app-wide. */
class ToastController(
    private val host: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    fun show(message: String) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message)
        }
    }
}

val LocalToast = staticCompositionLocalOf { ToastController(SnackbarHostState(), NoopScope) }

private object NoopScope : CoroutineScope {
    override val coroutineContext = kotlinx.coroutines.Dispatchers.Main.immediate
}

@Composable
fun rememberToastController(host: SnackbarHostState): ToastController {
    val scope = rememberCoroutineScope()
    return remember(host, scope) { ToastController(host, scope) }
}

/* ------------------------------------------------------------------ keyboard */

/**
 * Taps outside a field clear focus, which is how the keyboard is dismissed naturally.
 * Applied by the form screens to their scroll container.
 */
@Composable
fun Modifier.clearFocusOnTap(): Modifier {
    val focus = LocalFocusManager.current
    return this.pointerInput(Unit) {
        // detectTapGestures only fires on a down event no child consumed, so tapping a
        // field still focuses it while tapping the page background dismisses the keyboard.
        detectTapGestures(onTap = { focus.clearFocus() })
    }
}

/* ---------------------------------------------------------------- containers */

/**
 * The one card in the app. A visible outline plus a filled container is what the old
 * flat white-on-white cards were missing.
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    color: Color? = null,
    contentPadding: Dp = AppDimens.cardPadding,
    outlined: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = color ?: MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        shadowElevation = 1.dp,
    ) { Column(Modifier.padding(contentPadding), content = content) }
}

/** Card with a gradient fill, for hero blocks. Text colour is the caller's business. */
@Composable
fun GradientCard(
    brush: Brush,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = Color.Transparent,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.background(brush).padding(contentPadding), content = content)
    }
}

/** Section header: accent bar + title, with an optional trailing note. */
@Composable
fun CollapsibleSectionTitle(
    title: String,
    action: String = "",
    icon: ImageVector? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    englishTitle: String? = null,
) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(width = 4.dp, height = 28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(9.dp))
            if (icon != null) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(7.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!englishTitle.isNullOrBlank()) {
                    Text(englishTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (action.isNotBlank()) {
                Text(action, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (expanded) "بستن" else "باز کردن",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SectionTitle(title: String, action: String = "", icon: ImageVector? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(8.dp))
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (action.isNotBlank()) {
            Text(
                action,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Metric tile. Fixed minimum height and single-line, ellipsised value: two tiles side
 * by side are now always the same size no matter how long the number is. This is the
 * fix for the mismatched-square problem, applied everywhere tiles are used.
 */
@Composable
fun MetricCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val tint = accent ?: MaterialTheme.colorScheme.primary
    val base = modifier
        .defaultMinSize(minHeight = if (compact) AppDimens.tileCompactMinHeight else AppDimens.tileMinHeight)
    Surface(
        modifier = if (onClick == null) base else base.clickable(onClick = onClick),
        shape = TileShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = if (compact) 10.dp else 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp)) }
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
            Text(
                value,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Two metric tiles on one balanced row. Every screen uses this instead of ad-hoc Rows. */
@Composable
fun MetricPair(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.gap),
    ) {
        first(Modifier.weight(1f))
        second(Modifier.weight(1f))
    }
}

@Composable
fun EmptyState(title: String, body: String = "", icon: ImageVector, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        ) {
            Icon(
                icon, null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(16.dp).size(26.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun ActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    trailing: String = "",
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val color = tint ?: MaterialTheme.colorScheme.primary
    Surface(
        shape = FieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AppDimens.rowMinHeight)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = color, modifier = Modifier.size(19.dp)) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing.isNotBlank()) {
                Text(
                    trailing,
                    color = color,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        shape = FieldShape,
        color = if (checked) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(
            1.dp,
            if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AppDimens.rowMinHeight)
            .clickable { onChange(!checked) },
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

/** Collapsible block: title + total when closed, full detail when open. */
@Composable
fun Collapsible(
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(AppDimens.cardPadding)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        summary,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

/** Label / value line. The workhorse of every detail card. */
@Composable
fun InfoRow(label: String, value: String, emphasis: Boolean = false, valueColor: Color? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* -------------------------------------------------------------------- fields */

@Composable
fun TextFieldR(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        shape = FieldShape,
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) imeAction else ImeAction.Default),
    )
}

/** Decimal-capable numeric field: kilometres, hours, litres, percentages. */
@Composable
fun NumberField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String = "",
    imeAction: ImeAction = ImeAction.Next,
) {
    val support: (@Composable () -> Unit)? = if (suffix.isBlank()) null else {
        { Text(suffix, style = MaterialTheme.typography.bodySmall) }
    }
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val cleaned = raw.toLatinDigits().filter { it.isDigit() || it == '.' }
            onValue(cleaned)
        },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        singleLine = true,
        shape = FieldShape,
        supportingText = support,
    )
}

/**
 * Money input with live thousands separators.
 *
 * [value] is always the ungrouped digit string, so the numeric value the caller saves
 * is untouched by the formatting; only what the user sees is grouped. The caret is
 * pinned to the end, which is the only stable position once separators are inserted
 * and removed as you type.
 */
@Composable
fun MoneyField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String = "تومان",
    imeAction: ImeAction = ImeAction.Next,
    maxDigits: Int = 15,
) {
    val digits = value.digitsOnly()
    val formatted = groupDigits(digits)
    OutlinedTextField(
        value = TextFieldValue(formatted, TextRange(formatted.length)),
        onValueChange = { new -> onValue(new.text.digitsOnly().take(maxDigits)) },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        singleLine = true,
        shape = FieldShape,
        supportingText = if (suffix.isBlank()) null else {
            { Text(suffix, style = MaterialTheme.typography.bodySmall) }
        },
    )
}

/**
 * Strict digit field with a hard length cap — used by "شماره درخواست".
 * The numeric keyboard opens on focus, letters and symbols can never be entered (they
 * are filtered even when pasted), and the cap is enforced on input rather than
 * validated afterwards.
 */
@Composable
fun DigitsField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxLength: Int = 12,
    showCounter: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
) {
    val digits = value.digitsOnly().take(maxLength)
    OutlinedTextField(
        value = digits,
        onValueChange = { raw -> onValue(raw.digitsOnly().take(maxLength)) },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        singleLine = true,
        shape = FieldShape,
        supportingText = if (!showCounter) null else {
            {
                Text(
                    "حداکثر $maxLength رقم · ${digits.length} رقم وارد شده",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

/**
 * Text field with previously used values offered inline.
 *
 * The dropdown popup this used to open is gone: it floated over the field, moved the
 * page and fought the keyboard. Suggestions are now chips rendered under the field, in
 * the normal layout flow, so nothing jumps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
) {
    val matches = remember(value, suggestions) {
        suggestions.filter { it.isNotBlank() && (value.isBlank() || it.contains(value, true)) && it != value }.take(6)
    }
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValue(if (numeric) it.toLatinDigits().filter { c -> c.isDigit() || c == '.' } else it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            shape = FieldShape,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                imeAction = imeAction,
            ),
        )
        if (matches.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                matches.forEach { s ->
                    SuggestionChip(
                        onClick = { onValue(s) },
                        label = { Text(s, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ selection */

/** Single-choice chip row. Wraps, so it never overflows on a narrow screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { option ->
            val isSelected = selected == option
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option) },
                label = { Text(label(option), maxLines = 1) },
                shape = RoundedCornerShape(12.dp),
                leadingIcon = if (!isSelected) null else {
                    { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

/**
 * Inline single-choice selector: a labelled block of selectable cards laid out in the
 * page, replacing the dialogs and dropdown popups these fields used to open.
 *
 * Nothing floats, nothing steals focus, and the keyboard never covers the choice —
 * which was the whole complaint about the old pickers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> OptionSelector(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionIcon: ((T) -> ImageVector)? = null,
    optionHint: ((T) -> String)? = null,
    columns: Int = 2,
) {
    Column(modifier.fillMaxWidth()) {
        if (label.isNotBlank()) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        val perRow = columns.coerceAtLeast(1)
        options.chunked(perRow).forEach { rowOptions ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowOptions.forEach { option ->
                    OptionCard(
                        text = optionLabel(option),
                        hint = optionHint?.invoke(option) ?: "",
                        icon = optionIcon?.invoke(option),
                        selected = option == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(option) },
                    )
                }
                // Keeps the last row's cards the same width as every other row.
                repeat(perRow - rowOptions.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun OptionCard(
    text: String,
    hint: String,
    icon: ImageVector?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .clip(FieldShape)
            .clickable(onClick = onClick),
        shape = FieldShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, border),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon, null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hint.isNotBlank()) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Outlined.Check, null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Segmented control for 2–4 short options: tabs, ranges, modes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    Text(
                        label(option),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

/* ---------------------------------------------------------------- data table */

/**
 * Compact report table.
 *
 * Column widths are fixed and the table scrolls horizontally, so six numeric columns
 * stay readable on a phone instead of being squeezed into unreadable slivers or
 * pushing the card out of the screen.
 */
data class TableColumn(val title: String, val width: Dp, val numeric: Boolean = true)

@Composable
fun DataTable(
    columns: List<TableColumn>,
    rows: List<List<String>>,
    totalsRow: List<String>? = null,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(scroll)) {
            Column {
                // header
                Row(
                    Modifier
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(vertical = 8.dp),
                ) {
                    columns.forEach { column ->
                        Text(
                            column.title,
                            modifier = Modifier.width(column.width).padding(horizontal = 6.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = if (column.numeric) TextAlign.Center else TextAlign.Start,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                rows.forEachIndexed { index, row ->
                    Row(
                        Modifier
                            .background(
                                if (index % 2 == 1) MaterialTheme.colorScheme.surfaceContainer
                                else MaterialTheme.colorScheme.surfaceContainerLowest
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        columns.forEachIndexed { i, column ->
                            Text(
                                row.getOrElse(i) { "" },
                                modifier = Modifier.width(column.width).padding(horizontal = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = if (column.numeric) TextAlign.Center else TextAlign.Start,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                totalsRow?.let { totals ->
                    Row(
                        Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        columns.forEachIndexed { i, column ->
                            Text(
                                totals.getOrElse(i) { "" },
                                modifier = Modifier.width(column.width).padding(horizontal = 6.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = if (column.numeric) TextAlign.Center else TextAlign.Start,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "برای دیدن ستون‌های بیشتر، جدول را افقی بکشید",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ---------------------------------------------------------------- delta chip */

/** Up/down indicator against a reference value. Returns a muted dash when there is no base. */
@Composable
fun DeltaChip(current: Double, reference: Double, modifier: Modifier = Modifier) {
    if (reference <= 0.0) {
        Text("—", modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val delta = ((current - reference) / reference) * 100.0
    val positive = delta >= 0
    val color = if (positive) appAccents.positive else appAccents.negative
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.13f),
    ) {
        Text(
            (if (positive) "+" else "−") + money(kotlin.math.abs(delta)) + "٪",
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "تأیید",
    dismissLabel: String = "انصراف",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
        shape = MaterialTheme.shapes.extraLarge,
        confirmButton = {
            if (destructive) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    shape = FieldShape,
                ) { Text(confirmLabel) }
            } else {
                Button(onClick = onConfirm, shape = FieldShape) { Text(confirmLabel) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
