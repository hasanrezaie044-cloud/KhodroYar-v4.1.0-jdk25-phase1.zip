package com.khodroyar.app.ui.screens

import com.khodroyar.app.core.records.RecordKey
import com.khodroyar.app.core.records.RecordKind
import com.khodroyar.app.core.records.RecordDetails
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.finance.LoanCalculator
import com.khodroyar.app.core.finance.PersonalIncomeCategory
import com.khodroyar.app.core.finance.PurchaseCategory
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.finance.LoanChartLabels
import androidx.compose.ui.platform.testTag
import com.khodroyar.app.data.db.LoanEntity
import com.khodroyar.app.data.db.ServiceTotals
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import com.khodroyar.app.ui.theme.heroBrush
import com.khodroyar.app.ui.theme.appAccents
import kotlinx.coroutines.launch

private enum class FinanceTab(val label: String) {
    OVERVIEW("نمای کلی"), LOANS("وام و اقساط"), PURCHASES("خرید شخصی"), INCOMES("درآمد شخصی")
}

/**
 * Finance.
 *
 * The three legacy areas are all still here — monthly overview with month navigation,
 * loans with a real installment schedule, and the personal purchase / income ledgers
 * with their original categories.
 *
 * What changed:
 *  * the loans section leads with a proper financial dashboard (totals, paid, remaining,
 *    installment counts, what is due this month, the next payment) in a clear three-level
 *    hierarchy instead of a bare list;
 *  * every form is a full-screen panel inside the section rather than a bottom sheet, so
 *    the keyboard cannot cover the amount fields;
 *  * every amount field formats with thousands separators as you type;
 *  * category pickers are inline cards, not dropdowns.
 */
@Composable
fun FinanceScreen(
    repository: AppRepository,
    /** Opened from a calendar day: open the personal-expense form with this date. */
    quickDate: String? = null,
    onQuickDone: () -> Unit = {},
) {
    var tab by remember { mutableStateOf(if (quickDate != null) FinanceTab.PURCHASES else FinanceTab.OVERVIEW) }
    val start = remember { Jalali.today() }
    var year by remember { mutableStateOf(start.year) }
    var month by remember { mutableStateOf(start.month) }
    val prefix = "$year/${month.toString().padStart(2, '0')}"

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = AppDimens.screenPadding, vertical = 8.dp)) {
            SegmentedSelector(
                options = FinanceTab.entries.toList(),
                selected = tab,
                label = { it.label },
                onSelect = { tab = it },
            )
        }
        when (tab) {
            FinanceTab.OVERVIEW -> FinanceOverview(
                repository = repository,
                year = year, month = month, prefix = prefix,
                onPrevious = { if (month == 1) { month = 12; year -= 1 } else month -= 1 },
                onNext = { if (month == 12) { month = 1; year += 1 } else month += 1 },
            )
            FinanceTab.LOANS -> LoansSection(repository)
            FinanceTab.PURCHASES -> PurchasesSection(repository, year, quickDate, onQuickDone)
            FinanceTab.INCOMES -> IncomesSection(repository, year)
        }
    }
}

/* ------------------------------------------------------------------- overview */

