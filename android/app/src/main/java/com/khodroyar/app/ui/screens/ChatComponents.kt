package com.khodroyar.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.khodroyar.app.R
import com.khodroyar.app.ai.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ChatBrandIcon(brand: String, modifier: Modifier = Modifier.size(22.dp)) {
    val asset = when (brand.lowercase()) {
        "openrouter" -> R.drawable.ai_brand_openrouter
        "gemini" -> R.drawable.ai_brand_gemini
        "google" -> R.drawable.ai_brand_google
        "groq" -> R.drawable.ai_brand_groq
        "claude" -> R.drawable.ai_brand_claude
        "anthropic" -> R.drawable.ai_brand_anthropic
        "fireworks" -> R.drawable.ai_brand_fireworks
        "gpt", "openai" -> R.drawable.ai_brand_openai
        "nvidia" -> R.drawable.ai_brand_nvidia
        "meta" -> R.drawable.ai_brand_meta
        "qwen" -> R.drawable.ai_brand_qwen
        "deepseek" -> R.drawable.ai_brand_deepseek
        "mistral" -> R.drawable.ai_brand_mistral
        else -> null
    }
    if (asset != null) Icon(painterResource(asset), null, modifier = modifier, tint = MaterialTheme.colorScheme.onSurface)
    else Icon(if (brand == "local") Icons.Outlined.OfflineBolt else Icons.Outlined.Memory, null, modifier = modifier)
}

/** A model row intentionally contains exactly a brand mark, model name and real context capacity. */
@Composable
private fun CompactModelRow(item: AIModelView, selectedId: String, enabled: Boolean, onPick: (String) -> Unit, tagPrefix: String) {
    val model = item.model
    val selected = model.key.id == selectedId
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(onClick = { onPick(model.key.id) }, enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            modifier = Modifier.fillMaxWidth().testTag("$tagPrefix${model.key.id}").semantics { this.selected = selected }) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp).heightIn(min = 28.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChatBrandIcon(ChatModelPresentation.brand(model).first)
                Text(model.name.ifBlank { model.key.id }.substringAfter(": "), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(ChatModelPresentation.capacity(model.capability.contextLength), Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr))
                }
            }
        }
    }
}

