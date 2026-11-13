package com.khodroyar.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.khodroyar.app.ui.components.FieldShape
import com.khodroyar.app.ui.components.CardShape
import com.khodroyar.app.ui.components.IconBadge
import com.khodroyar.app.ui.components.tr

/**
 * Terms of use shown once, before anything else (before the PIN screen, so it also
 * covers installs that run with the lock off). Accepting stores [TermsOfUse.VERSION];
 * raise the version when the text changes materially and everyone is asked again.
 */
object TermsOfUse {
    const val VERSION = com.khodroyar.app.core.privacy.AppPrivacyPolicy.VERSION
    const val TITLE = com.khodroyar.app.core.privacy.AppPrivacyPolicy.TITLE
    const val INTRO = com.khodroyar.app.core.privacy.AppPrivacyPolicy.INTRO
    val sections = com.khodroyar.app.core.privacy.AppPrivacyPolicy.sections
}

/** Intro + all sections, coloured by the caller so it works on the dark gate and in a dialog. */
@Composable
private fun TermsText(headingColor: Color, bodyColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(TermsOfUse.INTRO, color = bodyColor, style = MaterialTheme.typography.bodyMedium)
        TermsOfUse.sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    section.title,
                    color = headingColor,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(section.body, color = bodyColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Full-screen gate styled like the login screen. The text card takes whatever height
 * is left and scrolls, while the checkbox and both buttons stay pinned below it — so
 * nothing is pushed off-screen at a large system font scale.
 */
@Composable
fun AgreementScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    var agreed by rememberSaveable { mutableStateOf(false) }

    GateBackdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Outlined.Shield, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(TermsOfUse.TITLE, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("پیش از شروع، با آگاهی ادامه دهید", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = CardShape,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().weight(1f)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                    TermsText(headingColor = MaterialTheme.colorScheme.primary, bodyColor = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().testTag("terms-agree").toggleable(value = agreed, role = Role.Checkbox, onValueChange = { agreed = it })
                    .heightIn(min = 52.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = agreed, onCheckedChange = null)
                    Spacer(Modifier.width(10.dp))
                    Text("متن بالا را خوانده‌ام و می‌پذیرم", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = onAccept, enabled = agreed, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("terms-continue"), shape = FieldShape) {
                    Text("می‌پذیرم و ادامه")
                }
                TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("نمی‌پذیرم و خروج", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Settings → About: lets the owner re-read the accepted text at any time. */
@Composable
fun TermsLinkCard() {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { show = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = FieldShape,
    ) {
        Icon(Icons.Outlined.Shield, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(tr(TermsOfUse.TITLE, "Privacy & disclaimer"))
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(TermsOfUse.TITLE) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    TermsText(
                        headingColor = MaterialTheme.colorScheme.primary,
                        bodyColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text("بستن") } },
        )
    }
}
