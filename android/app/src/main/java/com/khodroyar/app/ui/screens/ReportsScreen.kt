package com.khodroyar.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.core.finance.MaintenanceType
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.FuelType
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.data.backup.BackupFiles
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.export.PdfReportWriter
import com.khodroyar.app.export.XlsxWriter
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import com.khodroyar.app.ui.theme.heroBrush
import kotlinx.coroutines.launch
import java.io.File

/**
 * Reports.
 *
 * The report engine (dependency-free XLSX writer + native PDF) is untouched. What
 * changed is the summary itself: "خلاصه بر اساس نوع سرویس" is now a compact table —
 * one line per service type with kilometres, hours, service count, tolls and amount,
 * plus an overall totals row — instead of a stack of oversized cards. The per-service
 * detail list is collapsed by default, because that was the "unnecessary expanded
 * detail" complaint.
 *
 * Crucially, the very same [Analytics.summarizeByType] aggregation feeds the screen,
 * the PDF and the Excel workbook, so the three can never disagree.
 */
@Composable
fun ReportsScreen(repository: AppRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    val today = remember { Jalali.todayString() }
    val ymd = remember { Jalali.parse(today) ?: Jalali.today() }
    val monthRange = remember { Jalali.monthRange(ymd.year, ymd.month) }

    var from by remember { mutableStateOf(monthRange.first) }
    var to by remember { mutableStateOf(monthRange.second) }
    var typeFilter by remember { mutableStateOf<ServiceType?>(null) }
    var showDetails by remember { mutableStateOf(false) }

    val allServices by repository.services().collectAsState(initial = emptyList())
    val allFuels by repository.fuels().collectAsState(initial = emptyList())
    val allMaintenance by repository.maintenances().collectAsState(initial = emptyList())
    val allPersonalExpenses by repository.personalExpenses().collectAsState(initial = emptyList())
    val allPersonalIncomes by repository.personalIncomes().collectAsState(initial = emptyList())

    val services = remember(allServices, from, to, typeFilter) {
        allServices
            .filter { it.date >= from && it.date <= to && (typeFilter == null || it.type == typeFilter!!.wire) }
            .sortedBy { it.date }
    }
    val fuels = remember(allFuels, from, to) { allFuels.filter { it.date >= from && it.date <= to }.sortedBy { it.date } }
    val maintenance = remember(allMaintenance, from, to) {
        allMaintenance.filter { it.date >= from && it.date <= to }.sortedBy { it.date }
    }

    /** The single source of truth for the on-screen table, the PDF and the workbook. */
    val summary = remember(services) { Analytics.summarizeByType(services) }

    val rangeLabel = "$from تا $to"
    val baseName = "vehicle-report-${from.replace("/", "-")}_${to.replace("/", "-")}"
    val excelFileName = "$baseName.xlsx"

    var selectedSliceId by remember { mutableStateOf<String?>(null) }
    val personalExpenses = remember(allPersonalExpenses, from, to) {
        allPersonalExpenses.filter { it.date >= from && it.date <= to }
    }
    val personalIncomes = remember(allPersonalIncomes, from, to) {
        allPersonalIncomes.filter { it.date >= from && it.date <= to }
    }
    val fuelTotal = remember(fuels) { fuels.sumOf { it.total } }
    val maintTotal = remember(maintenance) { maintenance.sumOf { it.cost } }
    val purchaseTotal = remember(personalExpenses) { personalExpenses.sumOf { it.amount } }
    val serviceIncomeTotal = remember(services) { services.sumOf { it.income.toDouble() } }
    val personalIncomeTotalAmt = remember(personalIncomes) { personalIncomes.sumOf { it.amount } }
    val costTotal = fuelTotal + maintTotal + purchaseTotal
    val breakdownSlices = remember(
        fuelTotal, maintTotal, purchaseTotal, serviceIncomeTotal, personalIncomeTotalAmt, costTotal,
    ) {
        val raw = listOf(
            DonutSlice("fuel", "سوخت", fuelTotal, Color(0xFF42A5F5)),
            DonutSlice("maint", "تعمیرات", maintTotal, Color(0xFFEF5350)),
            DonutSlice("buy", "خریدها", purchaseTotal, Color(0xFFFFA726)),
            DonutSlice("income", "درآمد", serviceIncomeTotal, Color(0xFF66BB6A)),
            DonutSlice("pincome", "درآمد شخصی", personalIncomeTotalAmt, Color(0xFFAB47BC)),
            DonutSlice("cost", "هزینه", costTotal, Color(0xFF78909C)),
        ).filter { it.value > 0.0 }
        if (raw.isEmpty()) listOf(DonutSlice("empty", "بدون داده", 1.0, Color(0xFFBDBDBD))) else raw
    }

    /* --------------------------------------------------------------- workbook */

    val totalLabel = tr("جمع کل", "Total")

    fun buildWorkbook(): XlsxWriter = XlsxWriter()
        // Sheet 1: the exact summary the screen shows, totals row included.
        .addSheet(
            XlsxWriter.Sheet(
                "خلاصه",
                listOf("نوع سرویس", "تعداد سرویس", "کیلومتر", "ساعت کارکرد", "تعداد عوارضی", "مبلغ (تومان)"),
                summary.rows.map { row ->
                    listOf(
                        XlsxWriter.Cell.Text(row.label),
                        XlsxWriter.Cell.Number(row.count.toDouble()),
                        XlsxWriter.Cell.Money(row.totalKm),
                        XlsxWriter.Cell.Number(round2(row.totalHours)),
                        XlsxWriter.Cell.Number(row.tollCount.toDouble()),
                        XlsxWriter.Cell.Money(row.totalAmount.toDouble()),
                    )
                } + listOf(
                    listOf(
                        XlsxWriter.Cell.Text(totalLabel),
                        XlsxWriter.Cell.Number(summary.totals.count.toDouble()),
                        XlsxWriter.Cell.Money(summary.totals.totalKm),
                        XlsxWriter.Cell.Number(round2(summary.totals.totalHours)),
                        XlsxWriter.Cell.Number(summary.totals.tollCount.toDouble()),
                        XlsxWriter.Cell.Money(summary.totals.totalAmount.toDouble()),
                    )
                ),
                listOf(20.0, 14.0, 14.0, 14.0, 14.0, 18.0).map { XlsxWriter.Column(it) },
                autoFilter = false,
            )
        )
        .addSheet(
            XlsxWriter.Sheet(
                "سرویس‌ها",
                listOf("ردیف", "تاریخ", "نوع سرویس", "شروع", "پایان", "کیلومتر", "ساعت", "مبدأ", "مقصد", "سرنشین", "شماره درخواست", "عوارضی", "درآمد"),
                services.mapIndexed { index, s ->
                    listOf(
                        XlsxWriter.Cell.Number((index + 1).toDouble()),
                        XlsxWriter.Cell.Text(s.date),
                        XlsxWriter.Cell.Text(ServiceType.fromWire(s.type).label),
                        XlsxWriter.Cell.Text(s.startTime),
                        XlsxWriter.Cell.Text(s.endTime),
                        XlsxWriter.Cell.Money(s.km),
                        XlsxWriter.Cell.Number(round2(s.hours)),
                        XlsxWriter.Cell.Text(s.origin),
                        XlsxWriter.Cell.Text(s.destination),
                        XlsxWriter.Cell.Text(s.passengers),
                        XlsxWriter.Cell.Text(s.requestNumber),
                        XlsxWriter.Cell.Number(s.tollCount.toDouble()),
                        XlsxWriter.Cell.Money(s.income.toDouble()),
                    )
                },
                List(13) { XlsxWriter.Column(14.0) },
            )
        )
        .addSheet(
            XlsxWriter.Sheet(
                "سوخت",
                listOf("ردیف", "تاریخ", "نوع سوخت", "لیتر", "مبلغ", "خودرو"),
                fuels.mapIndexed { index, f ->
                    listOf(
                        XlsxWriter.Cell.Number((index + 1).toDouble()),
                        XlsxWriter.Cell.Text(f.date),
                        XlsxWriter.Cell.Text(FuelType.fromWire(f.type).label),
                        XlsxWriter.Cell.Number(round2(f.liters)),
                        XlsxWriter.Cell.Money(f.total),
                        XlsxWriter.Cell.Text(f.carType),
                    )
                },
                listOf(8.0, 14.0, 16.0, 12.0, 16.0, 12.0).map { XlsxWriter.Column(it) },
            )
        )
        .addSheet(
            XlsxWriter.Sheet(
                "تعمیرات",
                listOf("ردیف", "تاریخ", "نوع", "هزینه", "کیلومتر", "توضیحات"),
                maintenance.mapIndexed { index, m ->
                    listOf(
                        XlsxWriter.Cell.Number((index + 1).toDouble()),
                        XlsxWriter.Cell.Text(m.date),
                        XlsxWriter.Cell.Text(m.typeText.ifBlank { MaintenanceType.fromWire(m.type).label }),
                        XlsxWriter.Cell.Money(m.cost),
                        XlsxWriter.Cell.Money(m.km),
                        XlsxWriter.Cell.Text(m.description),
                    )
                },
                listOf(8.0, 14.0, 20.0, 16.0, 14.0, 28.0).map { XlsxWriter.Column(it) },
            )
        )

    /* -------------------------------------------------------------------- pdf */

    fun pdfTables(): List<PdfReportWriter.Table> {
        val summaryTable = PdfReportWriter.Table(
            title = "خلاصه بر اساس نوع سرویس",
            header = listOf("نوع سرویس", "کیلومتر", "ساعت", "تعداد سرویس", "عوارضی", "مبلغ (تومان)"),
            rows = summary.rows.map { row ->
                listOf(
                    row.label,
                    money(row.totalKm),
                    Analytics.formatHours(row.totalHours),
                    money(row.count),
                    money(row.tollCount),
                    money(row.totalAmount),
                )
            },
            weights = listOf(1.5f, 1.1f, 0.9f, 1.0f, 0.9f, 1.4f),
            footer = listOf(
                "جمع کل",
                money(summary.totals.totalKm),
                Analytics.formatHours(summary.totals.totalHours),
                money(summary.totals.count),
                money(summary.totals.tollCount),
                money(summary.totals.totalAmount),
            ),
        )
        val overall = PdfReportWriter.Table(
            title = "جمع کل بازه",
            header = listOf("شرح", "مقدار"),
            rows = listOf(
                listOf("جمع کیلومتر", money(summary.totals.totalKm)),
                listOf("جمع ساعت کارکرد", Analytics.formatHours(summary.totals.totalHours)),
                listOf("تعداد کل سرویس", money(summary.totals.count)),
                listOf("تعداد کل عوارضی", money(summary.totals.tollCount)),
                listOf("جمع درآمد سرویس (تومان)", money(summary.totals.totalAmount)),
                listOf("جمع هزینه سوخت (تومان)", money(fuels.sumOf { it.total })),
                listOf("جمع هزینه تعمیرات (تومان)", money(maintenance.sumOf { it.cost })),
            ),
            weights = listOf(2f, 1.2f),
        )
        val details = PdfReportWriter.Table(
            title = "جزئیات سرویس‌ها",
            header = listOf("تاریخ", "نوع", "ساعت", "کیلومتر", "مبدأ", "مقصد", "عوارضی", "درآمد"),
            rows = services.map { s ->
                listOf(
                    s.date,
                    ServiceType.fromWire(s.type).label,
                    "${s.startTime.ifBlank { "--:--" }} تا ${s.endTime.ifBlank { "--:--" }}",
                    money(s.km),
                    s.origin,
                    s.destination,
                    money(s.tollCount),
                    money(s.income),
                )
            },
            weights = listOf(1.1f, 1f, 1.3f, 0.9f, 1.2f, 1.2f, 0.7f, 1.2f),
        )
        return listOf(summaryTable, overall, details)
    }

    val saveExcel = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri == null) { toast.show("ذخیره خروجی لغو شد"); return@rememberLauncherForActivityResult }
        scope.launch {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out -> buildWorkbook().write(out) } != null
            }.getOrDefault(false)
            toast.show(if (ok) "فایل Excel روی دستگاه ذخیره شد" else "ذخیره فایل Excel انجام نشد")
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = AppDimens.screenPadding,
            end = AppDimens.screenPadding,
            top = AppDimens.gutter,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(AppDimens.sectionSpacing),
    ) {
        /* ------------------------------------------------------------- filters */
        item {
            FormSection("بازه گزارش", Icons.Outlined.DateRange) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.gap)) {
                    JalaliDateField("از تاریخ", from, { from = it }, Modifier.weight(1f))
                    JalaliDateField("تا تاریخ", to, { to = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(AppDimens.gap))
                // Four boxed period cards: This Month, Last Month, This Week, Year
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppDimens.tightGap),
                ) {
    val periodTitles = listOf("این ماه", "ماه گذشته", "این هفته", "سال ${ymd.year}")
                    val periodActions: List<() -> Unit> = listOf(
                        {
                            val r = Jalali.monthRange(ymd.year, ymd.month); from = r.first; to = r.second
                        },
                        {
                            val prev = Jalali.addMonths(today, -1)
                            Jalali.parse(prev)?.let {
                                val r = Jalali.monthRange(it.year, it.month); from = r.first; to = r.second
                            }
                        },
                        {
                            val w = Jalali.weekRange(today); from = w.first; to = w.second
                        },
                        {
                            from = Jalali.format(ymd.year, 1, 1)
                            to = Jalali.format(ymd.year, 12, Jalali.monthLength(ymd.year, 12))
                        },
                    )
                    periodTitles.forEachIndexed { index, title ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 52.dp)
                                .clickable(onClick = periodActions[index]),
                        ) {
                            Box(
                                Modifier.padding(horizontal = 6.dp, vertical = 10.dp).fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(AppDimens.gap))
                Text(
                    "نوع سرویس",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                // Horizontal bar-style service type selector
                val typeOptions = listOf<ServiceType?>(null) + ServiceType.entries.toList()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    typeOptions.forEach { opt ->
                        val selected = typeFilter == opt
                        FilterChip(
                            selected = selected,
                            onClick = { typeFilter = opt },
                            label = {
                                Text(
                                    opt?.label ?: "همه",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }

        /* ---------------------------------------------------------- range hero */
        item {
            GradientCard(brush = heroBrush()) {
                Text(tr("گزارش کارکرد", "Work Report"), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                Text(rangeLabel, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Spacer(Modifier.height(10.dp))
                Text(
                    "${summary.totals.count} سرویس · ${money(summary.totals.totalKm)} کیلومتر · " +
                        "${Analytics.formatHours(summary.totals.totalHours)} ساعت",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${amount(summary.totals.totalAmount)} تومان درآمد",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
        }

        /* ---------------------------------------------------- circular breakdown */
        item {
            SurfaceCard {
                Text("نمودار مالی بازه", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "روی هر بخش بزنید تا جزئیات همان مورد را ببینید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                InteractiveDonutChart(
                    slices = breakdownSlices,
                    selectedId = selectedSliceId,
                    onSelect = { id -> selectedSliceId = id.ifBlank { null } },
                    centerTitle = "بازه",
                    centerSubtitle = rangeLabel,
                )
                val sel = breakdownSlices.firstOrNull { it.id == selectedSliceId }
                if (sel != null && sel.id != "empty") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (sel.id) {
                            "fuel" -> "${fuels.size} سوخت‌گیری · ${amount(sel.value)} تومان"
                            "maint" -> "${maintenance.size} تعمیر · ${amount(sel.value)} تومان"
                            "buy" -> "${personalExpenses.size} خرید · ${amount(sel.value)} تومان"
                            "income" -> "${services.size} سرویس · ${amount(sel.value)} تومان"
                            "pincome" -> "${personalIncomes.size} درآمد شخصی · ${amount(sel.value)} تومان"
                            "cost" -> "سوخت + تعمیر + خرید · ${amount(sel.value)} تومان"
                            else -> "${sel.label}: ${amount(sel.value)} تومان"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = sel.color,
                    )
                }
            }
        }

        /* ------------------------------------------------------------ exports */
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppDimens.gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { saveExcel.launch(excelFileName) },
                        modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                        shape = FieldShape,
                    ) { Icon(Icons.Outlined.SaveAlt, null); Spacer(Modifier.width(6.dp)); Text(tr("ذخیره Excel", "Save Excel")) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    val file = File(context.cacheDir, "exports/$excelFileName")
                                        .also { it.parentFile?.mkdirs() }
                                    buildWorkbook().write(file)
                                    BackupFiles.shareFile(context, file, BackupFiles.mimeFor(file), "اشتراک گزارش Excel")
                                }.onSuccess { toast.show("خروجی Excel آماده شد") }
                                    .onFailure { toast.show("ساخت خروجی Excel انجام نشد") }
                            }
                        },
                        modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                        shape = FieldShape,
                    ) { Icon(Icons.Outlined.GridOn, null); Spacer(Modifier.width(6.dp)); Text(tr("اشتراک Excel", "Share Excel")) }
                }
                Button(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val file = File(context.cacheDir, "exports/$baseName.pdf")
                                    .also { it.parentFile?.mkdirs() }
                                PdfReportWriter().write(file, "گزارش کارکرد خودرو", rangeLabel, pdfTables())
                                BackupFiles.shareFile(context, file, BackupFiles.mimeFor(file), "اشتراک گزارش PDF")
                            }.onSuccess { toast.show("خروجی PDF آماده شد") }
                                .onFailure { toast.show("ساخت خروجی PDF انجام نشد") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Icon(Icons.Outlined.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text(tr("خروجی PDF", "PDF Export")) }
            }
        }

        /* ----------------------------------------- summary table by service type */
        item { SectionTitle("خلاصه بر اساس نوع سرویس", "${summary.rows.size} نوع", Icons.Outlined.TableChart) }
        if (summary.rows.isEmpty()) {
            item { EmptyState("برای این بازه گزارشی نیست", "بازه تاریخ را تغییر دهید.", Icons.Outlined.Assessment) }
        } else {
            item {
                SurfaceCard(contentPadding = 10.dp) {
                    DataTable(
                        columns = listOf(
                            TableColumn("نوع سرویس", 92.dp, numeric = false),
                            TableColumn("کیلومتر", 76.dp),
                            TableColumn("ساعت", 62.dp),
                            TableColumn("تعداد", 56.dp),
                            TableColumn("عوارضی", 62.dp),
                            TableColumn("مبلغ", 104.dp),
                        ),
                        rows = summary.rows.map { row ->
                            listOf(
                                row.label,
                                money(row.totalKm),
                                Analytics.formatHours(row.totalHours),
                                row.count.toString(),
                                row.tollCount.toString(),
                                amount(row.totalAmount),
                            )
                        },
                        totalsRow = listOf(
                            "جمع کل",
                            money(summary.totals.totalKm),
                            Analytics.formatHours(summary.totals.totalHours),
                            summary.totals.count.toString(),
                            summary.totals.tollCount.toString(),
                            amount(summary.totals.totalAmount),
                        ),
                    )
                }
            }

            /* ------------------------------------------------------ overall totals */
            item {
                SurfaceCard {
                    Text("جمع سرویس‌ها", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("تعداد / کیلومتر / ساعت", "${summary.totals.count} · ${money(summary.totals.totalKm)} · ${Analytics.formatHours(summary.totals.totalHours)}")
                    InfoRow(
                        "درآمد سرویس",
                        "${amount(summary.totals.totalAmount)} تومان",
                        emphasis = true,
                        valueColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        /* -------------------------------------------------- fuel & maintenance */
        item { SectionTitle("سوخت و تعمیرات این بازه", icon = Icons.Outlined.Receipt) }
        item {
            SurfaceCard {
                InfoRow(
                    "سوخت (${fuels.size} رکورد)",
                    "${money(fuels.sumOf { it.liters })} لیتر · ${amount(fuels.sumOf { it.total })}",
                )
                InfoRow("تعمیرات (${maintenance.size} رکورد)", amount(maintenance.sumOf { it.cost }))
            }
        }

        /* --------------------------------------- service detail, collapsed by default */
        item {
            ActionRow(
                title = if (showDetails) "بستن جزئیات سرویس‌ها" else "نمایش جزئیات سرویس‌ها",
                subtitle = "${services.size} رکورد در این بازه",
                icon = if (showDetails) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                onClick = { showDetails = !showDetails },
            )
        }
        if (showDetails) {
            items(services, key = { it.id }) { ServiceRow(it, detailed = true) }
        }
    }
}

private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
