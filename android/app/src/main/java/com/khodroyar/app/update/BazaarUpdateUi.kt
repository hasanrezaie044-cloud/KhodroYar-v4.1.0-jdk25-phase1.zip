package com.khodroyar.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.khodroyar.app.ui.components.SurfaceCard

@Composable
fun BazaarUpdatePrompt(vm: BazaarUpdateViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.check() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.check() }
    if (vm.available != null) {
        val context = LocalContext.current
        var failure by remember(vm.available) { mutableStateOf(false) }
        BazaarUpdateDialog(onLater = vm::later, failed = failure, onUpdate = {
            if (BazaarUpdateLinks.open(context)) vm.later() else { vm.openFailed(); failure = true }
        })
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun BazaarUpdateDialog(onLater: () -> Unit, onUpdate: () -> Unit, failed: Boolean = false) {
    AlertDialog(onDismissRequest = onLater, modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("bazaar-update-dialog"),
        title = { Text("به‌روزرسانی خودرویار") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("نسخهٔ جدید خودرویار در بازار منتشر شده است. دانلود و نصب از طریق خودِ بازار انجام می‌شود؛ اطلاعات شما حفظ می‌شود.")
            if (failed) Text("بازار باز نشد؛ نصب یا به‌روز بودن آن را بررسی کنید.", color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { Button(onClick = onUpdate, modifier = Modifier.testTag("bazaar-update-now")) { Text("به‌روزرسانی از بازار") } },
        dismissButton = { TextButton(onClick = onLater, modifier = Modifier.testTag("bazaar-update-later")) { Text("بعداً") } })
}

@Composable
fun BazaarUpdateSettings(vm: BazaarUpdateViewModel = viewModel()) {
    val context = LocalContext.current
    SurfaceCard {
        Text("به‌روزرسانی از بازار", style = MaterialTheme.typography.titleMedium)
        Text("نسخهٔ منتشرشده از بازار پرسیده می‌شود؛ نصب فقط در خودِ بازار و با تأیید شماست.", style = MaterialTheme.typography.bodySmall)
        vm.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("bazaar-update-status")) }
        OutlinedButton(onClick = { vm.check(manual = true) }, enabled = !vm.checking,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bazaar-check-update")) {
            Text(if (vm.checking) "در حال بررسی…" else "بررسی نسخهٔ جدید در بازار")
        }
        TextButton(onClick = { if (!BazaarUpdateLinks.open(context)) vm.openFailed() },
            modifier = Modifier.fillMaxWidth().testTag("bazaar-open-page")) { Text("باز کردن صفحهٔ خودرویار در بازار") }
    }
}
