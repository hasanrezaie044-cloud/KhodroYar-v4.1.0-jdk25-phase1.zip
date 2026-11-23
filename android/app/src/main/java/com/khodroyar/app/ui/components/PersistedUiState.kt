package com.khodroyar.app.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Tiny, view-only UI preferences (for example "is this section expanded"). They hold no user
 * data, live outside Room and the backup, and a missing value simply means [default].
 */
object PersistedUiState {
    const val FILE = "khodroyar_ui_state"
    const val MAINTENANCE_PERIODIC_EXPANDED = "maintenance_periodic_expanded"

    fun read(context: Context, key: String, default: Boolean): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(key, default)

    fun write(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }
}

/** A remembered boolean that also survives leaving the screen and restarting the app. */
@Composable
fun rememberPersistedFlag(key: String, default: Boolean): Pair<Boolean, (Boolean) -> Unit> {
    val context = LocalContext.current.applicationContext
    var value by rememberSaveable(key) { mutableStateOf(PersistedUiState.read(context, key, default)) }
    return value to { next: Boolean ->
        value = next
        PersistedUiState.write(context, key, next)
    }
}
