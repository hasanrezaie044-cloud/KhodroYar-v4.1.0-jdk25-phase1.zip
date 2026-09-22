package com.khodroyar.app.ui.screens

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
import com.khodroyar.app.data.db.LoanEntity
import com.khodroyar.app.data.db.ServiceTotals
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import com.khodroyar.app.ui.theme.heroBrush
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
fun FinanceScreen(repository: AppRepository) {
    var tab by remember { mutableStateOf(FinanceTab.OVERVIEW) }
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
            FinanceTab.PURCHASES -> PurchasesSection(repository, prefix)
            FinanceTab.INCOMES -> IncomesSection(repository, prefix)
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
    val loans by repository.loans().collectAsState(initial = emptyList())

    // Installments whose due date falls inside the displayed month.
    val installmentsDue = remember(loans, prefix) {
        loans.sumOf { loan ->
            repository.loanSummary(loan).schedule.filter { it.dueDate.startsWith(prefix) }.sumOf { it.amount }
        }
    }

    val income = totals.totalIncome + personalIncome
    val expenses = fuelCost + maintenanceCost + purchases + installmentsDue
    val profit = income - expenses

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
            MetricPair(
                first = { m -> MetricCard("درآمد", amount(income), Icons.Outlined.TrendingUp, m) },
                second = { m ->
                    MetricCard(
                        "هزینه", amount(expenses), Icons.Outlined.TrendingDown, m,
                        accent = MaterialTheme.colorScheme.secondary,
                    )
                },
            )
        }
        item {
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("سود / زیان ماه", "Monthly Profit / Loss"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(
                        amount(profit),
                        color = if (profit >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
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
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LoanEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<LoanEntity?>(null) }

    // Keep the loan ledger self-maintaining: overdue installments are marked automatically
    // when this section is opened, and a loan is removed as soon as its final installment
    // becomes paid.
    LaunchedEffect(Unit) {
        repository.markOverdueInstallments()
        repository.cleanupCompletedLoans()
    }

    val today = remember { Jalali.todayString() }
    val monthPrefix = remember(today) {
        Jalali.parse(today)?.let { "${it.year}/${it.month.toString().padStart(2, '0')}" } ?: ""
    }

    val portfolio = remember(loans, monthPrefix, today) {
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
            val s = repository.loanSummary(loan)
            principal += loan.totalAmount
            total += s.totalWithInterest
            paid += s.paidAmount
            remaining += s.remainingAmount
            installments += s.schedule.size
            paidCount += s.paidCount
            s.schedule.filter { it.dueDate.startsWith(monthPrefix) && !it.paid }.forEach {
                dueAmount += it.amount
                dueCount += 1
            }
            overdue += s.schedule.count { !it.paid && it.dueDate < today }
            val next = s.nextInstallment
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
                        "وام، سود و جدول اقساط را یک‌جا پیگیری کنید.",
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

                /* --------------------------------- level 3: per-loan details */
                item { SectionTitle("جزئیات وام‌ها", "${loans.size} وام", Icons.Outlined.ListAlt) }
                items(loans, key = { it.id }) { loan ->
                    LoanCard(
                        loan = loan,
                        summary = repository.loanSummary(loan),
                        onToggle = { index -> scope.launch { repository.toggleInstallment(loan.id, index) } },
                        onEdit = { editing = loan; showForm = true },
                        onDelete = { pendingDelete = loan },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val changed = repository.markOverdueInstallments()
                                toast.show(
                                    if (changed > 0) "$changed قسط سررسیدشده به‌عنوان پرداخت‌شده علامت خورد"
                                    else "قسط سررسیدشده‌ای برای علامت‌گذاری نبود"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(AppDimens.buttonHeight),
                        shape = FieldShape,
                    ) { Text(tr("علامت‌گذاری اقساط سررسیدشده", "Mark Due Installments")) }
                }
            }
        }
        Button(
            onClick = { editing = null; showForm = true },
            modifier = Modifier
                .padding(horizontal = AppDimens.screenPadding, vertical = 10.dp)
                .fillMaxWidth()
                .height(AppDimens.buttonHeight),
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
}

@Composable
private fun LoanCard(
    loan: LoanEntity,
    summary: LoanCalculator.Summary,
    onToggle: (Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val progress = if (summary.schedule.isEmpty()) 0f
    else summary.paidCount.toFloat() / summary.schedule.size.toFloat()

    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    loan.title.ifBlank { "وام" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${loan.date} · اصل ${amount(loan.totalAmount)} · سود ${loan.interestPercent.trimNumber()}٪",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "ویرایش") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, "حذف", tint = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(8.dp))
        MoneyLine("مبلغ کل با سود", summary.totalWithInterest.toDouble())
        MoneyLine("پرداخت‌شده", summary.paidAmount.toDouble())
        MoneyLine("باقی‌مانده", summary.remainingAmount.toDouble())
        summary.nextInstallment?.let {
            InfoRow("قسط بعدی", "${it.dueDate} · ${amount(it.amount)}")
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(7.dp),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        Text(
            "${summary.paidCount} از ${summary.schedule.size} قسط · ${(progress * 100).toInt()}٪",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "بستن جدول اقساط" else "نمایش جدول اقساط")
        }
        if (expanded) {
            val today = Jalali.todayString()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                summary.schedule.forEach { installment ->
                    val overdue = !installment.paid && installment.dueDate < today
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = installment.paid, onCheckedChange = { onToggle(installment.index) })
                        Column(Modifier.weight(1f)) {
                            Text(
                                "قسط ${installment.index} · ${installment.dueDate}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                            if (overdue) {
                                Text(
                                    "سررسید گذشته",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        Text(amount(installment.amount), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
            }
        }
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
            }
            FormSection("پیش‌نمایش محاسبه", Icons.Outlined.Calculate) {
                MoneyLine("سود", (total - principalValue))
                MoneyLine("مبلغ کل با سود", total.toDouble())
                MoneyLine("مبلغ هر قسط", perInstallment.toDouble())
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
                    modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("انصراف", "Cancel")) }
                Button(
                    onClick = {
                        if (principalValue <= 0) { toast.show("مبلغ وام را وارد کنید"); return@Button }
                        scope.launch {
                            if (editing == null) {
                                repository.addLoan(
                                    title.trim().ifBlank { "وام" }, principalValue, interestValue, date, countValue,
                                )
                                toast.show("وام ثبت شد")
                            } else {
                                repository.updateLoan(
                                    editing.id, title.trim().ifBlank { "وام" }, principalValue,
                                    interestValue, date, countValue,
                                )
                                toast.show("وام ویرایش شد")
                            }
                            onClose()
                        }
                    },
                    modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("ذخیره", "Save")) }
            }
        }
    }
}

/* ------------------------------------------------- personal purchases / income */

@Composable
private fun PurchasesSection(repository: AppRepository, prefix: String) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val expenses by repository.personalExpenses(prefix).collectAsState(initial = emptyList())
    val total by repository.personalExpenseTotal(prefix).collectAsState(initial = 0.0)
    var showForm by remember { mutableStateOf(false) }

    if (showForm) {
        LedgerForm(
            title = "ثبت خرید شخصی",
            icon = Icons.Outlined.ShoppingBag,
            categories = PurchaseCategory.entries.map { it.wire to it.label },
            onClose = { showForm = false },
            onSave = { date, category, label, value ->
                scope.launch {
                    repository.addPersonalExpense(date, category, label, value)
                    toast.show("خرید شخصی ثبت شد")
                    showForm = false
                }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = AppDimens.screenPadding, vertical = 8.dp)) {
            ScreenHeader("خریدهای شخصی", "$prefix · ${amount(total)} تومان")
        }
        if (expenses.isEmpty()) {
            EmptyState("خریدی ثبت نشده", "", Icons.Outlined.ShoppingBag, Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(AppDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(AppDimens.gap),
            ) {
                items(expenses, key = { it.id }) { expense ->
                    LedgerRow(
                        title = expense.title.ifBlank { PurchaseCategory.fromWire(expense.category).label },
                        subtitle = "${expense.date} · ${PurchaseCategory.fromWire(expense.category).label}",
                        amountText = amount(expense.amount),
                        onDelete = {
                            scope.launch {
                                repository.deletePersonalExpense(expense.id)
                                toast.show("خرید حذف شد")
                            }
                        },
                    )
                }
            }
        }
        Button(
            onClick = { showForm = true },
            modifier = Modifier
                .padding(horizontal = AppDimens.screenPadding, vertical = 10.dp)
                .fillMaxWidth()
                .height(AppDimens.buttonHeight),
            shape = FieldShape,
        ) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(tr("ثبت خرید شخصی", "Add Personal Purchase")) }
    }
}

@Composable
private fun IncomesSection(repository: AppRepository, prefix: String) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val incomes by repository.personalIncomes(prefix).collectAsState(initial = emptyList())
    val total by repository.personalIncomeTotal(prefix).collectAsState(initial = 0.0)
    var showForm by remember { mutableStateOf(false) }

    if (showForm) {
        LedgerForm(
            title = "ثبت درآمد شخصی",
            icon = Icons.Outlined.Savings,
            categories = PersonalIncomeCategory.entries.map { it.wire to it.label },
            onClose = { showForm = false },
            onSave = { date, category, label, value ->
                scope.launch {
                    repository.addPersonalIncome(date, category, label, value)
                    toast.show("درآمد شخصی ثبت شد")
                    showForm = false
                }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = AppDimens.screenPadding, vertical = 8.dp)) {
            ScreenHeader("درآمد شخصی", "$prefix · ${amount(total)} تومان")
        }
        if (incomes.isEmpty()) {
            EmptyState("درآمد شخصی ثبت نشده", "", Icons.Outlined.Savings, Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(AppDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(AppDimens.gap),
            ) {
                items(incomes, key = { it.id }) { income ->
                    LedgerRow(
                        title = income.title.ifBlank { PersonalIncomeCategory.fromWire(income.category).label },
                        subtitle = "${income.date} · ${PersonalIncomeCategory.fromWire(income.category).label}",
                        amountText = amount(income.amount),
                        onDelete = {
                            scope.launch {
                                repository.deletePersonalIncome(income.id)
                                toast.show("درآمد حذف شد")
                            }
                        },
                    )
                }
            }
        }
        Button(
            onClick = { showForm = true },
            modifier = Modifier
                .padding(horizontal = AppDimens.screenPadding, vertical = 10.dp)
                .fillMaxWidth()
                .height(AppDimens.buttonHeight),
            shape = FieldShape,
        ) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(tr("ثبت درآمد شخصی", "Add Personal Income")) }
    }
}