/** Packs are collapsed independently, rather than a flat provider-wide list. No inference is run here. */
@Composable
internal fun ModelPackList(models: List<AIModelView>, selectedId: String, enabled: Boolean, onPick: (String) -> Unit,
    tagPrefix: String = "ai-chat-model-", includeUnavailable: Boolean = false) {
    val now = System.currentTimeMillis()
    val visible = if (includeUnavailable) models else models.filter { it.selectableAt(now) }
    val packs = remember(visible) { ChatModelPresentation.packs(visible) }
    val initial = packs.firstOrNull { p -> p.models.any { it.model.key.id == selectedId } }?.brand ?: packs.firstOrNull()?.brand
    var open by remember(packs.map { it.brand }) { mutableStateOf(initial) }
    packs.forEach { pack ->
        Column(Modifier.fillMaxWidth().testTag("ai-model-pack-${pack.brand}")) {
            Surface(onClick = { open = if (open == pack.brand) null else pack.brand }, shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChatBrandIcon(pack.brand)
                    Text(pack.title, Modifier.weight(1f).semantics { heading() }, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content))
                    Icon(if (open == pack.brand) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        if (open == pack.brand) "بستن بسته" else "مدل‌های ${pack.title}", Modifier.size(20.dp))
                }
            }
            AnimatedVisibility(open == pack.brand) {
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    pack.models.forEach { item -> CompactModelRow(item, selectedId, enabled && item.selectableAt(now), onPick, tagPrefix) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatModelSheet(mode: AiMode, models: AIModelState, selectedId: String, busy: Boolean, localModelStatus: String,
    onProvider: (AiMode) -> Unit, onPick: (String) -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp), containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("ai-model-sheet")) {
        Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("انتخاب مدل", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onRefresh, enabled = !busy && mode != AiMode.LOCAL, modifier = Modifier.testTag("ai-chat-refresh-models")) {
                    Icon(Icons.Outlined.Refresh, "تازه‌سازی مدل‌ها")
                }
            }
            LazyRow(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AIProviderRegistry.supportedModes, key = { it.name }) { provider ->
                    val descriptor = AIProviderRegistry.get(provider)
                    FilterChip(selected = mode == provider, onClick = { if (provider != mode) onProvider(provider) }, enabled = !busy,
                        label = { Text(descriptor.title) }, leadingIcon = { ChatBrandIcon(descriptor.id, Modifier.size(17.dp)) },
                        modifier = Modifier.testTag("ai-chat-provider-${provider.name}"))
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 22.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), contentPadding = PaddingValues(horizontal = 20.dp)) {
                if (mode == AiMode.LOCAL) item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChatBrandIcon("local"); Column(Modifier.weight(1f)) {
                                Text("دستیار آفلاین")
                                Text(localModelStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }; Text(if (localModelStatus.contains("نصب است")) "نصب‌شده" else "مدل ندارد", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    FilledTonalButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("offline-ai-open-management")) {
                        Text("مدیریت هوش مصنوعی آفلاین")
                    }
                } else item {
                    ModelPackList(models.models, selectedId, !busy, onPick, includeUnavailable = true)
                    if (models.models.isEmpty() && !busy) Text("فهرست مدل‌ها آماده نیست", Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    TextButton(onClick = onSettings, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("ai-model-connection-settings")) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("تنظیم اتصال")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatOptionsSheet(taskCount: Int, enabled: Boolean, onNew: () -> Unit, onHistory: () -> Unit,
    onClear: () -> Unit, onTasks: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 28.dp).testTag("ai-options-menu"),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("گزینه‌های گفتگو", Modifier.padding(start = 14.dp, bottom = 14.dp),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            ChatOption("گفتگوی نو", Icons.Outlined.AddComment, enabled, onNew)
            ChatOption("تاریخچه", Icons.Outlined.History, enabled, onHistory)
            ChatOption("پاک‌سازی گفتگوها", Icons.Outlined.DeleteSweep, enabled, onClear, destructive = true)
            ChatOption("کارهای خودکار", Icons.Outlined.AutoAwesome, enabled, onTasks, count = taskCount)
            HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            ChatOption("تنظیمات هوش مصنوعی و مدل‌ها", Icons.Outlined.Tune, enabled, onSettings)
        }
    }
}

@Composable
private fun ChatOption(title: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit, destructive: Boolean = false, count: Int? = null) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, Modifier.size(23.dp), tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            count?.let {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(it.toString(), Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag("ai-task-count"),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatAttachmentSheet(onCamera: () -> Unit, onGallery: () -> Unit, onFile: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 32.dp).testTag("ai-attachment-menu"),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Triple("دوربین", Icons.Outlined.PhotoCamera, onCamera), Triple("گالری", Icons.Outlined.PhotoLibrary, onGallery), Triple("فایل", Icons.Outlined.AttachFile, onFile))
                .forEachIndexed { index, (name, icon, action) ->
                    Surface(onClick = action, modifier = Modifier.weight(1f).testTag("ai-attachment-${listOf("camera", "gallery", "file")[index]}"),
                        shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(icon, null, Modifier.size(26.dp)); Text(name, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
        }
    }
}

@Composable
internal fun ChatImageThumbnail(file: File, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val image by produceState<android.graphics.Bitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                val options = BitmapFactory.Options().apply {
                    var sample = 1
                    while (bounds.outWidth / sample > 384 || bounds.outHeight / sample > 384) sample *= 2
                    inSampleSize = sample
                }
                BitmapFactory.decodeFile(file.path, options)
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(16.dp)).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center) {
        if (image != null) Image(requireNotNull(image).asImageBitmap(), "تصویر پیوست‌شده", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.BrokenImage, "تصویر محلی در دسترس نیست", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Only the outside Row is LTR: + stays physically left and send/voice physically right in Persian RTL. */
@Composable
internal fun ChatComposer(value: String, onChange: (String) -> Unit, image: File?, imageLoading: Boolean,
    enabled: Boolean, busy: Boolean, placeholder: String, onAdd: () -> Unit, onRemoveImage: () -> Unit,
    onSend: () -> Unit, onVoice: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Surface(shape = RoundedCornerShape(30.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("ai-composer")) {
            Column {
                if (image != null || imageLoading) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (imageLoading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    else Box {
                        ChatImageThumbnail(requireNotNull(image), Modifier.size(76.dp).testTag("ai-draft-image"))
                        FilledIconButton(onClick = onRemoveImage, enabled = !busy, modifier = Modifier.align(Alignment.TopEnd).size(30.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Icon(Icons.Outlined.Close, "حذف تصویر", Modifier.size(17.dp))
                        }
                    }
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onAdd, enabled = enabled, modifier = Modifier.size(48.dp).testTag("ai-attach")) {
                            Icon(Icons.Outlined.Add, "پیوست", Modifier.size(25.dp))
                        }
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                            BasicTextField(value, onChange, enabled = enabled, maxLines = 5,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, textDirection = TextDirection.Content),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 10.dp).heightIn(min = 24.dp).testTag("ai-message-input"),
                                decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
                                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    inner()
                                } })
                        }
                        val send = value.isNotBlank() || image != null
                        FilledIconButton(onClick = if (send) onSend else onVoice, enabled = enabled && !busy && !imageLoading,
                            modifier = Modifier.size(48.dp).testTag(if (send) "ai-send" else "ai-voice"),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                            Icon(if (send) Icons.Filled.ArrowUpward else Icons.Outlined.Mic, if (send) "ارسال" else "تایپ صوتی", Modifier.size(23.dp))
                        }
                    }
                }
            }
        }
    }
}
