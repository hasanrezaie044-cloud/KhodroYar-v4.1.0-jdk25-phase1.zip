package com.khodroyar.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.khodroyar.app.ai.AgentScratchpad
import com.khodroyar.app.ai.AgentTask
import com.khodroyar.app.ai.AssistantMemoryStore
import com.khodroyar.app.ai.MemoryFact
import com.khodroyar.app.ui.components.StatusPill

/**
 * Owner view of the assistant's memory tiers: long-term facts (editable/deletable, with source and
 * confidence) and the persistent agent task log (episodic, cancellable). Conversation history is in
 * «تاریخچه»; the working tier is the current chat. Nothing here is sent to a model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantMemorySheet(
    facts: List<MemoryFact>, tasks: List<AgentTask>, message: String?,
    onSave: (id: String?, text: String, category: String) -> Unit, onDelete: (MemoryFact) -> Unit,
    onCancelTask: (AgentTask) -> Unit, onDeleteTask: (AgentTask) -> Unit, onDismissMessage: () -> Unit, onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<MemoryFact?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<MemoryFact?>(null) }
    var confirmDeleteTask by remember { mutableStateOf<AgentTask?>(null) }
    var confirmCancel by remember { mutableStateOf<AgentTask?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.testTag("assistant-memory-sheet").fillMaxWidth().fillMaxHeight(0.88f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("حافظه و کارهای دستیار", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("فقط روی همین دستگاه و در پشتیبان کامل. رمز، کارت و کلید ذخیره نمی‌شوند. دستیار فقط با ابزار «یادآوری» و در صورت نیاز آن‌ها را می‌خواند.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp), onClick = onDismissMessage) {
                    Text(it, Modifier.padding(12.dp).testTag("assistant-memory-message"))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("نکته‌های ماندگار (${facts.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { adding = true }, modifier = Modifier.testTag("assistant-memory-add")) { Text("افزودن") }
            }
            if (facts.isEmpty()) Text("حافظه خالی است.", style = MaterialTheme.typography.bodyMedium)
            facts.forEach { fact ->
                Surface(shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(fact.text, style = MaterialTheme.typography.bodyLarge)
                        Text("${AssistantMemoryStore.categoryLabel(fact.category)} · منبع: ${fact.sourceLabel} · اطمینان ${(fact.confidence * 100).toInt()}٪ · ${fact.id.take(8)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = { editing = fact }) { Text("ویرایش") }
                            TextButton(onClick = { confirmDelete = fact }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("حذف") }
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("کارهای چندمرحله‌ای (${tasks.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (tasks.isEmpty()) Text("کاری ثبت نشده است.", style = MaterialTheme.typography.bodyMedium)
            tasks.forEach { task ->
                Surface(shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().testTag("agent-task-${task.id.take(8)}")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(task.goal, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            StatusPill(task.status.label, color = if (task.status.open) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                        }
                        task.steps.forEachIndexed { i, step ->
                            Text("${i + 1}. ${step.text} — ${step.status.label}", style = MaterialTheme.typography.bodySmall,
                                color = if (step.status == AgentScratchpad.Status.DONE) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (task.operations.isNotEmpty()) Text("عملیات: " + task.operations.joinToString("، ") { "${it.id.take(8)} ${it.tool} ${if (it.ok) "✓" else "✗"}" },
                            style = MaterialTheme.typography.labelSmall)
                        Row {
                            if (task.status.open) TextButton(onClick = { confirmCancel = task }) { Text("لغو کار") }
                            TextButton(onClick = { confirmDeleteTask = task }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("حذف از فهرست") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (adding || editing != null) MemoryEditDialog(editing, onDismiss = { adding = false; editing = null }) { text, category ->
        onSave(editing?.id, text, category); adding = false; editing = null
    }
    confirmDelete?.let { fact ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("حذف نکته از حافظه؟") }, text = { Text("«${fact.text}» برای همیشه از حافظهٔ دستیار حذف می‌شود.") },
            confirmButton = { TextButton(onClick = { onDelete(fact); confirmDelete = null }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("انصراف") } })
    }
    confirmCancel?.let { task ->
        AlertDialog(onDismissRequest = { confirmCancel = null }, title = { Text("لغو کار؟") },
            text = { Text("مراحل باقی‌مانده اجرا نمی‌شوند. ${task.operations.count { it.ok }} عملیات تأییدشدهٔ قبلی باقی می‌ماند و خودکار برگردانده نمی‌شود.") },
            confirmButton = { TextButton(onClick = { onCancelTask(task); confirmCancel = null }) { Text("لغو کار") } },
            dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text("بازگشت") } })
    }
    confirmDeleteTask?.let { task ->
        AlertDialog(onDismissRequest = { confirmDeleteTask = null }, title = { Text("حذف کار از فهرست؟") },
            text = { Text("فقط سابقهٔ این کار حذف می‌شود؛ سوابق مالی و خودرو تغییر نمی‌کنند.") },
            confirmButton = { TextButton(onClick = { onDeleteTask(task); confirmDeleteTask = null }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDeleteTask = null }) { Text("انصراف") } })
    }
}

@Composable
private fun MemoryEditDialog(fact: MemoryFact?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var text by remember(fact) { mutableStateOf(fact?.text.orEmpty()) }
    var category by remember(fact) { mutableStateOf(fact?.category ?: "other") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (fact == null) "نکتهٔ تازه" else "ویرایش نکته") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it.take(AssistantMemoryStore.MAX_CHARS) }, Modifier.fillMaxWidth().testTag("assistant-memory-text"),
                    label = { Text("متن (حداکثر ${AssistantMemoryStore.MAX_CHARS} نویسه)") })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AssistantMemoryStore.CATEGORIES.forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(AssistantMemoryStore.categoryLabel(c)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, category) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("assistant-memory-save")) { Text("ذخیره") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } })
}
