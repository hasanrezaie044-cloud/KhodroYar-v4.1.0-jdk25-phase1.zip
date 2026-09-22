package com.khodroyar.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.prefs.SettingsStore
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.notifications.Notifications
import com.khodroyar.app.security.PinStore
import com.khodroyar.app.ui.screens.AuthGate
import com.khodroyar.app.ui.screens.CarManagerRoot
import com.khodroyar.app.ui.theme.CarManagerTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars so the app's own gradient header fills the
        // cutout / status-bar strip instead of leaving an empty band there. The bars
        // themselves keep working exactly as the system intends; only the background
        // behind them belongs to us now.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val repository = AppRepository(applicationContext)
        val pinStore = PinStore(applicationContext)
        val settingsStore = SettingsStore.get(applicationContext)

        // Channels exist before anything can try to post to them.
        Notifications.ensureChannels(this)

        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            // Theme choice is persisted, so it survives process death; "system" still
            // follows the OS, which is the previous behaviour.
            val settings by settingsStore.state.collectAsState()
            val darkTheme = when (settings.themeMode) {
                AppSettings.THEME_LIGHT -> false
                AppSettings.THEME_DARK -> true
                else -> systemDarkTheme
            }

            // The header gradient is dark in both themes, so the status-bar icons are
            // always drawn light to stay legible over it.
            LaunchedEffect(darkTheme) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            CarManagerTheme(darkTheme = darkTheme) {
                AuthGate(pinStore) {
                    CarManagerRoot(repository) {
                        settingsStore.update {
                            it.copy(
                                themeMode = if (darkTheme) AppSettings.THEME_LIGHT else AppSettings.THEME_DARK
                            )
                        }
                    }
                }
            }
        }
    }
}
