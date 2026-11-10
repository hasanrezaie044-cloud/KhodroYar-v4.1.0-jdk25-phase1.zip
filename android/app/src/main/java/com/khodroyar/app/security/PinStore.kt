package com.khodroyar.app.security

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PIN storage and verification.
 *
 * Upgraded from the JS version, which stored `SHA256(pin + "car_salt_2024")` — a
 * single fixed salt and a single hash round, i.e. trivially brute-forceable for a
 * 4-digit PIN. This uses PBKDF2-HMAC-SHA256 with a per-install random salt, kept in
 * `EncryptedSharedPreferences` (Android Keystore backed).
 *
 * The old format is still accepted once, transparently: an existing legacy hash
 * verifies against the old scheme and is immediately re-hashed with the new one, so
 * nobody is locked out by the upgrade.
 */
class PinStore(context: Context) {

    private val prefs by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "car_secure_prefs", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    companion object {
        private const val K_HASH = "pin_hash"
        private const val K_SALT = "pin_salt"
        private const val K_LEGACY = "pin_legacy_hash"
        private const val K_LOCK = "lock_enabled"
        private const val LEGACY_SALT = "car_salt_2024"
        private const val ITERATIONS = 120_000
        private const val KEY_BITS = 256
    }

    val isPinSet: Boolean get() = prefs.contains(K_HASH) || prefs.contains(K_LEGACY)

    /** Lock defaults to ON, preserving the previous behaviour. */
    var lockEnabled: Boolean
        get() = prefs.getBoolean(K_LOCK, true)
        set(v) { prefs.edit().putBoolean(K_LOCK, v).apply() }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        prefs.edit()
            .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(K_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .remove(K_LEGACY)
            .apply()
    }

    /** Seeds a legacy SHA-256 hash carried over from the React Native install. */
    fun importLegacyHash(legacyHex: String) {
        if (legacyHex.isBlank()) return
        prefs.edit().putString(K_LEGACY, legacyHex.lowercase()).remove(K_HASH).remove(K_SALT).apply()
    }

    fun verify(pin: String): Boolean {
        prefs.getString(K_HASH, null)?.let { stored ->
            val salt = prefs.getString(K_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
                ?: return false
            return constantTimeEquals(Base64.decode(stored, Base64.NO_WRAP), pbkdf2(pin, salt))
        }
        prefs.getString(K_LEGACY, null)?.let { legacy ->
            if (constantTimeEquals(legacy.toByteArray(), legacySha256(pin).toByteArray())) {
                setPin(pin) // transparent upgrade to PBKDF2
                return true
            }
            return false
        }
        return false
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    private fun legacySha256(pin: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest((pin + LEGACY_SALT).toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun clear() { prefs.edit().clear().apply() }
}
