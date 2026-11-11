package com.khodroyar.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.db.PersonnelEntity
import com.khodroyar.app.data.db.ServiceEntity
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ai.AgentSection
import com.khodroyar.app.automation.AutomationStore
import com.khodroyar.app.notifications.AgentAutomationScheduler
import com.khodroyar.app.notifications.TrafficCheckScheduler
import com.khodroyar.app.notifications.AutoBackupScheduler
import com.khodroyar.app.notifications.AutoServiceScheduler
import com.khodroyar.app.notifications.Notifications
import com.khodroyar.app.notifications.ReminderScheduler
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.nav.AppDestination
import com.khodroyar.app.ui.nav.ToolDestination
import com.khodroyar.app.ui.theme.AppDimens
import com.khodroyar.app.ui.theme.LocalDarkTheme
import kotlinx.coroutines.launch

/**
 * Application shell: RTL layout direction, bottom navigation, the right-edge tools
 * drawer, full-screen overlays and the app-wide toast host.
 *
 * What changed here:
 *  * the floating service-registration FAB is gone — registration is a real tab with a
 *    real full-screen form ([ServiceFormScreen]);
 *  * "بیشتر" became "تنظیمات", and the tools it used to launch live in a right-edge
 *    slide-out panel that opens with a swipe (RTL puts a Material drawer on the right,
 *    and the swipe direction follows);
 *  * the top app area is a gradient header drawn *behind* the status bar, so the notch
 *    strip is part of the design instead of an empty band;
 *  * one back handler owns the whole hierarchy, so the exit confirmation only appears
 *    when back would actually leave the app.
 */
