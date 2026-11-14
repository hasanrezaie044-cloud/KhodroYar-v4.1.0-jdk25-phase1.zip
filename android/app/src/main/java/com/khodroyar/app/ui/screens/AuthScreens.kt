package com.khodroyar.app.ui.screens

import com.khodroyar.app.ui.components.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.khodroyar.app.data.prefs.SettingsStore
import com.khodroyar.app.security.BiometricGate
import com.khodroyar.app.security.PinStore
import com.khodroyar.app.ui.components.FieldShape
import com.khodroyar.app.ui.components.SurfaceCard
import com.khodroyar.app.ui.components.IconBadge
import com.khodroyar.app.ui.components.fieldColors
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.khodroyar.app.ui.components.toLatinDigits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gate order: terms of use (once per [TermsOfUse.VERSION]) -> PIN / biometric (the
 * fingerprint prompt opens by itself) -> one-time offline profile setup (first + last
 * name) so the app can greet the owner personally.
 * Everything stays on-device — no network.
 */
@Composable
fun AuthGate(pinStore: PinStore, settingsStore: SettingsStore, content: @Composable () -> Unit) {
    var authenticated by remember { mutableStateOf(!pinStore.lockEnabled) }
    val settings by settingsStore.state.collectAsState()
    val needsProfile = authenticated && !settings.hasProfile
    val context = LocalContext.current

    when {
        settings.acceptedTermsVersion < TermsOfUse.VERSION -> AgreementScreen(
            onAccept = { settingsStore.update { it.copy(acceptedTermsVersion = TermsOfUse.VERSION) } },
            onDecline = { context.findActivity()?.finishAffinity() },
        )
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

    GateBackdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            GateBrand()
            Spacer(Modifier.height(32.dp))
            Text("از آشنایی با شما خوشحالیم", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("پروفایل شخصی‌تان را بسازید", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            SurfaceCard(modifier = Modifier.widthIn(max = 420.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(value = first, onValueChange = { first = it; error = null },
                        modifier = Modifier.fillMaxWidth().testTag("profile-first-name"), label = { Text("نام") }, singleLine = true, shape = FieldShape, colors = fieldColors())
                    OutlinedTextField(value = last, onValueChange = { last = it; error = null },
                        modifier = Modifier.fillMaxWidth().testTag("profile-last-name"), label = { Text("نام خانوادگی") }, singleLine = true, shape = FieldShape, colors = fieldColors())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    Button(onClick = {
                        val f = first.trim(); val l = last.trim()
                        when {
                            f.isEmpty() -> error = "لطفاً نام را وارد کنید"
                            l.isEmpty() -> error = "لطفاً نام خانوادگی را وارد کنید"
                            else -> onSave(f, l)
                        }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("profile-save"), shape = FieldShape) { Text("شروع مسیر") }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("این پروفایل فقط روی گوشی شما ذخیره می‌شود", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The lock screen's logic. The look lives in [AnimatedLoginScreen]; what happens with the
 * digits stays here and uses the existing [PinStore] (PBKDF2 hash in encrypted prefs) and
 * [BiometricGate] (the real BiometricPrompt) — nothing is hard-coded or bypassed.
 *
 * PIN length: new PINs are exactly [LOGIN_PIN_LENGTH] digits and are checked automatically
 * after the fifth. A PIN saved by an older version may be 4 digits long, so from the fourth
 * digit on each prefix is also tried silently (a wrong prefix costs nothing — there is no
 * attempt counter); only a wrong fifth digit shows the error.
 */
@Composable
private fun LoginScreen(pinStore: PinStore, onSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val needsSetup = !pinStore.isPinSet
    var pin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    var errorTick by remember { mutableIntStateOf(0) }
    // While a result is playing (success or error animation) typed input is ignored.
    var locked by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }

    fun finishSuccess() {
        if (success) return
        success = true
        locked = true
        scope.launch {
            delay(320)
            onSuccess()
        }
    }

    fun fail(message: String) {
        locked = true
        errorMessage = message
        errorTick++
        scope.launch {
            delay(650)
            pin = ""
            locked = false
        }
    }

    val canUseFingerprint = remember(needsSetup) {
        !needsSetup && context is androidx.fragment.app.FragmentActivity && BiometricGate.isAvailable(context)
    }
    val promptBiometric: () -> Unit = {
        val activity = context as? androidx.fragment.app.FragmentActivity
        if (activity != null && !success) BiometricGate.prompt(activity, onSuccess = { finishSuccess() })
    }

    // Open the fingerprint prompt by itself when the lock screen appears. Only once
    // (rememberSaveable survives rotation), and never while the PIN is still being
    // created; the fingerprint button stays as the manual retry. The short delay lets the
    // window take focus first — a prompt shown on the very first frame can be cancelled
    // by the system before the user sees it.
    var autoPrompted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(needsSetup) {
        if (canUseFingerprint && !autoPrompted) {
            autoPrompted = true
            delay(300)
            promptBiometric()
        }
    }

    val onPinChange: (String) -> Unit = change@{ raw ->
        if (locked) return@change
        val digits = raw.toLatinDigits().filter { it in '0'..'9' }.take(LOGIN_PIN_LENGTH)
        pin = digits
        if (digits.isNotEmpty() && errorMessage.isNotEmpty()) errorMessage = ""
        if (needsSetup) {
            if (digits.length == LOGIN_PIN_LENGTH) {
                locked = true
                scope.launch {
                    delay(220) // let the fifth dot finish its animation
                    val first = firstPin
                    when {
                        first == null -> {
                            firstPin = digits
                            pin = ""
                            locked = false
                        }
                        first == digits -> {
                            pinStore.setPin(digits)
                            success = true
                            delay(320)
                            onSuccess()
                        }
                        else -> {
                            firstPin = null
                            fail("دو رمز یکسان نیستند")
                        }
                    }
                }
            }
        } else if (digits.length >= 4) {
            val attempt = digits
            scope.launch {
                val ok = withContext(Dispatchers.Default) { pinStore.verify(attempt) }
                if (success || pin != attempt) return@launch
                if (ok) finishSuccess()
                else if (attempt.length == LOGIN_PIN_LENGTH) fail("رمز عبور اشتباه است")
            }
        }
    }

    AnimatedLoginScreen(
        pin = pin,
        onPinChange = onPinChange,
        welcome = "خوش آمدید",
        prompt = when {
            needsSetup && firstPin != null -> "رمز عبور را دوباره وارد کنید"
            needsSetup -> "یک رمز عبور ۵ رقمی بسازید"
            else -> "لطفاً رمز عبور ۵ رقمی خود را وارد کنید"
        },
        errorMessage = errorMessage,
        errorTick = errorTick,
        success = success,
        showFingerprint = canUseFingerprint,
        onFingerprint = promptBiometric,
    )
}
