package com.khodroyar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.khodroyar.app.ui.screens.findActivity

/** Shared small primitives; no bitmaps, blur layers or continuously running animations. */
@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(modifier, color = color.copy(alpha = 0.10f), contentColor = color, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** On-dark metric, used inside hero panels instead of nesting bright cards in them. */
@Composable
fun HeroStat(label: String, value: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Column(modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(15.dp), tint = Color(0xFFA9C8C7))
            Text(label, color = Color(0xFFB9CFD0), style = MaterialTheme.typography.labelSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Each full-screen surface owns its bar contrast: dark gates, theme-aware application. */
@Composable
fun SystemChrome(dark: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
}