@Composable
fun CarManagerRoot(
    repository: AppRepository,
    /** Set by MainActivity when a notification was tapped: which section to open. */
    launchTarget: String? = null,
    onLaunchHandled: () -> Unit = {},
    onToggleTheme: () -> Unit = {},
) {
    val context = LocalContext.current
    SystemChrome(dark = LocalDarkTheme.current)
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState(initial = repository.settingsNow)

    val agentRules by remember { AutomationStore.get(context) }.rules.collectAsState()
    LaunchedEffect(agentRules.any { it.enabled }) { AgentAutomationScheduler.sync(context) }
    LaunchedEffect(Unit) { AgentAutomationScheduler.checkNow(context); TrafficCheckScheduler.sync(context) }
    var agentReturn by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf(AppDestination.HOME) }
    var tool by remember { mutableStateOf<ToolDestination?>(null) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    // Calendar quick links (fuel / repairs / expense for a given day): the tool opens
    // straight on its entry form with that date, and returns to the calendar afterwards.
    var quick by remember { mutableStateOf<QuickEntry?>(null) }
    LaunchedEffect(tool) { if (tool == null) quick = null }
    // Tapping a notification lands on the part of the app it is about.
    LaunchedEffect(launchTarget) {
        val target = launchTarget ?: return@LaunchedEffect
        overlay = null
        quick = null
        when (target) {
            Notifications.TARGET_AGENT -> tool = ToolDestination.AI_ASSISTANT
            Notifications.TARGET_MAINTENANCE -> tool = ToolDestination.MAINTENANCE
            Notifications.TARGET_FINANCE -> tool = ToolDestination.FINANCE
            Notifications.TARGET_INSURANCE -> tool = ToolDestination.INSURANCE
            Notifications.TARGET_SETTINGS -> { tool = null; destination = AppDestination.SETTINGS }
            Notifications.TARGET_TRAFFIC -> tool = ToolDestination.TRAFFIC
            Notifications.TARGET_SERVICES -> { tool = null; destination = AppDestination.SERVICES }
            Notifications.TARGET_BACKUP -> { tool = null; destination = AppDestination.SETTINGS }
            Notifications.TARGET_CALENDAR -> { tool = null; destination = AppDestination.CALENDAR }
            else -> { tool = null; destination = AppDestination.HOME }
        }
        onLaunchHandled()
    }
    var showExitDialog by remember { mutableStateOf(false) }
    var tutorialStep by remember { mutableStateOf<Int?>(null) }
    var tutorialKey by remember { mutableStateOf<String?>(null) }
    val tutorialPrefs = remember {
        context.getSharedPreferences("car_manager_onboarding", Context.MODE_PRIVATE)
    }
    LaunchedEffect(Unit) {
        if (!tutorialPrefs.getBoolean("tab_home", false)) {
            tutorialKey = "tab_home"
            tutorialStep = 0
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHost = remember { SnackbarHostState() }
    val toast = rememberToastController(snackbarHost)

    // Reminder schedule follows the switches, and is re-synced on every cold start.
    LaunchedEffect(settings.anyReminderEnabled) { ReminderScheduler.sync(context) }
    // "ثبت خودکار" — its own independent daily schedule, unrelated to the reminders.
    LaunchedEffect(settings.autoServiceEnabled) { AutoServiceScheduler.sync(context) }
    // Nightly automatic backup: its own switch, its own schedule.
    LaunchedEffect(settings.autoBackupEnabled) { AutoBackupScheduler.sync(context) }

    /*
     * ROOT CAUSE OF "no notifications": POST_NOTIFICATIONS was only ever requested from
     * a row buried in Settings. On Android 13+ it starts denied, so every reminder was
     * silently dropped. It is now requested once, on the first launch that needs it.
     */
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) ReminderScheduler.runNow(context) }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Notifications.permissionGranted(context) &&
            settings.anyReminderEnabled
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val title = when {
        overlay != null -> overlay!!.title
        tool != null -> if (settings.language == AppSettings.LANGUAGE_EN) tool!!.englishTitle else tool!!.title
        else -> if (settings.language == AppSettings.LANGUAGE_EN) destination.englishTitle else destination.title
    }
    val subtitle = when {
        overlay != null -> if (settings.language == AppSettings.LANGUAGE_EN) "Back" else "بازگشت با دکمه بازگشت"
        tool != null -> if (settings.language == AppSettings.LANGUAGE_EN) tool!!.englishSubtitle else tool!!.subtitle
        destination == AppDestination.HOME -> todayLabel()
        else -> if (settings.language == AppSettings.LANGUAGE_EN) "KhodroYar · Your workspace" else "خودرویار · فضای شخصی شما"
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides(if (settings.language == AppSettings.LANGUAGE_EN) LayoutDirection.Ltr else LayoutDirection.Rtl),
        LocalLanguage provides settings.language,
        LocalToast provides toast,
        LocalPrivacyMode provides settings.privacyMode,
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // Swipe from the leading edge — which is the RIGHT edge under RTL.
            gesturesEnabled = overlay == null,
            drawerContent = {
                ToolsDrawer(
                    active = tool,
                    onPick = { picked ->
                        agentReturn = false
                        quick = null
                        tool = picked
                        overlay = null
                        val key = "tool_${picked.name.lowercase()}"
                        if (!tutorialPrefs.getBoolean(key, false)) { tutorialKey = key; tutorialStep = 0 }
                        scope.launch { drawerState.close() }
                    },
                    onClose = { scope.launch { drawerState.close() } },
                )
            },
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHost,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(top = 8.dp),
                    ) { data ->
                        Snackbar(
                            snackbarData = data,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                },
                bottomBar = {
                    AppBottomBar(
                        selected = destination,
                        toolActive = tool != null,
                        onSelect = { picked ->
                            agentReturn = false
                            destination = picked
                            tool = null
                            overlay = null
                            val key = "tab_${picked.name.lowercase()}"
                            if (!tutorialPrefs.getBoolean(key, false)) {
                                tutorialKey = key
                                tutorialStep = 0
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding(),
                ) {
                    AppTopBar(
                        title = title,
                        subtitle = subtitle,
                        privacyMode = settings.privacyMode,
                        showBack = overlay != null || tool != null || agentReturn,
                        onBack = { if (agentReturn) { overlay = null; tool = ToolDestination.AI_ASSISTANT; agentReturn = false } else if (overlay != null) overlay = null else tool = null },
                        onOpenTools = { scope.launch { drawerState.open() } },
                        onSearch = { overlay = Overlay.Search },
                    )
                    AnimatedContent(
                        targetState = (overlay ?: tool ?: destination) as Any,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                        label = "screen",
                        modifier = Modifier.fillMaxSize(),
                    ) { target ->
                        when (target) {
                            /* ------------------------------------------- overlays */
                            is Overlay.EditService -> ServiceFormScreen(
                                repository = repository,
                                initialDate = target.service.date,
                                editing = target.service,
                                onDone = { overlay = null },
                            )
                            is Overlay.NewServiceOn -> ServiceFormScreen(
                                repository = repository,
                                initialDate = target.date,
                                editing = null,
                                onDone = { overlay = null },
                            )
                            is Overlay.PersonnelDetail -> PersonnelDetailScreen(
                                repository = repository,
                                personnelId = target.person.id,
                            )
                            is Overlay.Search -> SearchScreen(
                                repository = repository,
                                onOpenService = { overlay = Overlay.EditService(it) },
                                onClose = { overlay = null },
                            )

                            /* ---------------------------------------------- tools */
                            ToolDestination.FUEL -> FuelScreen(
                                repository,
                                quickDate = quick?.takeIf { it.tool == ToolDestination.FUEL }?.date,
                                onQuickDone = { quick = null; tool = null },
                            )
                            ToolDestination.FINANCE -> FinanceScreen(
                                repository,
                                quickDate = quick?.takeIf { it.tool == ToolDestination.FINANCE }?.date,
                                onQuickDone = { quick = null; tool = null },
                            )
                            ToolDestination.MAINTENANCE -> MaintenanceScreen(
                                repository,
                                quickDate = quick?.takeIf { it.tool == ToolDestination.MAINTENANCE }?.date,
                                onQuickDone = { quick = null; tool = null },
                            )
                            ToolDestination.RATES -> RatesScreen(repository)
                            ToolDestination.INSURANCE -> InsuranceScreen(repository)
                            ToolDestination.TRAFFIC -> TrafficAssistantScreen(repository)
                            ToolDestination.AI_ASSISTANT -> AiAssistantScreen(onNavigate = { section ->
                                overlay = null
                                quick = null
                                agentReturn = true
                                when (section) {
                                    AgentSection.HOME -> { tool = null; destination = AppDestination.HOME }
                                    AgentSection.SERVICES -> { tool = null; destination = AppDestination.SERVICES }
                                    AgentSection.CALENDAR -> { tool = null; destination = AppDestination.CALENDAR }
                                    AgentSection.REPORTS -> { tool = null; destination = AppDestination.REPORTS }
                                    AgentSection.SETTINGS -> { tool = null; destination = AppDestination.SETTINGS }
                                    AgentSection.FINANCE -> tool = ToolDestination.FINANCE
                                    AgentSection.FUEL -> tool = ToolDestination.FUEL
                                    AgentSection.MAINTENANCE -> tool = ToolDestination.MAINTENANCE
                                    AgentSection.INSURANCE -> tool = ToolDestination.INSURANCE
                                    AgentSection.RATES -> tool = ToolDestination.RATES
                                    AgentSection.TRAFFIC -> tool = ToolDestination.TRAFFIC
                                    AgentSection.SEARCH -> overlay = Overlay.Search
                                }
                            })

                            /* ----------------------------------------------- tabs */
                            AppDestination.HOME -> DashboardScreen(
                                repository = repository,
                                settings = settings,
                                onOpenReports = { destination = AppDestination.REPORTS; tool = null },
                                onOpenCalendar = { destination = AppDestination.CALENDAR; tool = null },
                                onOpenPersonnel = { person -> overlay = Overlay.PersonnelDetail(person) },
                                onTogglePrivacy = {
                                    repository.updateSettings { it.copy(privacyMode = !it.privacyMode) }
                                },
                                onOpenTraffic = { tool = ToolDestination.TRAFFIC },
                                onAddService = { destination = AppDestination.SERVICES; tool = null },
                                onOpenAssistant = { tool = ToolDestination.AI_ASSISTANT },
                            )
                            AppDestination.SERVICES -> ServicesScreen(repository)
                            AppDestination.CALENDAR -> CalendarScreen(
                                repository = repository,
                                onQuickService = { date -> overlay = Overlay.NewServiceOn(date) },
                                onQuickFuel = { date -> quick = QuickEntry(ToolDestination.FUEL, date); tool = ToolDestination.FUEL },
                                onQuickMaintenance = { date -> quick = QuickEntry(ToolDestination.MAINTENANCE, date); tool = ToolDestination.MAINTENANCE },
                                onQuickExpense = { date -> quick = QuickEntry(ToolDestination.FINANCE, date); tool = ToolDestination.FINANCE },
                                onEditService = { overlay = Overlay.EditService(it) },
                            )
                            AppDestination.REPORTS -> ReportsScreen(repository)
                            AppDestination.SETTINGS -> SettingsScreen(repository, settings, onToggleTheme)
                            else -> DashboardScreen(
                                repository = repository,
                                settings = settings,
                                onOpenReports = { destination = AppDestination.REPORTS },
                                onOpenCalendar = { destination = AppDestination.CALENDAR },
                                onOpenPersonnel = { person -> overlay = Overlay.PersonnelDetail(person) },
                                onTogglePrivacy = {
                                    repository.updateSettings { it.copy(privacyMode = !it.privacyMode) }
                                },
                                onOpenTraffic = { tool = ToolDestination.TRAFFIC },
                                onAddService = { destination = AppDestination.SERVICES; tool = null },
                                onOpenAssistant = { tool = ToolDestination.AI_ASSISTANT },
                            )
                        }
                    }
                }
            }
        }

        /*
         * ONE back handler for the whole app, so the exit confirmation can never fire
         * while the user is merely navigating: it is the last branch, reached only when
         * nothing is open and we are already on the home tab.
         */
        BackHandler {
            when {
                drawerState.isOpen -> scope.launch { drawerState.close() }
                agentReturn -> { overlay = null; tool = ToolDestination.AI_ASSISTANT; agentReturn = false }
                overlay != null -> overlay = null
                tool != null -> tool = null
                destination != AppDestination.HOME -> destination = AppDestination.HOME
                else -> showExitDialog = true
            }
        }

        tutorialStep?.let { step ->
            val key = tutorialKey ?: "tab_home"
            val fa = when (key) {
                "tab_home" -> listOf("خانه", "اینجا خلاصه وضعیت روز، درآمد، کیلومتر، ساعت، تعداد سرویس و وضعیت تعویض روغن را می‌بینید.")
                "tab_services" -> listOf("ثبت سرویس", "تاریخ، ساعت شروع، مسیر، کیلومتر و ساعت کار را وارد کنید؛ نوع سرویس از روی تاریخ (تعطیل)، ساعت و شماره درخواست خودکار تشخیص داده می‌شود.")
                "tab_calendar" -> listOf("تقویم", "روز موردنظر را انتخاب کنید تا سرویس‌های همان روز را ببینید و ویرایش یا ثبت کنید.")
                "tab_reports" -> listOf("گزارش", "گزارش‌های دوره‌ای و خروجی Excel/PDF از اطلاعات ثبت‌شده را اینجا مشاهده کنید.")
                "tab_settings" -> listOf("تنظیمات", "ظاهر، زبان، هدف درآمد، اعلان‌ها، پشتیبان‌گیری، پرسنل و امنیت را از این بخش مدیریت کنید.")
                "tool_fuel" -> listOf("سوخت", "سوخت‌گیری را ثبت کنید؛ مبلغ و لیترها برای محاسبه هزینه‌ها در داشبورد استفاده می‌شوند.")
                "tool_finance" -> listOf("مالی و وام", "وام، اقساط، خرید و درآمد شخصی را ثبت و وضعیت اقساط را مدیریت کنید.")
                "tool_maintenance" -> listOf("تعمیرات و تعویض روغن", "کیلومتر فعلی و کیلومتر بعدی روغن را ثبت کنید؛ برنامه با سرویس‌های ثبت‌شده موعد هشدار را تشخیص می‌دهد.")
                "tool_rates" -> listOf("نرخ‌ها", "نرخ‌های هر سال را وارد کنید. تغییر نرخ فقط روی محاسبات همان سال اثر می‌گذارد.")
                "tool_ai_assistant" -> listOf("دستیار هوشمند", "با دستیار گفتگو کنید، گزارش بگیرید یا ثبت هزینه و سرویس را بخواهید. هر ثبت، ویرایش یا حذف فقط پس از تأیید شما انجام می‌شود.")
                else -> listOf("آموزش", "این بخش برای مدیریت اطلاعات خودرو است.")
            }
            val en = when (key) {
                "tab_home" -> listOf("Home", "See today's summary, income, mileage, hours, service count and oil-change status.")
                "tab_services" -> listOf("Add Service", "Enter date, start time, route, mileage and work hours; the service type is detected automatically from the date (holiday), time and request number.")
                "tab_calendar" -> listOf("Calendar", "Select a day to view, edit or add services for that date.")
                "tab_reports" -> listOf("Reports", "View period reports and export your recorded data to Excel or PDF.")
                "tab_settings" -> listOf("Settings", "Manage appearance, language, income goal, notifications, backup, personnel and security.")
                "tool_fuel" -> listOf("Fuel", "Record refuelling; litres and costs are used in dashboard calculations.")
                "tool_finance" -> listOf("Finance & Loans", "Record loans, installments, personal purchases and income, and manage installment status.")
                "tool_maintenance" -> listOf("Maintenance & Oil", "Enter current and next oil-change mileage; the app uses recorded services to determine when the alert is due.")
                "tool_rates" -> listOf("Rates", "Enter rates for each year. Changing a year's rates affects calculations for that year only.")
                "tool_ai_assistant" -> listOf("Smart Assistant", "Chat, get reports, or ask to record expenses and services. Every save, edit or delete happens only after you confirm.")
                else -> listOf("Tutorial", "This section helps manage your vehicle information.")
            }
            val titles = if (settings.language == AppSettings.LANGUAGE_EN) en else fa
            AlertDialog(
                onDismissRequest = { tutorialPrefs.edit().putBoolean(key, true).apply(); tutorialStep = null; tutorialKey = null },
                title = { Text(if (settings.language == AppSettings.LANGUAGE_EN) "Tutorial: ${titles[0]}" else "آموزش ${titles[0]}") },
                text = { Column { Text("1 / 1", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text(titles[1]) } },
                confirmButton = {
                    Button(onClick = { tutorialPrefs.edit().putBoolean(key, true).apply(); tutorialStep = null; tutorialKey = null }) {
                        Text(if (settings.language == AppSettings.LANGUAGE_EN) "Got it" else "شروع")
                    }
                },
                dismissButton = { TextButton(onClick = { tutorialPrefs.edit().putBoolean(key, true).apply(); tutorialStep = null; tutorialKey = null }) { Text(if (settings.language == AppSettings.LANGUAGE_EN) "Skip" else "رد کردن") } },
            )
        }

        if (showExitDialog) {
            ConfirmDialog(
                title = "خروج از برنامه",
                message = "آیا مطمئن هستید که می‌خواهید از برنامه خارج شوید؟",
                confirmLabel = "خروج",
                dismissLabel = "انصراف",
                destructive = true,
                onConfirm = {
                    showExitDialog = false
                    (context as? Activity)?.finish()
                },
                onDismiss = { showExitDialog = false },
            )
        }
    }
}

/** Full-screen panels that sit above the tab content. */
sealed interface Overlay {
    val title: String

    data class EditService(val service: ServiceEntity) : Overlay {
        override val title: String get() = "ویرایش سرویس"
    }

    data class NewServiceOn(val date: String) : Overlay {
        override val title: String get() = "ثبت سرویس $date"
    }

    data class PersonnelDetail(val person: PersonnelEntity) : Overlay {
        override val title: String get() = "اطلاعات پرسنلی"
    }

    data object Search : Overlay {
        override val title: String get() = "جستجو"
    }
}

@Composable
private fun todayLabel(): String {
    val today = remember { Jalali.todayString() }
    val weekday = remember(today) {
        Jalali.parse(today)?.let { Jalali.weekdayName(it.year, it.month, it.day) } ?: ""
    }
    return "$today  •  $weekday"
}

/* ------------------------------------------------------------------- top bar */

/** A quiet, theme-aware toolbar. Insets and icon contrast follow the actual surface. */
@Composable
private fun AppTopBar(
    title: String, subtitle: String, privacyMode: Boolean, showBack: Boolean,
    onBack: () -> Unit, onOpenTools: () -> Unit, onSearch: (() -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                IconButton(onClick = if (showBack) onBack else onOpenTools) {
                    Icon(if (showBack) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Menu,
                        if (showBack) tr("بازگشت", "Back") else tr("ابزارها", "Tools"), modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (privacyMode) Icon(Icons.Outlined.VisibilityOff, tr("حالت حریم خصوصی", "Privacy mode"),
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            if (onSearch != null && !showBack) IconButton(onClick = onSearch) {
                Icon(Icons.Outlined.Search, tr("جستجو", "Search"), modifier = Modifier.size(23.dp))
            }
            if (showBack) IconButton(onClick = onOpenTools) { Icon(Icons.Outlined.Menu, tr("ابزارها", "Tools")) }
        }
    }
}

/** Anchored navigation, a distinct registration action, and readable persistent labels. */
@Composable
private fun AppBottomBar(selected: AppDestination, toolActive: Boolean, onSelect: (AppDestination) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AppDestination.entries.forEach { item ->
                    val active = !toolActive && selected == item
                    val title = if (LocalLanguage.current == "en") item.englishTitle else item.title
                    val tint by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "destination")
                    val add = item == AppDestination.SERVICES
                    Column(Modifier.weight(1f).testTag("nav-${item.name}").clip(RoundedCornerShape(14.dp))
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(item) })
                        .heightIn(min = 64.dp).padding(horizontal = 2.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                        Box(Modifier.size(width = 46.dp, height = 32.dp).clip(RoundedCornerShape(11.dp))
                            .background(when { add -> MaterialTheme.colorScheme.primary; active -> MaterialTheme.colorScheme.primaryContainer; else -> Color.Transparent }),
                            contentAlignment = Alignment.Center) {
                            Icon(if (add) Icons.Outlined.Add else if (active) item.selectedIcon else item.icon,
                                null, tint = if (add) MaterialTheme.colorScheme.onPrimary else tint, modifier = Modifier.size(22.dp))
                        }
                        Text(title, color = tint, style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Full-height toolkit; large row targets and no text clipped by fixed drawer-item heights. */
@Composable
private fun ToolsDrawer(active: ToolDestination?, onPick: (ToolDestination) -> Unit, onClose: () -> Unit) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp), modifier = Modifier.widthIn(max = 350.dp)) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Outlined.DirectionsCar, size = 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(tr("خودرویار", "KhodroYar"), style = MaterialTheme.typography.titleLarge)
                    Text(tr("همه‌چیز، یک‌جا", "Everything, together"), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, tr("بستن پنل", "Close tools")) }
            }
            Spacer(Modifier.height(8.dp))
            Text(tr("ابزارهای شما", "Your toolkit"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToolDestination.entries.forEach { item ->
                ActionRow(title = if (LocalLanguage.current == "en") item.englishTitle else item.title,
                    subtitle = if (LocalLanguage.current == "en") item.englishSubtitle else item.subtitle,
                    icon = item.icon, trailing = if (item == active) tr("فعال", "Active") else "", onClick = { onPick(item) })
            }
            Spacer(Modifier.height(12.dp))
            Text(tr("اطلاعات شما روی همین دستگاه می‌ماند؛ ابزارهای آنلاین فقط با تنظیم و اجازهٔ شما کار می‌کنند.",
                "Your records stay on this device. Online tools require your configuration and consent."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Kept so the settings screen can describe the current theme without duplicating logic. */
fun themeLabel(settings: AppSettings): String = when (settings.themeMode) {
    AppSettings.THEME_LIGHT -> "روشن"
    AppSettings.THEME_DARK -> "تاریک"
    else -> "هماهنگ با سیستم"
}


/** A calendar quick link in flight: which tool to open, and the day it was tapped on. */
private data class QuickEntry(val tool: ToolDestination, val date: String)
