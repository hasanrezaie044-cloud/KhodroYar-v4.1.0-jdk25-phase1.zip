package com.khodroyar.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.data.db.PersonnelEntity
import com.khodroyar.app.ui.components.IconBadge
import com.khodroyar.app.ui.components.tr
import com.khodroyar.app.ui.theme.LocalAppAccents

/** Two-letter monogram for the booklet seal; never guesses beyond the stored name. */
fun personnelInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "؟"
        words.size == 1 -> words[0].take(1)
        else -> words[0].take(1) + "\u200c" + words[1].take(1)
    }
}

/** Home section: one ID booklet per person, swiped horizontally, each with view and edit. */
@Composable
fun PersonnelBooklets(
    personnel: List<PersonnelEntity>,
    onOpen: (PersonnelEntity) -> Unit,
    onEdit: (PersonnelEntity) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("home-personnel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.Group, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Text(tr("دفترچهٔ پرسنلی", "Personnel booklet"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(tr("${personnel.size} نفر", "${personnel.size}"), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val width = (LocalConfiguration.current.screenWidthDp - 48).coerceIn(240, 340).dp
        if (personnel.size == 1) {
            PersonnelBookletCard(personnel[0], Modifier.fillMaxWidth(), onView = { onOpen(personnel[0]) }, onEdit = { onEdit(personnel[0]) })
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(personnel, key = { "person-${it.id}" }) { person ->
                    PersonnelBookletCard(person, Modifier.width(width), onView = { onOpen(person) }, onEdit = { onEdit(person) })
                }
            }
        }
    }
}

/**
 * Premium identity booklet: a dark cover with a binding spine, guilloche rings, the
 * monogram seal and the stored fields. Static Canvas only (no images, no animation).
 */
@Composable
fun PersonnelBookletCard(
    person: PersonnelEntity,
    modifier: Modifier = Modifier,
    onView: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
) {
    val gold = Color(0xFFE2C58B)
    val cover = LocalAppAccents.current.heroGradient
    Surface(modifier = modifier.testTag("personnel-booklet-${person.id}"), shape = RoundedCornerShape(22.dp), color = Color.Transparent) {
        Row(Modifier.background(Brush.linearGradient(cover)).height(IntrinsicSize.Min)) {
            // Binding spine with stitching.
            Box(Modifier.width(14.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.22f))) {
                Canvas(Modifier.matchParentSize()) {
                    val x = size.width / 2
                    var y = 8.dp.toPx()
                    while (y < size.height - 8.dp.toPx()) {
                        drawLine(gold.copy(alpha = 0.55f), Offset(x, y), Offset(x, y + 5.dp.toPx()), 1.dp.toPx())
                        y += 10.dp.toPx()
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                Canvas(Modifier.matchParentSize()) {
                    val c = Offset(size.width * 0.08f, size.height * 0.15f)
                    repeat(4) { i -> drawCircle(gold.copy(alpha = 0.07f), size.height * (0.35f + i * 0.17f), c, style = Stroke(1.dp.toPx())) }
                }
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("کارت شناسایی پرسنلی", "Personnel ID"), color = gold, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.Badge, null, Modifier.size(16.dp), tint = gold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(gold.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.matchParentSize()) { drawCircle(gold.copy(alpha = 0.7f), style = Stroke(1.5.dp.toPx())) }
                            Text(personnelInitials(person.name), color = gold, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(person.name.ifBlank { tr("بدون نام", "Unnamed") }, color = Color.White, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(person.personnelCode.ifBlank { tr("کد پرسنلی ثبت نشده", "No personnel code") },
                                color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BookletField(tr("مرکز هزینه", "Cost centre"), person.costCenter, Modifier.weight(1f))
                        BookletField(tr("تلفن", "Phone"), person.phone, Modifier.weight(1f))
                    }
                    if (onView != null || onEdit != null) {
                        HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 4.dp), color = gold.copy(alpha = 0.18f))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            onView?.let {
                                TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp).testTag("personnel-view-${person.id}")) {
                                    Icon(Icons.Outlined.Visibility, null, Modifier.size(18.dp), tint = Color.White)
                                    Spacer(Modifier.width(6.dp)); Text(tr("مشاهده", "View"), color = Color.White)
                                }
                            }
                            onEdit?.let {
                                TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp).testTag("personnel-edit-${person.id}")) {
                                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp), tint = gold)
                                    Spacer(Modifier.width(6.dp)); Text(tr("ویرایش", "Edit"), color = gold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookletField(label: String, value: String, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$label: ${value.ifBlank { "—" }}" }) {
        Text(label, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        Text(value.ifBlank { "—" }, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
