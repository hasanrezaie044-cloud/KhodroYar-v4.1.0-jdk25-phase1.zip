package com.khodroyar.app.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Native BiometricPrompt wrapper, replacing expo-local-authentication.
 * Device-credential fallback is allowed, matching `disableDeviceFallback: false`
 * in the JS implementation.
 */
object BiometricGate {

    private const val ALLOWED =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAvailable(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit = {},
    ) {
        if (!isAvailable(activity)) { onFailure(null); return }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(code: Int, msg: CharSequence) = onFailure(msg.toString())
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("تأیید هویت برای ورود به مدیریت خودرو")
            .setAllowedAuthenticators(ALLOWED)
            .build()
        prompt.authenticate(info)
    }
}
