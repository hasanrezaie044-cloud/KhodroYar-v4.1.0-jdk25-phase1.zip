package com.khodroyar.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.db.PersonnelEntity
import com.khodroyar.app.data.db.ServiceEntity
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.notifications.Notifications
import com.khodroyar.app.notifications.ReminderScheduler
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.nav.AppDestination
import com.khodroyar.app.ui.nav.ToolDestination
import com.khodroyar.app.ui.theme.AppDimens
import com.khodroyar.app.ui.theme.headerBrush
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
fun CarManagerRoot(repository: AppRepository, onToggleTheme: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState(initial = repository.settingsNow)

    var destination by remember { mutableStateOf(AppDestination.HOME) }
    var tool by remember { mutableStateOf<ToolDestination?>(null) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
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
        else -> if (settings.language == AppSettings.LANGUAGE_EN) "KhodroYar · Offline" else "مدیریت خودرو  ·  آفلاین"
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
                snackbarHost = { SnackbarHost(snackbarHost, Modifier.navigationBarsPadding()) },
                bottomBar = {
                    AppBottomBar(
                        selected = destination,
                        toolActive = tool != null,
                        onSelect = { picked ->
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
                        showBack = overlay != null || tool != null,
                        onBack = { if (overlay != null) overlay = null else tool = null },
                        onOpenTools = { scope.launch { drawerState.open() } },
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

                            /* ---------------------------------------------- tools */
                            ToolDestination.FUEL -> FuelScreen(repository)
                            ToolDestination.FINANCE -> FinanceScreen(repository)
                            ToolDestination.MAINTENANCE -> MaintenanceScreen(repository)
                            ToolDestination.RATES -> RatesScreen(repository)

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
                            )
                            AppDestination.SERVICES -> ServicesScreen(repository)
                            AppDestination.CALENDAR -> CalendarScreen(
                                repository = repository,
                                onQuickService = { date -> overlay = Overlay.NewServiceOn(date) },
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
                "tab_services" -> listOf("ثبت سرویس", "نوع سرویس و خودرو را انتخاب کنید، سپس تاریخ، مسیر، کیلومتر و ساعت کار را وارد کنید.")
                "tab_calendar" -> listOf("تقویم", "روز موردنظر را انتخاب کنید تا سرویس‌های همان روز را ببینید و ویرایش یا ثبت کنید.")
                "tab_reports" -> listOf("گزارش", "گزارش‌های دوره‌ای و خروجی Excel/PDF از اطلاعات ثبت‌شده را اینجا مشاهده کنید.")
                "tab_settings" -> listOf("تنظیمات", "ظاهر، زبان، هدف درآمد، اعلان‌ها، پشتیبان‌گیری، پرسنل و امنیت را از این بخش مدیریت کنید.")
                "tool_fuel" -> listOf("سوخت", "سوخت‌گیری را ثبت کنید؛ مبلغ و لیترها برای محاسبه هزینه‌ها در داشبورد استفاده می‌شوند.")
                "tool_finance" -> listOf("مالی و وام", "وام، اقساط، خرید و درآمد شخصی را ثبت و وضعیت اقساط را مدیریت کنید.")
                "tool_maintenance" -> listOf("تعمیرات و تعویض روغن", "کیلومتر فعلی و کیلومتر بعدی روغن را ثبت کنید؛ برنامه با سرویس‌های ثبت‌شده موعد هشدار را تشخیص می‌دهد.")
                "tool_rates" -> listOf("نرخ‌ها", "نرخ‌های هر سال را وارد کنید. تغییر نرخ فقط روی محاسبات همان سال اثر می‌گذارد.")
                else -> listOf("آموزش", "این بخش برای مدیریت اطلاعات خودرو است.")
            }
            val en = when (key) {
                "tab_home" -> listOf("Home", "See today's summary, income, mileage, hours, service count and oil-change status.")
                "tab_services" -> listOf("Add Service", "Choose service and vehicle, then enter date, route, mileage and work hours manually.")
                "tab_calendar" -> listOf("Calendar", "Select a day to view, edit or add services for that date.")
                "tab_reports" -> listOf("Reports", "View period reports and export your recorded data to Excel or PDF.")
                "tab_settings" -> listOf("Settings", "Manage appearance, language, income goal, notifications, backup, personnel and security.")
                "tool_fuel" -> listOf("Fuel", "Record refuelling; litres and costs are used in dashboard calculations.")
                "tool_finance" -> listOf("Finance & Loans", "Record loans, installments, personal purchases and income, and manage installment status.")
                "tool_maintenance" -> listOf("Maintenance & Oil", "Enter current and next oil-change mileage; the app uses recorded services to determine when the alert is due.")
                "tool_rates" -> listOf("Rates", "Enter rates for each year. Changing a year's rates affects calculations for that year only.")
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

/**
 * Status-bar-aware header.
 *
 * The gradient fills the whole top area including the cutout/notch strip
 * ([Modifier.statusBarsPadding] insets only the *content*), so the camera area reads as
 * part of the app. The gradient is dark in both themes, which keeps the system clock,
 * battery and signal icons legible — light status-bar icons are requested in
 * `MainActivity`. Nothing about the system bar's behaviour is overridden.
 */
@Composable
private fun AppTopBar(
    title: String,
    subtitle: String,
    privacyMode: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenTools: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(headerBrush())
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowForward, "بازگشت", tint = Color.White)
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.16f),
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Icon(
                        Icons.Outlined.DirectionsCar, null,
                        tint = Color.White,
                        modifier = Modifier.padding(9.dp).size(20.dp),
                    )
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (privacyMode) {
                Icon(
                    Icons.Outlined.VisibilityOff, "حالت حریم خصوصی روشن است",
                    tint = Color.White,
                    modifier = Modifier.padding(end = 6.dp).size(20.dp),
                )
            }
            IconButton(onClick = onOpenTools) {
                Icon(Icons.Outlined.Menu, "پنل ابزارها", tint = Color.White)
            }
        }
    }
}

/* ---------------------------------------------------------------- bottom bar */

@Composable
private fun AppBottomBar(
    selected: AppDestination,
    toolActive: Boolean,
    onSelect: (AppDestination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 0.dp,
    ) {
        AppDestination.entries.forEach { item ->
            val isSelected = !toolActive && selected == item
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(item) },
                icon = { Icon(if (isSelected) item.selectedIcon else item.icon, if (LocalLanguage.current == "en") item.englishTitle else item.title) },
                label = {
                    Text(
                        if (LocalLanguage.current == "en") item.englishTitle else item.title,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

/* --------------------------------------------------------------- tools panel */

/**
 * The right-edge slide-out panel. A Material modal drawer under RTL anchors to the
 * right and its swipe gesture follows, which is exactly the requested interaction:
 * drag in from the right edge, pick something, and it closes itself. Tapping the scrim
 * closes it too — that behaviour is built in.
 */
@Composable
private fun ToolsDrawer(
    active: ToolDestination?,
    onPick: (ToolDestination) -> Unit,
    onClose: () -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerShape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 26.dp, bottomEnd = 26.dp),
        modifier = Modifier.widthIn(max = 330.dp),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = AppDimens.screenPadding, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(AppDimens.gap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.DirectionsCar, null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(tr("پنل ابزارها", "Tools panel"), style = MaterialTheme.typography.titleLarge)
                    Text(
                        "برای باز کردن، از لبه راست به چپ بکشید",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToolDestination.entries.forEach { item ->
                // ActionRow instead of NavigationDrawerItem: the Material item clamps its
                // own height, which clips the two-line Persian labels.
                ActionRow(
                    title = if (LocalLanguage.current == "en") item.englishTitle else item.title,
                    subtitle = if (LocalLanguage.current == "en") item.englishSubtitle else item.subtitle,
                    icon = item.icon,
                    trailing = if (item == active) "فعال" else "",
                    onClick = { onPick(item) },
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(tr("بستن پنل", "Close panel")) }
        }
    }
}

/** Kept so the settings screen can describe the current theme without duplicating logic. */
fun themeLabel(settings: AppSettings): String = when (settings.themeMode) {
    AppSettings.THEME_LIGHT -> "روشن"
    AppSettings.THEME_DARK -> "تاریک"
    else -> "هماهنگ با سیستم"
}