@Composable
private fun FinanceOverview(
    repository: AppRepository,
    year: Int,
    month: Int,
    prefix: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val totals by repository.serviceTotals(prefix).collectAsState(initial = ServiceTotals())
    val fuelCost by repository.fuelCost(prefix).collectAsState(initial = 0.0)
    val maintenanceCost by repository.maintenanceCost(prefix).collectAsState(initial = 0.0)
    val purchases by repository.personalExpenseTotal(prefix).collectAsState(initial = 0.0)
    val personalIncome by repository.personalIncomeTotal(prefix).collectAsState(initial = 0.0)
    val expenseRows by repository.personalExpenses(prefix).collectAsState(initial = emptyList())
    val loans by repository.loans().collectAsState(initial = emptyList())
    val byCategory = remember(expenseRows) {
        expenseRows.groupBy { it.category }.mapValues { (_, rows) -> rows.sumOf { it.amount } }
            .entries.sortedByDescending { it.value }
    }

    // Installments whose due date falls inside the displayed month.
    val installmentsDue = remember(loans, prefix) {
        loans.sumOf { loan ->
            repository.loanSummary(loan).schedule.filter { it.dueDate.startsWith(prefix) }.sumOf { it.amount }
        }
    }

    val income = totals.totalIncome + personalIncome
    val expenses = fuelCost + maintenanceCost + purchases + installmentsDue
    val profit = income - expenses
    val chartColors = appAccents.chart
    var selectedPurchase by remember(prefix) { mutableStateOf<String?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(
            start = AppDimens.screenPadding,
            end = AppDimens.screenPadding,
            top = AppDimens.gutter,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(AppDimens.gutter),
    ) {
        item {
            SurfaceCard {
                MonthNavigator(year, month, onPrevious, onNext, trailing = "${totals.count} سرویس")
            }
        }
        item {
            GradientCard(brush = heroBrush()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("تراز این ماه", "This month's balance"), Modifier.weight(1f), color = Color(0xFFB9CFD0),
                        style = MaterialTheme.typography.labelLarge)
                    Icon(if (profit >= 0) Icons.Outlined.TrendingUp else Icons.Outlined.TrendingDown, null,
                        tint = if (profit >= 0) Color(0xFF99E3C7) else Color(0xFFFFB4AB))
                }
                Spacer(Modifier.height(12.dp))
                Text(amount(profit), color = Color.White, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tr("تومان · پس از کسر هزینه و اقساط", "Toman · after expenses and installments"),
                    color = Color(0xFFB9CFD0), style = MaterialTheme.typography.labelSmall)
                HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Color.White.copy(alpha = 0.13f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HeroStat(tr("درآمد", "Income"), amount(income), Modifier.weight(1f), Icons.Outlined.TrendingUp)
                    HeroStat(tr("هزینه", "Expenses"), amount(expenses), Modifier.weight(1f), Icons.Outlined.TrendingDown)
                }
            }
        }
        item {
            SurfaceCard {
                MoneyLine("درآمد سرویس‌ها", totals.totalIncome.toDouble())
                MoneyLine("درآمد شخصی", personalIncome)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                MoneyLine("سوخت", fuelCost)
                MoneyLine("تعمیرات", maintenanceCost)
                MoneyLine("خریدهای شخصی", purchases)
                MoneyLine("اقساط این ماه", installmentsDue.toDouble())
            }
        }
        if (byCategory.isNotEmpty()) {
            item {
                SurfaceCard {
                    Text(tr("تفکیک خریدهای شخصی", "Personal purchases by category"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    InteractiveDonutChart(
                        slices = byCategory.mapIndexed { index, entry ->
                            val label = try { PurchaseCategory.fromWire(entry.key).label } catch (_: Exception) { entry.key.ifBlank { "—" } }
                            DonutSlice(entry.key, label, entry.value, chartColors[index % chartColors.size])
                        },
                        selectedId = selectedPurchase,
                        onSelect = { selectedPurchase = it.ifBlank { null } },
                        centerTitle = tr("خریدهای شخصی", "Personal purchases"),
                    )
                }
            }
        }
    }
}

@Composable
private fun MoneyLine(label: String, value: Double) {
    InfoRow(label, "${amount(value)} تومان")
}

/* ---------------------------------------------------------------------- loans */

/** Portfolio-level loan figures. Item 16's "most important numbers" come from here. */
private data class LoanPortfolio(
    val loanCount: Int,
    val principalTotal: Double,
    val totalWithInterest: Long,
    val paidAmount: Long,
    val remainingAmount: Long,
    val installmentTotal: Int,
    val paidInstallments: Int,
    val remainingInstallments: Int,
    val dueThisMonthAmount: Long,
    val dueThisMonthCount: Int,
    val nextTitle: String,
    val nextAmount: Long,
    val nextDate: String,
    val overdueCount: Int,
)

@Composable
private fun LoansSection(repository: AppRepository) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val loans by repository.loans().collectAsState(initial = emptyList())
    val installmentRows by repository.allLoanInstallments().collectAsState(initial = emptyList())
    LaunchedEffect(loans) { loans.forEach { repository.ensureLoanSchedulePublic(it) } }
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LoanEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<LoanEntity?>(null) }
    var installmentEditing by remember { mutableStateOf<com.khodroyar.app.data.db.LoanInstallmentEntity?>(null) }
    var details by remember { mutableStateOf<RecordKey?>(null) }

    val today = remember { Jalali.todayString() }
    val monthPrefix = remember(today) {
        Jalali.parse(today)?.let { "${it.year}/${it.month.toString().padStart(2, '0')}" } ?: ""
    }

    val portfolio = remember(loans, installmentRows, monthPrefix, today) {
        var principal = 0.0
        var total = 0L
        var paid = 0L
        var remaining = 0L
        var installments = 0
        var paidCount = 0
        var dueAmount = 0L
        var dueCount = 0
        var overdue = 0
        var nextTitle = ""
        var nextAmount = 0L
        var nextDate = ""
        for (loan in loans) {
            val generated = repository.loanSummary(loan).schedule
            val rows = installmentRows.filter { it.loanId == loan.id }.ifEmpty {
                generated.map { inst -> com.khodroyar.app.data.db.LoanInstallmentEntity(
                    id = "legacy-${loan.id}-${inst.index}", loanId = loan.id, number = inst.index,
                    amount = inst.amount, dueDate = inst.dueDate, paidDate = if (inst.paid) inst.dueDate else "",
                ) }
            }
            val open = rows.filter { it.paidDate.isBlank() }
            val paidRows = rows.filter { it.paidDate.isNotBlank() }
            principal += loan.totalAmount
            total += rows.sumOf { it.amount }
            paid += paidRows.sumOf { it.amount }
            remaining += open.sumOf { it.amount }
            installments += rows.size
            paidCount += paidRows.size
            open.filter { it.dueDate.startsWith(monthPrefix) }.forEach { dueAmount += it.amount; dueCount += 1 }
            overdue += open.count { it.dueDate < today }
            val next = open.minByOrNull { it.dueDate }
            if (next != null && (nextDate.isEmpty() || next.dueDate < nextDate)) {
                nextDate = next.dueDate
                nextAmount = next.amount
                nextTitle = loan.title.ifBlank { "وام" }
            }
        }
        LoanPortfolio(
            loanCount = loans.size,
            principalTotal = principal,
            totalWithInterest = total,
            paidAmount = paid,
            remainingAmount = remaining,
            installmentTotal = installments,
            paidInstallments = paidCount,
            remainingInstallments = (installments - paidCount).coerceAtLeast(0),
            dueThisMonthAmount = dueAmount,
            dueThisMonthCount = dueCount,
            nextTitle = nextTitle,
            nextAmount = nextAmount,
            nextDate = nextDate,
            overdueCount = overdue,
        )
    }

    if (showForm) {
        LoanForm(
            repository = repository,
            editing = editing,
            onClose = { showForm = false; editing = null },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = AppDimens.screenPadding,
                end = AppDimens.screenPadding,
                top = AppDimens.gutter,
                bottom = AppDimens.gutter,
            ),
            verticalArrangement = Arrangement.spacedBy(AppDimens.gutter),
        ) {
            if (loans.isEmpty()) {
                item {
                    EmptyState(
                        "وامی ثبت نشده",
                        "",
                        Icons.Outlined.AccountBalanceWallet,
                    )
                }
            } else {
                /* ------------------------------------- level 1: headline money */
                item {
                    GradientCard(brush = heroBrush()) {
                        Text(
                            "مانده کل بدهی",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            "${amount(portfolio.remainingAmount)} تومان",
                            color = Color.White,
                            style = MaterialTheme.typography.displayMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(12.dp))
                        MetricPair(
                            first = { m ->
                                MetricCard(
                                    "پرداخت‌شده", amount(portfolio.paidAmount),
                                    Icons.Outlined.CheckCircle, m, compact = true,
                                )
                            },
                            second = { m ->
                                MetricCard(
                                    "کل با سود", amount(portfolio.totalWithInterest),
                                    Icons.Outlined.AccountBalance, m, compact = true,
                                )
                            },
                        )
                        Spacer(Modifier.height(AppDimens.gap))
                        MetricPair(
                            first = { m ->
                                MetricCard(
                                    "قابل پرداخت این ماه", amount(portfolio.dueThisMonthAmount),
                                    Icons.Outlined.EventAvailable, m, compact = true,
                                )
                            },
                            second = { m ->
                                MetricCard(
                                    "اقساط این ماه", portfolio.dueThisMonthCount.toString(),
                                    Icons.Outlined.Receipt, m, compact = true,
                                )
                            },
                        )
                    }
                }

                /* --------------------------------- level 2: secondary figures */
                item { SectionTitle("آمار وام‌ها", icon = Icons.Outlined.Insights) }
                item {
                    SurfaceCard {
                        InfoRow("تعداد وام‌ها", portfolio.loanCount.toString())
                        InfoRow("جمع اصل وام‌ها", "${amount(portfolio.principalTotal)} تومان")
                        InfoRow(
                            "جمع سود",
                            "${amount(portfolio.totalWithInterest - portfolio.principalTotal)} تومان",
                        )
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        InfoRow("کل اقساط", portfolio.installmentTotal.toString())
                        InfoRow("اقساط پرداخت‌شده", portfolio.paidInstallments.toString())
                        InfoRow("اقساط باقی‌مانده", portfolio.remainingInstallments.toString())
                        if (portfolio.overdueCount > 0) {
                            InfoRow(
                                "اقساط سررسیدگذشته",
                                portfolio.overdueCount.toString(),
                                emphasis = true,
                                valueColor = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (portfolio.nextDate.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            InfoRow(
                                "قسط بعدی",
                                "${portfolio.nextTitle} · ${portfolio.nextDate}",
                            )
                            InfoRow(
                                "مبلغ قسط بعدی",
                                "${amount(portfolio.nextAmount)} تومان",
                                emphasis = true,
                                valueColor = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (portfolio.installmentTotal > 0) {
                            Spacer(Modifier.height(10.dp))
                            val progress =
                                portfolio.paidInstallments.toFloat() / portfolio.installmentTotal.toFloat()
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${(progress * 100).toInt()}٪ از اقساط پرداخت شده",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                /* ------------------------- cash-flow forecast (next 6 months) */
                item { SectionTitle("پیش‌بینی اقساط", "۶ ماه آینده", Icons.Outlined.Timeline) }
                item { LoanCashFlowForecast(loans, repository) }

                /* ------------------------- level 2.5: month-by-month loan chart */
                item { SectionTitle("نمودار وام و اقساط", "به تفکیک ماه", Icons.Outlined.ShowChart) }
                item { LoanMonthlyChart(repository, loans) }

                /* --------------------------------- level 3: per-loan details */
                item { SectionTitle("جزئیات وام‌ها", "${loans.size} وام · نواری", Icons.Outlined.ListAlt) }
                items(loans, key = { it.id }) { loan ->
                    val savedInstallments by repository.loanInstallments(loan.id).collectAsState(initial = emptyList())
                    LoanCard(
                        loan = loan,
                        repository = repository,
                        summary = repository.loanSummary(loan),
                        installments = savedInstallments,
                        onToggle = { index -> scope.launch { repository.toggleInstallment(loan.id, index) } },
                        onEditInstallment = { installmentEditing = it },
                        onDetails = { details = RecordKey(RecordKind.LOAN, loan.id) },
                        onInstallmentDetails = { details = RecordKey(RecordKind.INSTALLMENT, it.id) },
                        onEdit = { editing = loan; showForm = true },
                        onDelete = { pendingDelete = loan },
                    )
                }
            }
        }
        Button(
            onClick = { editing = null; showForm = true },
            modifier = Modifier
                .padding(horizontal = AppDimens.screenPadding, vertical = 10.dp)
                .fillMaxWidth()
                .heightIn(min = AppDimens.buttonHeight),
            shape = FieldShape,
        ) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(tr("ثبت وام", "Add Loan")) }
    }

    pendingDelete?.let { loan ->
        ConfirmDialog(
            title = "حذف وام",
            message = "وام «${loan.title}» و جدول اقساط آن حذف شود؟",
            confirmLabel = "حذف",
            destructive = true,
            onConfirm = {
                scope.launch {
                    repository.deleteLoan(loan.id)
                    toast.show("وام حذف شد")
                    pendingDelete = null
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
    details?.let { key ->
        RecordDetailsPage(
            repository = repository,
            key = key,
            onDismiss = { details = null },
            onEdit = {
                scope.launch {
                    when (key.kind) {
                        RecordKind.LOAN -> repository.loanById(key.id)?.let { details = null; editing = it; showForm = true }
                        // The installment dialog opens above the details page, which refreshes after save.
                        else -> repository.installmentById(key.id)?.let { installmentEditing = it }
                    } ?: toast.show(tr("این رکورد دیگر وجود ندارد", "This record no longer exists"))
                }
            },
        )
    }
    installmentEditing?.let { row ->
        LoanInstallmentEditDialog(row, onDismiss = { installmentEditing = null }, onSave = { updated ->
            scope.launch {
                val result = runCatching { repository.updateLoanInstallment(updated) }
                if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) throw result.exceptionOrNull()!!
                if (result.isSuccess) {
                    installmentEditing = null
                    toast.show("قسط ویرایش شد")
                } else {
                    toast.show("ویرایش قسط ذخیره نشد؛ تاریخ و مبلغ را بررسی کنید")
                }
            }
        })
    }
}

/**
 * One loan, as a collapsed strip.
 *
 * Closed it is a single bar: title, remaining balance and installment progress. Opened
 * it shows the full breakdown, the installment schedule with its checkboxes, and the
 * edit / delete actions — which are no longer icon buttons sitting on the strip itself,
 * so they cannot be hit by accident while scrolling.
 */
@Composable
private fun LoanCard(
    loan: LoanEntity,
    repository: AppRepository,
    summary: LoanCalculator.Summary,
    installments: List<com.khodroyar.app.data.db.LoanInstallmentEntity>,
    onToggle: (Int) -> Unit,
    onEditInstallment: (com.khodroyar.app.data.db.LoanInstallmentEntity) -> Unit,
    onDetails: () -> Unit,
    onInstallmentDetails: (com.khodroyar.app.data.db.LoanInstallmentEntity) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var scheduleOpen by remember(loan.id) { mutableStateOf(false) }
    val scheduleRows = remember(installments, summary) {
        installments.ifEmpty { summary.schedule.map { row -> com.khodroyar.app.data.db.LoanInstallmentEntity(
            id = "legacy-${loan.id}-${row.index}", loanId = loan.id, number = row.index, amount = row.amount,
            dueDate = row.dueDate, paidDate = if (row.paid) row.dueDate else "",
        ) } }
    }
    val paidRows = scheduleRows.filter { it.paidDate.isNotBlank() }
    val openRows = scheduleRows.filter { it.paidDate.isBlank() }
    val totalAmount = scheduleRows.sumOf { it.amount }
    val paidAmount = paidRows.sumOf { it.amount }
    val remainingAmount = openRows.sumOf { it.amount }
    val progress = if (scheduleRows.isEmpty()) 0f else paidRows.size.toFloat() / scheduleRows.size.toFloat()
    val next = openRows.minByOrNull { it.dueDate }

    Collapsible(
        title = loan.title.ifBlank { "وام" },
        summary = "مانده ${amount(remainingAmount)} تومان · " +
            "${paidRows.size} از ${scheduleRows.size} قسط",
    ) {
        Text(
            "${loan.date} · اصل ${amount(loan.totalAmount)} · سود ${loan.interestPercent.trimNumber()}٪",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        MoneyLine("مبلغ کل با سود", totalAmount.toDouble())
        MoneyLine("پرداخت‌شده", paidAmount.toDouble())
        MoneyLine("باقی‌مانده", remainingAmount.toDouble())
        next?.let { InfoRow("قسط بعدی", "${it.dueDate} · ${amount(it.amount)}") }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(7.dp),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        Text(
            "${paidRows.size} از ${scheduleRows.size} قسط · ${(progress * 100).toInt()}٪",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { scheduleOpen = !scheduleOpen }) {
                Text(if (scheduleOpen) "بستن جدول اقساط" else "نمایش جدول اقساط")
            }
            TextButton(onClick = onDetails, modifier = Modifier.testTag("loan-details-${loan.id}")) {
                Icon(Icons.Outlined.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("جزئیات کامل", "Full details"))
            }
        }
        if (scheduleOpen) {
            val today = Jalali.todayString()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                scheduleRows.forEach { installment ->
                    val paid = installment.paidDate.isNotBlank()
                    val overdue = !paid && installment.dueDate < today
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = paid, onCheckedChange = { onToggle(installment.number) })
                        // Saved rows open their own details page; legacy (unsaved) rows have no id yet.
                        val stored = !installment.id.startsWith("legacy-")
                        Column(Modifier.weight(1f).then(if (stored) Modifier.clickable(onClickLabel = "جزئیات قسط") { onInstallmentDetails(installment) } else Modifier)
                            .padding(vertical = 4.dp)) {
                            Text("قسط ${installment.number} · ${installment.dueDate}", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(when { paid -> "پرداخت‌شده · ${installment.paidDate}"; overdue -> "سررسید گذشته"; else -> "پرداخت‌نشده" },
                                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Text(amount(installment.amount.toDouble()), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        IconButton(onClick = { onEditInstallment(installment) }) { Icon(Icons.Outlined.Edit, "ویرایش قسط") }
                    }
                }
            }
        }
        RecordAttachmentsPanel(repository, "loan", loan.id)
        Spacer(Modifier.height(AppDimens.gap))
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.gap)) {
            Button(
                onClick = onEdit,
                modifier = Modifier.weight(1f).heightIn(min = AppDimens.buttonHeight),
                shape = FieldShape,
            ) {
                Icon(Icons.Outlined.Edit, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("ویرایش", "Edit"))
            }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.weight(1f).heightIn(min = AppDimens.buttonHeight),
                shape = FieldShape,
            ) {
                Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(tr("حذف", "Delete"), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** One bucket of the loan chart: a Jalali month with what is due and what came in. */
private data class LoanMonthPoint(
    val prefix: String,
    val label: String,
    val installmentAmount: Double,
    val receivedAmount: Double,
    val installmentCount: Int,
    val isCurrent: Boolean,
)

/**
 * Month-by-month loan picture: how much installment falls in each month, and which
 * months a loan was actually received (the highlighted dots). Window is three months
 * back and eight forward, which covers "what did I take, and what do I owe when".
 */
@Composable
private fun LoanMonthlyChart(repository: AppRepository, loans: List<LoanEntity>) {
    val today = remember { Jalali.todayString() }
    val points: List<LoanMonthPoint> = remember(loans, today) {
        val now = Jalali.parse(today)
        if (now == null) {
            emptyList()
        } else {
            val currentPrefix = Jalali.format(now.year, now.month, 1).substring(0, 7)
            val schedules = loans.map { loan -> loan to repository.loanSummary(loan).schedule }
            LoanChartLabels.window(today).map { prefix ->
                val monthNumber = prefix.substringAfter('/').toIntOrNull() ?: 1
                var due = 0.0
                var count = 0
                for ((_, schedule) in schedules) {
                    schedule.filter { it.dueDate.startsWith(prefix) }.forEach {
                        due += it.amount.toDouble()
                        count += 1
                    }
                }
                val received = loans.filter { it.date.startsWith(prefix) }.sumOf { it.totalAmount }
                LoanMonthPoint(
                    prefix = prefix,
                    label = Jalali.monthName(monthNumber),
                    installmentAmount = due,
                    receivedAmount = received,
                    installmentCount = count,
                    isCurrent = prefix == currentPrefix,
                )
            }
        }
    }

    if (points.isEmpty() || points.all { it.installmentAmount <= 0.0 && it.receivedAmount <= 0.0 }) {
        InsufficientData("داده‌ای برای نمودار وام وجود ندارد")
        return
    }

    val markers = points.indices.filter { points[it].receivedAmount > 0.0 }.toSet()
    val currentPoint = points.firstOrNull { it.isCurrent }

    SurfaceCard {
        Text(
            "قسط هر ماه",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${amount(currentPoint?.installmentAmount ?: 0.0)} تومان در ${
                currentPoint?.let { Jalali.monthName(Jalali.parse(it.prefix + "/01")?.month ?: 1) } ?: ""
            }",
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(AppDimens.gutter))
        // Time always runs oldest→newest left to right (data order, never a mirrored RTL
        // axis); captions are month names only and are thinned so they never overlap.
        SmoothLineChart(
            labels = points.map { "${it.label} ${it.prefix.take(4)}" },
            axisLabels = points.map { LoanChartLabels.axis(it.prefix) },
            values = points.map { it.installmentAmount },
            markers = markers,
            masked = LocalPrivacyMode.current,
            valueText = { money(it) + " تومان" },
            timelineDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
            modifier = Modifier.testTag("loan-monthly-chart"),
        )
    }
}

/**
 * Loan form as a full-screen panel. It used to be a bottom sheet, which put the amount
 * fields directly under the keyboard.
 */
@Composable
private fun LoanForm(repository: AppRepository, editing: LoanEntity?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    var title by remember { mutableStateOf(editing?.title ?: "") }
    var date by remember { mutableStateOf(editing?.date ?: Jalali.todayString()) }
    var principal by remember { mutableStateOf(editing?.totalAmount?.trimNumber()?.digitsOnly() ?: "") }
    var interest by remember { mutableStateOf(editing?.interestPercent?.trimNumber() ?: "0") }
    var count by remember { mutableStateOf(editing?.installmentsCount?.toString() ?: "12") }
    var monthlyPayment by remember { mutableStateOf(editing?.monthlyInstallment?.takeIf { it > 0 }?.trimNumber()?.digitsOnly() ?: "") }
    var notes by remember { mutableStateOf(editing?.notes.orEmpty()) }

    val principalValue = principal.asDouble()
    val interestValue = interest.asDouble()
    val countValue = count.asInt().coerceAtLeast(1)
    val total = LoanCalculator.totalWithInterest(principalValue, interestValue)
    val perInstallment = if (countValue > 0) total / countValue else 0L
    val preview = remember(date, principalValue, interestValue, countValue) {
        LoanCalculator.schedule(date, principalValue, interestValue, countValue, emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .clearFocusOnTap()
                .padding(horizontal = AppDimens.screenPadding)
                .padding(top = AppDimens.gutter, bottom = AppDimens.gutter),
            verticalArrangement = Arrangement.spacedBy(AppDimens.gutter),
        ) {
            FormSection(if (editing != null) "ویرایش وام" else "ثبت وام جدید", Icons.Outlined.AccountBalance) {
                TextFieldR("عنوان وام", title, { title = it })
                Spacer(Modifier.height(AppDimens.gap))
                JalaliDateField("تاریخ دریافت", date, { date = it })
                Spacer(Modifier.height(AppDimens.gap))
                MoneyField("مبلغ اصل وام", principal, { principal = it })
                Spacer(Modifier.height(AppDimens.gap))
                NumberField("درصد سود", interest, { interest = it }, suffix = "درصد")
                Spacer(Modifier.height(AppDimens.gap))
                NumberField("تعداد اقساط", count, { count = it }, imeAction = ImeAction.Done)
                Spacer(Modifier.height(AppDimens.gap))
                MoneyField("مبلغ ماهانه دلخواه (اختیاری)", monthlyPayment, { monthlyPayment = it })
                Spacer(Modifier.height(AppDimens.gap))
                TextFieldR("یادداشت وام", notes, { notes = it }, singleLine = false)
            }
            FormSection("پیش‌نمایش محاسبه", Icons.Outlined.Calculate) {
                MoneyLine("سود", (total - principalValue))
                MoneyLine("مبلغ کل با سود", total.toDouble())
                MoneyLine("مبلغ هر قسط", (monthlyPayment.asDouble().takeIf { it > 0 } ?: perInstallment.toDouble()))
                preview.firstOrNull()?.let { InfoRow("اولین سررسید", it.dueDate) }
                preview.lastOrNull()?.let { InfoRow("آخرین سررسید", it.dueDate) }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppDimens.screenPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f).heightIn(min = AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("انصراف", "Cancel")) }
                Button(
                    onClick = {
                        if (principalValue <= 0) { toast.show("مبلغ وام را وارد کنید"); return@Button }
                        scope.launch {
                            if (editing == null) {
                                repository.addLoan(
                                    title.trim().ifBlank { "وام" }, principalValue, interestValue, date, countValue, monthlyPayment.asDouble(), notes.trim(),
                                )
                                toast.show("وام ثبت شد")
                            } else {
                                repository.updateLoan(
                                    editing.id, title.trim().ifBlank { "وام" }, principalValue,
                                    interestValue, date, countValue, monthlyPayment.asDouble(), notes.trim(),
                                )
                                toast.show("وام ویرایش شد")
                            }
                            onClose()
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("ذخیره", "Save")) }
            }
        }
    }
}

/* ------------------------------------------------- personal purchases / income */

@Composable
private fun PurchasesSection(
    repository: AppRepository,
    year: Int,
    quickDate: String? = null,
    onQuickDone: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    var selectedYear by remember { mutableStateOf(year) }
    val yearPrefix = "$selectedYear/"
    val total by repository.personalExpenseTotal(yearPrefix).collectAsState(initial = 0.0)
    var showForm by remember { mutableStateOf(quickDate != null) }
    var editing by remember { mutableStateOf<com.khodroyar.app.data.db.PersonalExpenseEntity?>(null) }
    var details by remember { mutableStateOf<RecordKey?>(null) }

    if (showForm || editing != null) {
        val existing = editing
        LedgerForm(
            title = if (existing != null) "ویرایش خرید شخصی" else "ثبت خرید شخصی",
            icon = Icons.Outlined.ShoppingBag,
            categories = PurchaseCategory.entries.map { it.wire to it.label },
            initialDate = existing?.date ?: quickDate,
            initialCategory = existing?.category,
            initialTitle = existing?.title,
            initialAmount = existing?.amount,
            initialKm = existing?.km ?: 0.0,
            initialNotes = existing?.notes.orEmpty(),
            showExtraFields = true,
            onClose = {
                showForm = false; editing = null
                if (quickDate != null) onQuickDone()
            },
            onSave = { date, category, label, value, km, notes ->
                scope.launch {
                    if (existing != null) {
                        repository.updatePersonalExpense(existing.copy(date = date, category = category, title = label, amount = value, km = km, notes = notes.take(500)))
                        toast.show("خرید ویرایش شد")
                    } else {
                        repository.addPersonalExpense(date, category, label, value, km, notes.take(500))
                        toast.show("خرید شخصی ثبت شد")
                    }
                    showForm = false
                    editing = null
                    if (quickDate != null) onQuickDone()
                }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = AppDimens.screenPadding, vertical = 8.dp)) {
            ScreenHeader("خریدهای شخصی", "سال $selectedYear · ${amount(total)} تومان")
            Spacer(Modifier.height(AppDimens.gap))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selectedYear -= 1 }) { Icon(Icons.Outlined.ChevronRight, "سال قبل") }
                Text("سال $selectedYear", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { selectedYear += 1 }) { Icon(Icons.Outlined.ChevronLeft, "سال بعد") }
            }
        }
        // 5.9.0: month booklet; per-module search removed (global search still covers purchases).
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = AppDimens.screenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(AppDimens.gap),
        ) {
            item {
                MonthBookBrowser(
                    kind = RecordKind.EXPENSE,
                    year = selectedYear,
                    onYearChange = { selectedYear = it },
                    months = repository::expenseMonths,
                    totalLabel = tr("جمع خریدها", "Purchases"),
                    tagPrefix = "purchases",
                ) { prefix ->
                    val monthRows by remember(prefix) { repository.personalExpenses(prefix) }.collectAsState(initial = null)
                    val list = monthRows
                    when {
                        list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        list.isEmpty() -> MonthEmpty(tr("در این ماه خریدی ثبت نشده است.", "No purchases this month."))
                        else -> list.forEach { expense ->
                            val category = PurchaseCategory.fromWire(expense.category).label
                            RecordListRow(
                                kind = RecordKind.EXPENSE,
                                title = expense.title.ifBlank { category },
                                subtitle = listOf(RecordDetails.weekdayDate(expense.date), category).joinToString(" · "),
                                amountText = amount(expense.amount),
                                onClick = { details = RecordKey(RecordKind.EXPENSE, expense.id) },
                                modifier = Modifier.testTag("purchase-row-${expense.id}"),
                            )
                        }
                    }
                }
            }
        }
        Button(
            onClick = { showForm = true },
            modifier = Modifier
                .padding(horizontal = AppDimens.screenPadding, vertical = 10.dp)
                .fillMaxWidth()
                .heightIn(min = AppDimens.buttonHeight),
            shape = FieldShape,
        ) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(tr("ثبت خرید شخصی", "Add Personal Purchase")) }
    }
    details?.let { key ->
        RecordDetailsPage(
            repository = repository,
            key = key,
            onDismiss = { details = null },
            onEdit = {
                scope.launch {
                    val row = repository.expenseById(key.id)
                    if (row == null) toast.show(tr("این رکورد دیگر وجود ندارد", "This record no longer exists"))
                    else { details = null; editing = row }
                }
            },
        )
    }
}

@Composable
private fun IncomesSection(repository: AppRepository, year: Int) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    var selectedYear by remember { mutableStateOf(year) }
    val yearPrefix = "$selectedYear/"
    val incomes by repository.personalIncomes(yearPrefix).collectAsState(initial = emptyList())
    val total by repository.personalIncomeTotal(yearPrefix).collectAsState(initial = 0.0)
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<com.khodroyar.app.data.db.PersonalIncomeEntity?>(null) }
    var details by remember { mutableStateOf<RecordKey?>(null) }
    var pendingDelete by remember { mutableStateOf<com.khodroyar.app.data.db.PersonalIncomeEntity?>(null) }

    if (showForm || editing != null) {
        val existing = editing
        LedgerForm(
            title = if (existing != null) "ویرایش درآمد شخصی" else "ثبت درآمد شخصی",
            icon = Icons.Outlined.Payments,
            categories = PersonalIncomeCategory.entries.map { it.wire to it.label },
            initialDate = existing?.date,
            initialCategory = existing?.category,
            initialTitle = existing?.title,
            initialAmount = existing?.amount,
            onClose = { showForm = false; editing = null },
            onSave = { date, category, label, value, _, _ ->
                scope.launch {
                    if (existing != null) {
                        repository.updatePersonalIncome(existing.copy(date = date, category = category, title = label, amount = value))
                        toast.show("درآمد ویرایش شد")
                    } else {
                        repository.addPersonalIncome(date, category, label, value)
                        toast.show("درآمد شخصی ثبت شد")
                    }
                    showForm = false
                    editing = null
                }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = AppDimens.screenPadding, vertical = 8.dp)) {
            ScreenHeader("درآمدهای شخصی", "سال $selectedYear · ${amount(total)} تومان")
            Spacer(Modifier.height(AppDimens.gap))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selectedYear -= 1 }) { Icon(Icons.Outlined.ChevronRight, "سال قبل") }
                Text("سال $selectedYear", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { selectedYear += 1 }) { Icon(Icons.Outlined.ChevronLeft, "سال بعد") }
            }
        }
        if (incomes.isEmpty()) {
            EmptyState("درآمدی ثبت نشده", "", Icons.Outlined.Payments, Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(AppDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(AppDimens.gap),
            ) {
                item {
                    Collapsible(
                        title = tr("درآمدهای ثبت‌شده", "Recorded income"),
                        summary = "${incomes.size} رکورد · ${amount(total)} تومان",
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppDimens.gap)) {
                            incomes.forEach { income ->
                                LedgerRow(
                                    title = income.title.ifBlank { PersonalIncomeCategory.fromWire(income.category).label },
                                    subtitle = "${income.date} · ${PersonalIncomeCategory.fromWire(income.category).label}",
                                    amountText = amount(income.amount),
                                    onClick = { details = RecordKey(RecordKind.INCOME, income.id) },
                                    onEdit = { details = null; editing = income },
                                    onDelete = { pendingDelete = income },
                                )
                            }
                        }
                    }
                }
            }
        }
        Button(
            onClick = { showForm = true },
            modifier = Modifier
                .padding(horizontal = AppDimens.screenPadding, vertical = 10.dp)
                .fillMaxWidth()
                .heightIn(min = AppDimens.buttonHeight),
            shape = FieldShape,
        ) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(tr("ثبت درآمد شخصی", "Add Personal Income")) }
    }
    details?.let { key ->
        RecordDetailsPage(
            repository = repository,
            key = key,
            onDismiss = { details = null },
            onEdit = {
                scope.launch {
                    val row = repository.incomeById(key.id)
                    if (row == null) toast.show(tr("این رکورد دیگر وجود ندارد", "This record no longer exists"))
                    else { details = null; editing = row }
                }
            },
        )
    }
    pendingDelete?.let { income ->
        ConfirmDialog(
            title = tr("حذف درآمد", "Delete income"),
            message = "${income.date} · ${income.title.ifBlank { PersonalIncomeCategory.fromWire(income.category).label }} · ${amount(income.amount)} تومان",
            confirmLabel = tr("حذف", "Delete"), destructive = true,
            onConfirm = {
                scope.launch {
                    repository.deletePersonalIncome(income.id)
                    toast.show("درآمد حذف شد")
                    pendingDelete = null
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun LedgerRow(title: String, subtitle: String, amountText: String, onClick: () -> Unit, onEdit: (() -> Unit)? = null, onDelete: () -> Unit) {
    Surface(
        shape = FieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = AppDimens.rowMinHeight)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                amountText,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            if (onEdit != null) {
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "ویرایش") }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, "حذف", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Ledger entry as a full-screen panel with horizontal category bar. */
@Composable
private fun LedgerForm(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    categories: List<Pair<String, String>>,
    initialDate: String? = null,
    initialCategory: String? = null,
    initialTitle: String? = null,
    initialAmount: Double? = null,
    initialKm: Double = 0.0,
    initialNotes: String = "",
    showExtraFields: Boolean = false,
    onClose: () -> Unit,
    onSave: (date: String, category: String, title: String, amount: Double, km: Double, notes: String) -> Unit,
) {
    var date by remember { mutableStateOf(initialDate ?: Jalali.todayString()) }
    var category by remember {
        mutableStateOf(initialCategory?.takeIf { c -> categories.any { it.first == c } } ?: categories.first().first)
    }
    var label by remember { mutableStateOf(initialTitle ?: "") }
    var value by remember {
        mutableStateOf(if (initialAmount != null && initialAmount > 0) initialAmount.trimNumber().digitsOnly() else "")
    }
    var km by remember { mutableStateOf(initialKm.takeIf { it > 0 }?.trimNumber()?.digitsOnly() ?: "") }
    var notes by remember { mutableStateOf(initialNotes) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .clearFocusOnTap()
                .padding(horizontal = AppDimens.screenPadding)
                .padding(top = AppDimens.gutter, bottom = AppDimens.gutter),
            verticalArrangement = Arrangement.spacedBy(AppDimens.gutter),
        ) {
            FormSection("دسته‌بندی", icon) {
                // Horizontal bar-style category selector
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    categories.forEach { (wire, catLabel) ->
                        FilterChip(
                            selected = category == wire,
                            onClick = { category = wire },
                            label = { Text(catLabel, maxLines = 1) },
                        )
                    }
                }
            }
            FormSection(title, Icons.Outlined.Edit) {
                JalaliDateField("تاریخ", date, { date = it })
                Spacer(Modifier.height(AppDimens.gap))
                TextFieldR("عنوان", label, { label = it })
                Spacer(Modifier.height(AppDimens.gap))
                MoneyField("مبلغ", value, { value = it }, imeAction = ImeAction.Done)
                if (showExtraFields) {
                    Spacer(Modifier.height(AppDimens.gap))
                    NumberField("کیلومتر مرتبط (اختیاری)", km, { km = it }, suffix = "کیلومتر")
                    Spacer(Modifier.height(AppDimens.gap))
                    TextFieldR("یادداشت / توضیحات", notes, { notes = it }, singleLine = false, imeAction = ImeAction.Done)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppDimens.screenPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f).heightIn(min = AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("انصراف", "Cancel")) }
                Button(
                    onClick = { onSave(date, category, label.trim(), value.asDouble(), km.asDouble(), notes.trim()) },
                    modifier = Modifier.weight(1f).heightIn(min = AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("ذخیره", "Save")) }
            }
        }
    }
}

@Composable
private fun LoanCashFlowForecast(loans: List<LoanEntity>, repository: AppRepository) {
    val today = remember { Jalali.todayString() }
    val months = remember(loans, today) {
        val start = Jalali.parse(today) ?: return@remember emptyList()
        (0 until 6).map { offset ->
            val y = start.year
            val m = start.month + offset
            val year = y + (m - 1) / 12
            val month = ((m - 1) % 12) + 1
            val prefix = "%04d/%02d".format(year, month)
            var total = 0L
            var count = 0
            loans.forEach { loan ->
                val summary = repository.loanSummary(loan)
                summary.schedule.forEach { inst ->
                    if (!inst.paid && inst.dueDate.startsWith(prefix)) {
                        total += inst.amount
                        count += 1
                    }
                }
            }
            Triple(prefix, count, total)
        }
    }
    SurfaceCard {
        if (months.all { it.second == 0 }) {
            Text(
                tr("قسط باز سررسید در ۶ ماه آینده نیست.", "No open installments in the next 6 months."),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            months.forEach { (prefix, count, total) ->
                if (count > 0) {
                    InfoRow(
                        prefix,
                        tr("$count قسط · ${amount(total.toDouble())} تومان", "$count · ${amount(total.toDouble())}"),
                    )
                } else {
                    InfoRow(prefix, tr("—", "—"))
                }
            }
            val sum = months.sumOf { it.third }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            InfoRow(tr("جمع ۶ ماه", "6-month total"), "${amount(sum.toDouble())} تومان")
        }
    }
}
