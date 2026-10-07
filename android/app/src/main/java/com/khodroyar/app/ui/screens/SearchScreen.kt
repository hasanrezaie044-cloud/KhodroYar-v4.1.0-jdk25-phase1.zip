package com.khodroyar.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.data.db.*
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens

/**
 * Offline global search across services, fuel, maintenance, personal expenses/incomes and personnel.
 */
@Composable
fun SearchScreen(
    repository: AppRepository,
    onOpenService: (ServiceEntity) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val services by repository.services().collectAsState(initial = emptyList())
    val fuels by repository.fuels().collectAsState(initial = emptyList())
    val maints by repository.maintenances().collectAsState(initial = emptyList())
    val expenses by repository.personalExpenses().collectAsState(initial = emptyList())
    val incomes by repository.personalIncomes().collectAsState(initial = emptyList())
    val people by repository.personnel().collectAsState(initial = emptyList())

    val q = query.trim().lowercase()
    val results = remember(q, services, fuels, maints, expenses, incomes, people) {
        if (q.length < 1) emptyList()
        else buildList {
            services.forEach { s ->
                val blob = listOf(s.date, s.type, s.origin, s.destination, s.passengers, s.requestNumber, s.carType)
                    .joinToString(" ").lowercase()
                if (blob.contains(q)) add(SearchHit.Service(s))
            }
            fuels.forEach { f ->
                val blob = listOf(f.date, f.type, f.carType, f.liters.toString(), f.total.toString()).joinToString(" ").lowercase()
                if (blob.contains(q)) add(SearchHit.Fuel(f))
            }
            maints.forEach { m ->
                val blob = listOf(m.date, m.type, m.typeText, m.description, m.carType).joinToString(" ").lowercase()
                if (blob.contains(q)) add(SearchHit.Maint(m))
            }
            expenses.forEach { e ->
                val blob = listOf(e.date, e.category, e.title, e.amount.toString()).joinToString(" ").lowercase()
                if (blob.contains(q)) add(SearchHit.Expense(e))
            }
            incomes.forEach { e ->
                val blob = listOf(e.date, e.category, e.title, e.amount.toString()).joinToString(" ").lowercase()
                if (blob.contains(q)) add(SearchHit.Income(e))
            }
            people.forEach { p ->
                val blob = listOf(p.name, p.personnelCode, p.costCenter, p.phone).joinToString(" ").lowercase()
                if (blob.contains(q)) add(SearchHit.Person(p))
            }
        }.take(80)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = AppDimens.screenPadding)) {
        Spacer(Modifier.height(AppDimens.gutter))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = FieldShape,
            label = { Text(tr("جستجو در همه رکوردها", "Search all records")) },
            placeholder = { Text(tr("مسیر، سرنشین، تاریخ، عنوان…", "Route, passenger, date, title…")) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, null) }
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                q.isEmpty() -> tr("حداقل یک حرف بنویسید", "Type at least one character")
                results.isEmpty() -> tr("نتیجه‌ای پیدا نشد", "No results")
                else -> tr("${results.size} نتیجه", "${results.size} results")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(results, key = { it.key }) { hit ->
                SurfaceCard {
                    when (hit) {
                        is SearchHit.Service -> {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenService(hit.item) }
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Route, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${hit.item.date} · ${hit.item.type}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOf(hit.item.origin, hit.item.destination).filter { it.isNotBlank() }.joinToString(" ← ")
                                            .ifBlank { hit.item.passengers.ifBlank { "—" } },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(amount(hit.item.income.toDouble()), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        is SearchHit.Fuel -> HitRow(Icons.Outlined.LocalGasStation, "${hit.item.date} · ${hit.item.type}", "${hit.item.liters} لیتر · ${amount(hit.item.total)}")
                        is SearchHit.Maint -> HitRow(Icons.Outlined.Build, "${hit.item.date} · ${hit.item.typeText.ifBlank { hit.item.type }}", amount(hit.item.cost))
                        is SearchHit.Expense -> HitRow(Icons.Outlined.ShoppingCart, "${hit.item.date} · ${hit.item.title}", "${hit.item.category} · ${amount(hit.item.amount)}")
                        is SearchHit.Income -> HitRow(Icons.Outlined.Payments, "${hit.item.date} · ${hit.item.title}", "${hit.item.category} · ${amount(hit.item.amount)}")
                        is SearchHit.Person -> HitRow(Icons.Outlined.Person, hit.item.name, listOf(hit.item.personnelCode, hit.item.phone).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" })
                    }
                }
            }
        }
    }
}

@Composable
private fun HitRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private sealed class SearchHit {
    abstract val key: String
    data class Service(val item: ServiceEntity) : SearchHit() { override val key get() = "s_${item.id}" }
    data class Fuel(val item: FuelEntity) : SearchHit() { override val key get() = "f_${item.id}" }
    data class Maint(val item: MaintenanceEntity) : SearchHit() { override val key get() = "m_${item.id}" }
    data class Expense(val item: PersonalExpenseEntity) : SearchHit() { override val key get() = "e_${item.id}" }
    data class Income(val item: PersonalIncomeEntity) : SearchHit() { override val key get() = "i_${item.id}" }
    data class Person(val item: PersonnelEntity) : SearchHit() { override val key get() = "p_${item.id}" }
}
