package com.offgrid.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class Appearance(val mode: String, val change: (String) -> Unit)
internal val LocalAppearance = staticCompositionLocalOf { Appearance("System") {} }

@Composable
fun OffgridTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("appearance", 0) }
    var mode by remember { mutableStateOf(preferences.getString("mode", "System") ?: "System") }
    val dark = mode == "Dark" || (mode == "System" && isSystemInDarkTheme())
    SideEffect {
        (context as? android.app.Activity)?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val colors = if (dark) darkColorScheme(
        background = Color(0xFF211E28), surface = Color(0xFF302B38),
        onSurface = Color(0xFFF7EEE7), onBackground = Color(0xFFF7EEE7),
        primary = Color(0xFFE4D8EE), onPrimary = Color(0xFF302238),
        secondaryContainer = Color(0xFF6B4233), onSecondaryContainer = Color(0xFFF7EEE7),
        tertiaryContainer = Color(0xFF545331), onTertiaryContainer = Color(0xFFF7EEE7),
        surfaceVariant = Color(0xFF514367), onSurfaceVariant = Color(0xFFC3B5C6),
        outlineVariant = Color(0xFF504458)
    ) else lightColorScheme(
        background = Color(0xFFF7F3EB), surface = Color(0xFFFFFDF8),
        onSurface = Color(0xFF302238), onBackground = Color(0xFF302238),
        primary = Color(0xFF38273F), onPrimary = Color(0xFFFFF9EF),
        secondaryContainer = Color(0xFFFFCFAE), onSecondaryContainer = Color(0xFF302238),
        tertiaryContainer = Color(0xFFEDE9A9), onTertiaryContainer = Color(0xFF302238),
        surfaceVariant = Color(0xFFE1D6F2), onSurfaceVariant = Color(0xFF746974),
        outlineVariant = Color(0xFFDFD5D9)
    )
    val font = FontFamily(Font(R.font.dm_sans))
    val defaults = Typography()
    val typography = Typography(
        displaySmall = defaults.displaySmall.copy(fontFamily = font, fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-1).sp),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = font),
        titleLarge = defaults.titleLarge.copy(fontFamily = font),
        titleMedium = defaults.titleMedium.copy(fontFamily = font),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = font),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = font),
        bodySmall = defaults.bodySmall.copy(fontFamily = font),
        labelLarge = defaults.labelLarge.copy(fontFamily = font),
        labelMedium = defaults.labelMedium.copy(fontFamily = font),
        labelSmall = defaults.labelSmall.copy(fontFamily = font)
    )
    CompositionLocalProvider(LocalAppearance provides Appearance(mode) {
        mode = it
        preferences.edit().putString("mode", it).apply()
    }) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}

@Composable
fun PocketHeading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 12.dp, bottom = 20.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp))
    }
}
