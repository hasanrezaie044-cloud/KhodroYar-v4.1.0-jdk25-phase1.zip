package com.khodroyar.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.khodroyar.app.traffic.TrafficRepository
import com.khodroyar.app.traffic.TrafficLevel
import com.khodroyar.app.traffic.RouteStatus
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.notifications.TrafficCheckScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private val CORRIDORS = TrafficRepository.CORRIDORS

@Composable
fun TrafficAssistantScreen(repository: AppRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val traffic = remember { TrafficRepository() }
    val settings by repository.settings.collectAsState(initial = repository.settingsNow)

    var apiKeyDraft by remember(settings.neshanApiKey) { mutableStateOf(settings.neshanApiKey) }
    var showKey by remember { mutableStateOf(false) }
    var keySavedMsg by remember { mutableStateOf<String?>(null) }

    var statuses by remember { mutableStateOf<Map<String, RouteStatus>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var lastUpdated by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoRefresh by remember { mutableStateOf(false) }

    fun refresh() {
        val key = settings.neshanApiKey.trim()
        if (key.isBlank()) {
            error = "ابتدا کلید API نشان را وارد و ذخیره کنید"
            return
        }
        scope.launch {
            loading = true
            error = null
            try {
                statuses = traffic.fetch(key).statuses
                lastUpdated = java.text.SimpleDateFormat("HH:mm", java.util.Locale("fa")).format(java.util.Date())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "دریافت وضعیت معتبر مسیر انجام نشد؛ اینترنت و کلید نشان را بررسی کنید."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(settings.neshanApiKey) {
        if (settings.neshanApiKey.isNotBlank()) refresh()
    }
    LaunchedEffect(autoRefresh, settings.neshanApiKey) {
        while (autoRefresh && settings.neshanApiKey.isNotBlank()) {
            delay(TimeUnit.MINUTES.toMillis(15))
            if (autoRefresh) refresh()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("کلید API نشان", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it; keySavedMsg = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Api-Key") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null)
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        val trimmed = apiKeyDraft.trim()
                        repository.updateSettings { it.copy(neshanApiKey = trimmed) }
                        TrafficCheckScheduler.sync(context)
                        keySavedMsg = if (trimmed.isEmpty()) "کلید پاک شد" else "کلید ذخیره شد"
                    }) { Text("ذخیره کلید") }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.neshan.org/")))
                    }) { Text("دریافت کلید") }
                }
                keySavedMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }


        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("اعلان خودکار ترافیک", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Switch(
                    checked = settings.trafficNotifyEnabled,
                    onCheckedChange = { on ->
                        repository.updateSettings { it.copy(trafficNotifyEnabled = on) }
                        TrafficCheckScheduler.sync(context)
                    },
                    enabled = settings.neshanApiKey.isNotBlank(),
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { refresh() },
                enabled = !loading && settings.neshanApiKey.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (loading) "در حال بررسی…" else "بروزرسانی")
            }
            FilterChip(
                selected = autoRefresh,
                onClick = { autoRefresh = !autoRefresh },
                enabled = settings.neshanApiKey.isNotBlank(),
                label = { Text("خودکار ۱۵ دقیقه") },
            )
        }

        lastUpdated?.let {
            Text("آخرین بروزرسانی: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        CORRIDORS.forEach { c ->
            RouteStatusCard(
                title = c.title,
                status = statuses[c.id],
                loading = loading && statuses[c.id] == null,
            )
        }

        OutlinedButton(
            onClick = {
                val c = CORRIDORS[1]
                val uri = Uri.parse(
                    "https://neshan.org/maps/routing/car/origin/${c.originLat},${c.originLng}/destination/${c.destLat},${c.destLng}"
                )
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("باز کردن مسیر ذوب‌آهن در نشان")
        }
    }
}

@Composable
private fun RouteStatusCard(title: String, status: RouteStatus?, loading: Boolean) {
    val color by animateColorAsState(
        when (status?.level) {
            TrafficLevel.SMOOTH -> Color(0xFF2E7D32)
            TrafficLevel.MODERATE -> Color(0xFFF9A825)
            TrafficLevel.HEAVY -> Color(0xFFC62828)
            null -> MaterialTheme.colorScheme.outline
        },
        label = "statusColor",
    )
    val bg by animateColorAsState(
        when (status?.level) {
            TrafficLevel.SMOOTH -> Color(0x1A2E7D32)
            TrafficLevel.MODERATE -> Color(0x1AF9A825)
            TrafficLevel.HEAVY -> Color(0x1AC62828)
            null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        label = "statusBg",
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                status != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(10.dp))
                        Text(status.labelFa, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
                    }
                    status.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> Text("هنوز بررسی نشده", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
