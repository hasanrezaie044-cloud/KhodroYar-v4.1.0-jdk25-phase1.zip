package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.updates.BazaarListing
import com.khodroyar.app.updates.BazaarUpdatesViewModel

/** Place INSIDE AuthGate: no update dialog overlays PIN/first-use consent. */
@Composable
internal fun BazaarUpdateHost(updates: BazaarUpdatesViewModel = viewModel()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by updates.state.collectAsState()
    DisposableEffect(lifecycle, updates) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> updates.onForeground()
                Lifecycle.Event.ON_STOP -> updates.onBackground()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) updates.onForeground()
        onDispose { lifecycle.removeObserver(observer); updates.onBackground() }
    }
    if (state.showPrompt && state.availableCode != null) {
        val context = LocalContext.current
        var failed by remember { mutableStateOf(false) }
        BazaarUpdateDialog(failed = failed, onLater = updates::dismiss, onUpdate = {
            if (BazaarListing.open(context)) { failed = false; updates.dismiss() } else failed = true
        })
    }
}

@Composable
internal fun BazaarUpdateDialog(failed: Boolean = false, onLater: () -> Unit, onUpdate: () -> Unit) {
    AlertDialog(onDismissRequest = onLater, modifier = Modifier.testTag("bazaar-update-dialog"),
        icon = { Icon(Icons.Outlined.SystemUpdate, null) },
        title = { Text(tr("نسخهٔ جدید آماده است", "An update is available")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("نسخهٔ تازهٔ مدیریت خودرو در کافه‌بازار قابل دریافت است.", "A newer KhodroYar version is available on Cafe Bazaar."))
            Text(tr("برای حفظ اطلاعات، برنامه را به‌روزرسانی کن؛ آن را حذف نکن.", "Update the installed app to retain your data; do not uninstall it."), style = MaterialTheme.typography.bodySmall)
            if (failed) Text(tr("صفحهٔ بازار باز نشد؛ دوباره تلاش کن.", "Could not open the store page. Try again."), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = onUpdate, modifier = Modifier.heightIn(min = 48.dp).testTag("bazaar-update-open")) {
            Text(tr("به‌روزرسانی در بازار", "Update on Bazaar"))
        } },
        dismissButton = { TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp).testTag("bazaar-update-later")) { Text(tr("بعداً", "Later")) } })
}

@Composable
internal fun BazaarUpdateSettingsCard(updates: BazaarUpdatesViewModel = viewModel()) {
    val state by updates.state.collectAsState()
    val context = LocalContext.current
    val toast = LocalToast.current
    val couldNotOpen = tr("صفحهٔ بازار باز نشد.", "Could not open Bazaar.")
    SurfaceCard {
        SectionTitle(tr("به‌روزرسانی برنامه", "App updates"))
        val message = when {
            state.checking -> tr("در حال بررسی بازار…", "Checking Bazaar…")
            state.outcome == BazaarUpdatesViewModel.Outcome.AVAILABLE -> tr("نسخهٔ جدید در بازار موجود است", "A new version is available on Bazaar")
            state.outcome == BazaarUpdatesViewModel.Outcome.NONE_REPORTED -> tr("بازار نسخهٔ جدیدی گزارش نکرد؛ ممکن است با تأخیر تازه شود.", "Bazaar reported no update; its cache may refresh later.")
            state.outcome == BazaarUpdatesViewModel.Outcome.BAZAAR_MISSING -> tr("برای بررسی خودکار، برنامهٔ کافه‌بازار باید نصب باشد.", "The Bazaar app must be installed for automatic checks.")
            state.outcome == BazaarUpdatesViewModel.Outcome.UNAVAILABLE -> tr("بررسی انجام نشد؛ بعداً دوباره تلاش کن.", "Could not check. Try again later.")
            else -> tr("بررسی نسخه از خود کافه‌بازار", "Check the version directly with Cafe Bazaar")
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { updates.check() }, enabled = !state.checking,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bazaar-update-check"), shape = FieldShape) {
            if (state.checking) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(tr("بررسی به‌روزرسانی", "Check for updates"))
        }
        TextButton(onClick = {
            if (!BazaarListing.open(context)) toast.show(couldNotOpen)
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr("صفحهٔ برنامه در بازار", "Open the Bazaar listing")) }
    }
}
