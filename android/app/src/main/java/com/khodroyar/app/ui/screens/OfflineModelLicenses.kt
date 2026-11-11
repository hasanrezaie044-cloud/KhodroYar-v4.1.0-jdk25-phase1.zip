package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.khodroyar.app.ui.components.FieldShape
import com.khodroyar.app.ui.components.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun OfflineModelLicenseLink() {
    var open by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val files = remember { listOf(
        "KhodroYar-ALBERT-NOTICE.txt" to "Model & modifications",
        "ALBERT-Apache-2.0.txt" to "ALBERT · Apache-2.0",
        "ONNX-Runtime-MIT.txt" to "ONNX Runtime · MIT",
        "ONNX-Runtime-ThirdPartyNotices.txt" to "ONNX Runtime · dependency notices",
        "SentencePiece-LICENSE.txt" to "SentencePiece · Apache-2.0",
        "SentencePiece-absl-LICENSE.txt" to "SentencePiece · Abseil",
        "SentencePiece-darts_clone-LICENSE.txt" to "SentencePiece · Darts",
        "SentencePiece-esaxx-LICENSE.txt" to "SentencePiece · esaxx",
        "SentencePiece-protobuf-lite-LICENSE.txt" to "SentencePiece · Protobuf Lite",
    ) }
    OutlinedButton(onClick = { open = true }, shape = FieldShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(tr("مدل آفلاین و مجوزها", "Offline model & licences"))
    }
    if (open) {
        val text by produceState<String?>(null, selected) {
            value = null
            if (selected != null) value = withContext(Dispatchers.IO) {
                runCatching { context.assets.open("licenses/$selected").bufferedReader().use { it.readText() } }.getOrNull()
            } else value = ""
        }
        AlertDialog(onDismissRequest = { open = false; selected = null },
            title = { Text(if (selected == null) tr("ALBERT فارسی · روی دستگاه", "Persian ALBERT · on device") else files.firstOrNull { it.first == selected }?.second.orEmpty()) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selected == null) {
                        Text(tr("تشخیص کارهای داخلی، نه چت عمومی. ثبت‌ها فقط پس از بررسی و تأیید تو انجام می‌شوند. روی دستگاه کم‌حافظه، حالت سبک قبلی فعال می‌ماند.",
                            "Internal task interpretation, not a general chatbot. Writes require your review and approval. Low-memory devices retain the lightweight mode."))
                        files.forEach { (file, title) -> TextButton(onClick = { selected = file }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(title) } }
                    } else {
                        SelectionContainer { Text(text ?: tr("در حال خواندن مجوز…", "Reading licence…"), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { if (selected != null) selected = null else open = false }) { Text(if (selected != null) tr("بازگشت", "Back") else tr("بستن", "Close")) } })
    }
}