@Composable
private fun LedgerRow(title: String, subtitle: String, amountText: String, onDelete: () -> Unit) {
    Surface(
        shape = FieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = AppDimens.rowMinHeight),
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
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, "حذف", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Ledger entry as a full-screen panel with an inline category picker. */
@Composable
private fun LedgerForm(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    categories: List<Pair<String, String>>,
    onClose: () -> Unit,
    onSave: (date: String, category: String, title: String, amount: Double) -> Unit,
) {
    var date by remember { mutableStateOf(Jalali.todayString()) }
    var category by remember { mutableStateOf(categories.first().first) }
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

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
                OptionSelector(
                    label = "",
                    options = categories,
                    selected = categories.firstOrNull { it.first == category },
                    optionLabel = { it.second },
                    onSelect = { category = it.first },
                    columns = 2,
                )
            }
            FormSection(title, Icons.Outlined.Edit) {
                JalaliDateField("تاریخ", date, { date = it })
                Spacer(Modifier.height(AppDimens.gap))
                TextFieldR("عنوان", label, { label = it })
                Spacer(Modifier.height(AppDimens.gap))
                MoneyField("مبلغ", value, { value = it }, imeAction = ImeAction.Done)
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
                    modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("انصراف", "Cancel")) }
                Button(
                    onClick = { onSave(date, category, label.trim(), value.asDouble()) },
                    modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("ذخیره", "Save")) }
            }
        }
    }
}
