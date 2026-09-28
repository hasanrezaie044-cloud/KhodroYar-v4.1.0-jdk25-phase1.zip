package com.khodroyar.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/** App language: fa (Persian) or en (English). */
val LocalLanguage = compositionLocalOf { "fa" }

@Composable
fun tr(fa: String, en: String): String = if (LocalLanguage.current == "en") en else fa
