package com.khodroyar.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.khodroyar.app.data.attachments.Attachments

/**
 * Photo preview for an attached file. Decodes a downsampled bitmap off the main thread,
 * and shows a plain notice instead of failing when the file is gone (e.g. a backup was
 * restored on another phone — only the path travels in the JSON backup).
 */
@Composable
fun AttachmentPreview(path: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) { value = Attachments.loadPreview(path) }
    val shape = FieldShape
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp, max = 220.dp),
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "عکس پیوست",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (Attachments.exists(path)) "در حال بارگذاری عکس…"
                    else "فایل عکس روی این دستگاه موجود نیست.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Tap-to-open wrapper used by details screens: preview + open in the gallery viewer. */
@Composable
fun AttachmentPreviewOpenable(path: String, onOpenFailed: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AttachmentPreview(
        path = path,
        modifier = modifier,
        onClick = { if (!Attachments.openInViewer(context, path)) onOpenFailed() },
    )
}
