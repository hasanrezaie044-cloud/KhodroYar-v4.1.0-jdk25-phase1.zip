package com.khodroyar.app.ui.screens

import com.khodroyar.app.ui.components.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.khodroyar.app.R
import com.khodroyar.app.data.prefs.SettingsStore
import com.khodroyar.app.security.BiometricGate
import com.khodroyar.app.security.PinStore
import com.khodroyar.app.ui.components.FieldShape
import com.khodroyar.app.ui.components.toLatinDigits

/**
 * Auth gate: PIN / biometric first, then a one-time offline profile setup
 * (first + last name) so the app can greet the owner personally.
 * Everything stays on-device — no network.
 */
@Composable
fun AuthGate(pinStore: PinStore, settingsStore: SettingsStore, content: @Composable () -> Unit) {
    var authenticated by remember { mutableStateOf(!pinStore.lockEnabled) }
    val settings by settingsStore.state.collectAsState()
    val needsProfile = authenticated && !settings.hasProfile

    when {
        !authenticated -> LoginScreen(pinStore) { authenticated = true }
        needsProfile -> ProfileSetupScreen(
            initialFirst = settings.userFirstName,
            initialLast = settings.userLastName,
            onSave = { first, last ->
                settingsStore.update {
                    it.copy(
                        userFirstName = first.trim(),
                        userLastName = last.trim(),
                    )
                }
            },
        )
        else -> content()
    }
}

@Composable
private fun ProfileSetupScreen(
    initialFirst: String,
    initialLast: String,
    onSave: (String, String) -> Unit,
) {
    var first by remember { mutableStateOf(initialFirst) }
    var last by remember { mutableStateOf(initialLast) }
    var error by remember { mutableStateOf<String?>(null) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(Color(0xFF05090B))) {
            Image(
                painter = painterResource(R.drawable.login_background),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xCC05090B), Color(0xE605090B), Color(0xF205090B)),
                        ),
                    ),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0x33A8D5C2)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Person, null, tint = Color(0xFFA8D5C2), modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "خوش آمدید",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "نام خود را وارد کنید تا اپ شما را بشناسد.\nهمه‌چیز فقط روی همین دستگاه ذخیره می‌شود.",
                    color = Color(0xFFB8C8C4),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(22.dp))
                Surface(
                    color = Color(0xE6121A1C),
                    shape = FieldShape,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = first,
                            onValueChange = { first = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("نام") },
                            singleLine = true,
                            shape = FieldShape,
                            colors = profileFieldColors(),
                        )
                        OutlinedTextField(
                            value = last,
                            onValueChange = { last = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("نام خانوادگی") },
                            singleLine = true,
                            shape = FieldShape,
                            colors = profileFieldColors(),
                        )
                        error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(
                            onClick = {
                                val f = first.trim()
                                val l = last.trim()
                                when {
                                    f.isEmpty() -> error = "لطفاً نام را وارد کنید"
                                    l.isEmpty() -> error = "لطفاً نام خانوادگی را وارد کنید"
                                    else -> onSave(f, l)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = FieldShape,
                        ) { Text("شروع کنید") }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "کاملاً آفلاین · بدون اینترنت",
                    color = Color(0xFF8FA09D),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun profileFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Color(0xFFA8D5C2),
    unfocusedBorderColor = Color(0xFF3A4A48),
    focusedLabelColor = Color(0xFFA8D5C2),
    unfocusedLabelColor = Color(0xFF8FA09D),
    cursorColor = Color(0xFFA8D5C2),
)

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
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x9905090B), Color(0xCC05090B), Color(0xF205090B)),
                        ),
                    ),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(CircleShape),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "مدیریت خودرو",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        needsSetup && firstPin != null -> "رمز را دوباره وارد کنید"
                        needsSetup -> "یک PIN امن برای محافظت از اطلاعات انتخاب کنید"
                        else -> "برای ورود PIN خود را وارد کنید"
                    },
                    color = Color(0xFFB8C8C4),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(22.dp))
                Surface(
                    color = Color(0xE6121A1C),
                    shape = FieldShape,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp,
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
                            colors = profileFieldColors(),
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
