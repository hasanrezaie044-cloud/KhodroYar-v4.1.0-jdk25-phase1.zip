package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.khodroyar.app.ai.*
import kotlinx.coroutines.delay

/** The existing settings flow uses the same compact manufacturer packs as chat. */
@Composable
internal fun AIModelSettingsPanel(state: AIModelState, selection: AISelectionMode, selectedId: String, enabled: Boolean,
    onSelection: (AISelectionMode) -> Unit, onModel: (String) -> Unit, onRefresh: () -> Unit, providerTitle: String = "OpenRouter") {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); tick++ } }
    val now = remember(state, tick) { System.currentTimeMillis() }
    var expanded by remember { mutableStateOf(false) }
    Text("مدل‌ها · $providerTitle", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        listOf(AISelectionMode.AUTOMATIC to "انتخاب اولیه", AISelectionMode.MANUAL to "انتخاب دستی").forEach { (mode, title) ->
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selection == mode, { onSelection(mode) }, enabled = enabled, modifier = Modifier.testTag("ai-selection-${mode.name}"))
                Text(title, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (selection == AISelectionMode.MANUAL) {
        OutlinedButton(onClick = { expanded = !expanded }, enabled = enabled && state.models.any { it.selectableAt(now) },
            modifier = Modifier.fillMaxWidth().testTag("ai-manual-model-picker")) { Text("انتخاب مدل") }
        if (expanded) ModelPackList(state.models, selectedId, enabled, onPick = { onModel(it); expanded = false },
            tagPrefix = "ai-manual-option-", includeUnavailable = true)
    }
    TextButton(onClick = onRefresh, enabled = enabled && !state.checking, modifier = Modifier.testTag("ai-refresh-models")) { Text("تازه‌سازی فهرست") }
    if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
