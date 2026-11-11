package com.khodroyar.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.db.PersonnelEntity
import com.khodroyar.app.data.db.ServiceEntity
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.data.repo.EntryReceipt
import com.khodroyar.app.data.repo.QuickKind
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

/** RTL/LTR shell, theme-aware chrome, anchored navigation, toolkit, overlays and agent return routing. */
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
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val settings by repository.settings.collectAsState(initial = repository.settingsNow)

    val agentRules by remember { AutomationStore.get(context) }.rules.collectAsState()
    LaunchedEffect(agentRules.any { it.enabled }) { AgentAutomationScheduler.sync(context) }
    LaunchedEffect(Unit) { AgentAutomationScheduler.checkNow(context); TrafficCheckScheduler.sync(context) }
    var agentReturn by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf(AppDestination.HOME) }
    var tool by remember { mutableStateOf<ToolDestination?>(null) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val recentEntry by repository.quickEntries.receipt.collectAsState()
    var pendingUndo by remember { mutableStateOf<EntryReceipt?>(null) }
    var undoBusy by remember { mutableStateOf(false) }
    // Calendar quick links (fuel / repairs / expense for a given day): the tool opens
    // straight on its entry form with that date, and returns to the calendar afterwards.
    var quick by remember { mutableStateOf<QuickEntry?>(null) }
    LaunchedEffect(tool) { if (tool == null) quick = null }
    // Tapping a notification lands on the part of the app it is about.
    LaunchedEffect(launchTarget) {
        val target = launchTarget ?: return@LaunchedEffect
        overlay = null
        quick = null
        val maintenanceType = Notifications.maintenanceTypeFromTarget(target)
        if (maintenanceType != null) {
            tool = ToolDestination.MAINTENANCE
            overlay = Overlay.FastEntry(QuickKind.MAINTENANCE, maintenanceType.wire)
        } else when (target) {
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
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHost = remember { SnackbarHostState() }
    val toast = rememberToastController(snackbarHost)

    // Complete an interrupted Room/preferences handoff before the next quick/manual writer.
    LaunchedEffect(repository) {
        try { repository.quickEntries.recover() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { toast.show("ثبت سریع قبلی کامل بررسی نشد؛ رکورد و کیلومتر خودرو را بررسی کن.") }
    }
    val mileageNotice by repository.quickEntries.recoveryNotice.collectAsState()
    LaunchedEffect(mileageNotice) {
        mileageNotice?.let { toast.show(it); repository.quickEntries.acknowledgeRecoveryNotice() }
    }

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
        overlay == null && tool == null && destination == AppDestination.HOME -> todayLabel()
        else -> ""
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
                            .fillMaxWidth(),
                    ) { data ->
                        Snackbar(
                            modifier = Modifier.padding(horizontal = 12.dp).testTag("app-snackbar"),
                            dismissAction = {
                                IconButton(onClick = { data.dismiss() }, modifier = Modifier.testTag("dismiss-snackbar")) {
                                    Icon(Icons.Outlined.Close, tr("بستن پیام", "Dismiss message"))
                                }
                            },
                        ) { Text(data.visuals.message) }
                    }
                },
                bottomBar = {
                    if (!keyboardVisible && overlay == null) AppBottomBar(
                        selected = destination,
                        toolActive = tool != null,
                        onSelect = { picked ->
                            agentReturn = false
                            destination = picked
                            tool = null
                            overlay = null
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
                        onBack = { if (overlay is Overlay.FastEntry) { (context as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.onBackPressed() } else if (agentReturn) { overlay = null; tool = ToolDestination.AI_ASSISTANT; agentReturn = false } else if (overlay != null) overlay = null else tool = null },
                        onOpenTools = { scope.launch { drawerState.open() } },
                        onSearch = { overlay = Overlay.Search },
                        onQuick = { overlay = Overlay.FastEntry() },
                    )
                    AnimatedContent(
                        targetState = (overlay ?: tool ?: destination) as Any,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                        label = "screen",
                        modifier = Modifier.fillMaxSize(),
                    ) { target ->
                        when (target) {
                            /* ------------------------------------------- overlays */
                            is Overlay.FastEntry -> key(target.kind, target.maintenanceType, target.editing?.revision) {
                                QuickEntryScreen(repository, initialKind = target.kind,
                                    initialMaintenanceType = target.maintenanceType, editing = target.editing,
                                    onFullService = { date -> overlay = Overlay.NewServiceOn(date) },
                                    onDone = { overlay = null })
                            }
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
                                onAddService = { overlay = Overlay.FastEntry() },
                                recentEntry = recentEntry,
                                onEditQuick = { recentEntry?.let { overlay = Overlay.FastEntry(it.draft.kind, editing = it) } },
                                onUndoQuick = { pendingUndo = recentEntry },
                                onRecordMaintenance = { type -> overlay = Overlay.FastEntry(QuickKind.MAINTENANCE, type.wire) },
                                onOpenMaintenance = { tool = ToolDestination.MAINTENANCE },
                                onOpenAssistant = { agentReturn = false; tool = ToolDestination.AI_ASSISTANT },
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
                            AppDestination.REPORTS -> ReportsScreen(repository, onOpenService = { overlay = Overlay.EditService(it) })
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
                                onAddService = { overlay = Overlay.FastEntry() },
                                recentEntry = recentEntry,
                                onEditQuick = { recentEntry?.let { overlay = Overlay.FastEntry(it.draft.kind, editing = it) } },
                                onUndoQuick = { pendingUndo = recentEntry },
                                onRecordMaintenance = { type -> overlay = Overlay.FastEntry(QuickKind.MAINTENANCE, type.wire) },
                                onOpenMaintenance = { tool = ToolDestination.MAINTENANCE },
                                onOpenAssistant = { agentReturn = false; tool = ToolDestination.AI_ASSISTANT },
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
        BackHandler(enabled = overlay !is Overlay.FastEntry || drawerState.isOpen) {
            when {
                drawerState.isOpen -> scope.launch { drawerState.close() }
                agentReturn -> { overlay = null; tool = ToolDestination.AI_ASSISTANT; agentReturn = false }
                overlay != null -> overlay = null
                tool != null -> tool = null
                destination != AppDestination.HOME -> destination = AppDestination.HOME
                else -> showExitDialog = true
            }
        }

        pendingUndo?.let { receipt ->
            val undoError = tr("ثبت یا کیلومتر بعداً تغییر کرده؛ برای ایمنی چیزی برگردانده نشد. از فهرست اصلی ویرایش کن.", "Entry or mileage changed; nothing was undone. Edit it from the ledger.")
            val undoSuccess = tr("ثبت سریع برگردانده شد", "Quick entry undone")
            AlertDialog(onDismissRequest = { if (!undoBusy) pendingUndo = null },
                icon = { Icon(Icons.Outlined.Close, null) },
                title = { Text(tr("برگرداندن ثبت سریع؟", "Undo quick entry?")) },
                text = { Text("${receipt.draft.title.ifBlank { receipt.draft.kind.label }} · ${receipt.draft.date}\n" +
                    tr("فقط همین ثبت حذف می‌شود؛ تغییر مرتبط کیلومتر هم برمی‌گردد. قانون‌های خودکار تغییر نمی‌کنند.", "Only this entry and its associated mileage change are removed. Automation rules are unchanged.")) },
                confirmButton = { TextButton(enabled = !undoBusy, onClick = {
                    undoBusy = true
                    scope.launch {
                        try { toast.show(if (repository.quickEntries.undo(receipt)) undoSuccess else undoError); pendingUndo = null }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { toast.show(undoError); pendingUndo = null }
                        finally { undoBusy = false }
                    }
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("confirm-quick-undo")) { Text(tr("تأیید برگرداندن", "Confirm undo")) } },
                dismissButton = { TextButton(enabled = !undoBusy, onClick = { pendingUndo = null }) { Text(tr("انصراف", "Cancel")) } })
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

    data class FastEntry(val kind: QuickKind = QuickKind.EXPENSE, val maintenanceType: String? = null, val editing: EntryReceipt? = null) : Overlay {
        override val title: String get() = "ثبت سریع"
    }

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
    onBack: () -> Unit, onOpenTools: () -> Unit, onSearch: (() -> Unit)? = null, onQuick: (() -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
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
            if (onQuick != null && !showBack) FilledTonalIconButton(onClick = onQuick, modifier = Modifier.testTag("toolbar-quick-add")) {
                Icon(Icons.Outlined.Add, tr("ثبت سریع", "Quick add"))
            }
            if (onSearch != null && !showBack) IconButton(onClick = onSearch) {
                Icon(Icons.Outlined.Search, tr("جستجو", "Search"), modifier = Modifier.size(23.dp))
            }
            if (showBack) IconButton(onClick = onOpenTools) { Icon(Icons.Outlined.Menu, tr("ابزارها", "Tools")) }
        }
    }
}

/** Material's native navigation handles touch targets, selection, and gesture/three-button insets. */
@Composable
private fun AppBottomBar(selected: AppDestination, toolActive: Boolean, onSelect: (AppDestination) -> Unit) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)), shadowElevation = 2.dp) {
            NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) {
                AppDestination.entries.forEach { item ->
                    val active = !toolActive && selected == item
                    val label = when {
                        item == AppDestination.SERVICES -> tr("ثبت", "Add")
                        LocalLanguage.current == "en" -> item.englishTitle
                        else -> item.title
                    }
                    NavigationBarItem(selected = active, onClick = { onSelect(item) },
                        modifier = Modifier.testTag("nav-${item.name}"),
                        icon = { Icon(if (item == AppDestination.SERVICES) Icons.Outlined.Add else if (active) item.selectedIcon else item.icon, null) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary, selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
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
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, tr("بستن پنل", "Close tools")) }
            }
            Spacer(Modifier.height(8.dp))
            Text(tr("ابزارهای شما", "Your toolkit"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToolDestination.entries.forEach { item ->
                ActionRow(title = if (LocalLanguage.current == "en") item.englishTitle else item.title,
                    subtitle = "",
                    icon = item.icon, trailing = if (item == active) tr("فعال", "Active") else "", onClick = { onPick(item) })
            }
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
