package com.khodroyar.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.khodroyar.app.R
import com.khodroyar.app.security.BiometricGate
import com.khodroyar.app.security.PinStore
import com.khodroyar.app.ui.components.FieldShape
import com.khodroyar.app.ui.components.toLatinDigits

/**
 * Auth gate. Unchanged behaviour (PBKDF2 PIN + BiometricPrompt, lock can be off);
 * the screen itself now uses the supplied login artwork as its background and the
 * app icon as its badge.
 */
@Composable
fun AuthGate(pinStore: PinStore, content: @Composable () -> Unit) {
    var authenticated by remember { mutableStateOf(!pinStore.lockEnabled) }
    if (authenticated) content() else LoginScreen(pinStore) { authenticated = true }
}

@Composable
private fun LoginScreen(pinStore: PinStore, onSuccess: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val needsSetup = !pinStore.isPinSet

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(Color(0xFF05090B))) {
            Image(
                painter = painterResource(R.drawable.login_background),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Scrim keeps the Persian text readable over the artwork.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color(0x66000000), Color(0xCC03080A), Color(0xF2010405)),
                    )
                )
            )
            Column(
                // The artwork stays full-bleed behind the system bars; the content is
                // inset so nothing lands under the notch or the navigation bar.
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // R.mipmap.ic_launcher resolves to mipmap-anydpi-v26/ic_launcher.xml
                // (<adaptive-icon>) on Android 8+. painterResource() accepts only
                // VectorDrawables or rasterised assets, so it threw while composing
                // this screen. Same badge, built from the icon's own two layers:
                // background colour + foreground bitmap, masked to a circle exactly
                // like the launcher does.
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF05090B)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = "مدیریت خودرو",
                        modifier = Modifier.size(144.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(tr("مدیریت خودرو", "KhodroYar"), color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        needsSetup && firstPin != null -> "رمز را دوباره وارد کنید"
                        needsSetup -> "یک PIN امن برای محافظت از اطلاعات انتخاب کنید"
                        else -> "برای ادامه، PIN را وارد کنید"
                    },
                    color = Color(0xFFB9C7C4),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(22.dp))
                Surface(
                    color = Color(0xE6101819),
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { if (it.length <= 8) pin = it.toLatinDigits() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("PIN") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = FieldShape,
                        )
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        Button(
                            onClick = {
                                if (pin.length < 4) { error = "PIN باید حداقل ۴ رقم باشد"; return@Button }
                                if (needsSetup) {
                                    val first = firstPin
                                    if (first == null) { firstPin = pin; pin = ""; error = null }
                                    else if (first == pin) { pinStore.setPin(pin); onSuccess() }
                                    else { firstPin = null; pin = ""; error = "دو PIN یکسان نیستند" }
                                } else if (pinStore.verify(pin)) {
                                    onSuccess()
                                } else {
                                    pin = ""; error = "PIN اشتباه است"
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = FieldShape,
                        ) { Text(if (needsSetup) "ادامه" else "ورود") }

                        if (!needsSetup && context is androidx.fragment.app.FragmentActivity &&
                            BiometricGate.isAvailable(context)
                        ) {
                            TextButton(onClick = { BiometricGate.prompt(context, onSuccess = onSuccess) }) {
                                Icon(Icons.Outlined.Fingerprint, null)
                                Spacer(Modifier.width(8.dp))
                                Text("ورود با اثر انگشت")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "کاملاً آفلاین · داده‌ها روی همین دستگاه می‌مانند",
                    color = Color(0xFF8FA09D),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
