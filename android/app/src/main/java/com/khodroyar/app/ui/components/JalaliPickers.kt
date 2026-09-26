package com.khodroyar.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.TimeRates

/**
 * Interactive Jalali calendar surface and the app-wide date / time pickers.
 *
 * `utils/jalali.ts` was the single source of truth in the legacy app; its native port
 * [Jalali] is the single source of truth here. No second conversion implementation
 * exists anywhere in the project, and nothing here touches the network — month
 * lengths, weekday offsets and leap years all come from [Jalali].
 */

/* ----------------------------------------------------------------- month grid */

@Composable
fun JalaliMonthGrid(
    year: Int,
    month: Int,
    selected: String?,
    today: String = Jalali.todayString(),
    hasData: (String) -> Boolean = { false },
    isHoliday: (String) -> Boolean = { false },
    onSelect: (String) -> Unit,
) {
    val length = Jalali.monthLength(year, month)
    val firstDow = Jalali.dayOfWeek(year, month, 1)
    val cells: List<Int?> = List(firstDow) { null } + (1..length).toList()

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Jalali.WEEKDAY_NAMES.forEachIndexed { index, name ->
                Text(
                    name.take(1),
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = if (index >= 5) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                for (index in 0 until 7) {
                    val day = week.getOrNull(index)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (day == null) {
                            Spacer(Modifier.size(40.dp))
                        } else {
                            val date = Jalali.format(year, month, day)
                            DayCell(
                                day = day,
                                isSelected = date == selected,
                                isToday = date == today,
                                isWeekend = Jalali.dayOfWeek(year, month, day) >= 5,
                                isHoliday = isHoliday(date),
                                hasData = hasData(date),
                                onClick = { onSelect(date) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    isSelected: Boolean,
    isToday: Boolean,
    isWeekend: Boolean,
    isHoliday: Boolean,
    hasData: Boolean,
    onClick: () -> Unit,
) {
    // Thursday / Friday stay red, and official + manual holidays are red too:
    // exactly the legacy colouring rule.
    val red = isWeekend || isHoliday
    val container = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isToday -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val content = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isToday -> MaterialTheme.colorScheme.onPrimaryContainer
        red -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = container,
            border = if (isToday && !isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    day.toString(),
                    color = content,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        // Marker for days that actually contain records.
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(
                    if (hasData) MaterialTheme.colorScheme.secondary else Color.Transparent
                )
        )
    }
}

/** Month header with previous / next navigation. */
@Composable
fun MonthNavigator(
    year: Int,
    month: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    trailing: String = "",
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) { Icon(Icons.Outlined.ChevronRight, "ماه قبل") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${Jalali.monthName(month)} $year", style = MaterialTheme.typography.titleLarge)
            if (trailing.isNotBlank()) {
                Text(trailing, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        IconButton(onClick = onNext) { Icon(Icons.Outlined.ChevronLeft, "ماه بعد") }
    }
}

/* -------------------------------------------------------------- date picker */

@Composable
fun JalaliDatePickerDialog(
    initial: String,
    title: String = "انتخاب تاریخ",
    isHoliday: (String) -> Boolean = { false },
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val start = remember(initial) { Jalali.parse(initial) ?: Jalali.today() }
    var year by remember { mutableStateOf(start.year) }
    var month by remember { mutableStateOf(start.month) }
    var selected by remember { mutableStateOf(Jalali.format(start.year, start.month, start.day)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                MonthNavigator(
                    year = year,
                    month = month,
                    onPrevious = {
                        if (month == 1) { month = 12; year -= 1 } else month -= 1
                    },
                    onNext = {
                        if (month == 12) { month = 1; year += 1 } else month += 1
                    },
                )
                Spacer(Modifier.height(6.dp))
                JalaliMonthGrid(
                    year = year,
                    month = month,
                    selected = selected,
                    isHoliday = isHoliday,
                    onSelect = { selected = it },
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val t = Jalali.today()
                        year = t.year; month = t.month
                        selected = Jalali.format(t.year, t.month, t.day)
                    }) { Text("امروز") }
                    TextButton(onClick = {
                        year -= 1
                    }) { Text("سال قبل") }
                    TextButton(onClick = {
                        year += 1
                    }) { Text("سال بعد") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(selected) }) { Text("انتخاب") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

/**
 * The app-wide date input. No form in the app asks the user to type a Jalali date
 * by hand any more — every one of them uses this field.
 */
@Composable
fun JalaliDateField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    isHoliday: (String) -> Boolean = { false },
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            shape = FieldShape,
            trailingIcon = { Icon(Icons.Outlined.CalendarMonth, null) },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledTrailingIconColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        JalaliDatePickerDialog(
            initial = value.ifBlank { Jalali.todayString() },
            title = label,
            isHoliday = isHoliday,
            onDismiss = { open = false },
            onPick = { onValue(it); open = false },
        )
    }
}

/* -------------------------------------------------------------- time picker */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            shape = FieldShape,
            trailingIcon = { Icon(Icons.Outlined.AccessTime, null) },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledTrailingIconColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val minutes = TimeRates.timeToMinutes(value) ?: (8 * 60)
        val state = rememberTimePickerState(
            initialHour = (minutes / 60).coerceIn(0, 23),
            initialMinute = (minutes % 60).coerceIn(0, 59),
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onValue(TimeRates.minutesToTime(state.hour * 60 + state.minute))
                    open = false
                }) { Text("تأیید") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("انصراف") } },
        )
    }
}
