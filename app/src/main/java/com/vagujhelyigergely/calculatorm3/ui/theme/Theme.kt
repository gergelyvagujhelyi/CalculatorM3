package com.vagujhelyigergely.calculatorm3.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Material 3 Expressive Purple Palette
val Purple10 = Color(0xFF21005D)
val Purple20 = Color(0xFF381E72)
val Purple30 = Color(0xFF4F378B)
val Purple40 = Color(0xFF6750A4)
val Purple80 = Color(0xFFD0BCFF)
val Purple90 = Color(0xFFEADDFF)
val Purple95 = Color(0xFFF6EDFF)
val Purple99 = Color(0xFFFFFBFE)

val PurpleGrey30 = Color(0xFF332D41)
val PurpleGrey40 = Color(0xFF4A4458)
val PurpleGrey50 = Color(0xFF625B71)
val PurpleGrey60 = Color(0xFF7A7289)
val PurpleGrey80 = Color(0xFFCAC4D0)
val PurpleGrey90 = Color(0xFFE8DEF8)
val PurpleGrey95 = Color(0xFFF6EDFF)

val Pink30 = Color(0xFF633B48)
val Pink40 = Color(0xFF7D5260)
val Pink80 = Color(0xFFEFB8C8)
val Pink90 = Color(0xFFFFD8E4)

val Red40 = Color(0xFFB3261E)
val Red80 = Color(0xFFF2B8B5)
val Red90 = Color(0xFFF9DEDC)

val Neutral6 = Color(0xFF141218)
val Neutral10 = Color(0xFF1D1B20)
val Neutral12 = Color(0xFF211F26)
val Neutral17 = Color(0xFF2B2930)
val Neutral22 = Color(0xFF36343B)
val Neutral87 = Color(0xFFDED8E1)
val Neutral90 = Color(0xFFE6E0E9)
val Neutral92 = Color(0xFFECE6F0)
val Neutral94 = Color(0xFFF3EDF7)
val Neutral96 = Color(0xFFF7F2FA)
val Neutral98 = Color(0xFFFEF7FF)
val Neutral99 = Color(0xFFFFFBFE)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    onPrimary = Color.White,
    primaryContainer = Purple90,
    onPrimaryContainer = Purple10,
    secondary = PurpleGrey50,
    onSecondary = Color.White,
    secondaryContainer = PurpleGrey90,
    onSecondaryContainer = PurpleGrey30,
    tertiary = Pink40,
    onTertiary = Color.White,
    tertiaryContainer = Pink90,
    onTertiaryContainer = Pink30,
    error = Red40,
    errorContainer = Red90,
    surface = Neutral98,
    surfaceVariant = Neutral90,
    onSurface = Neutral10,
    onSurfaceVariant = PurpleGrey50,
    outline = PurpleGrey60,
    outlineVariant = PurpleGrey80,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Neutral96,
    surfaceContainer = Neutral94,
    surfaceContainerHigh = Neutral92,
    surfaceContainerHighest = Neutral90,
)

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    onPrimary = Purple20,
    primaryContainer = Purple30,
    onPrimaryContainer = Purple90,
    secondary = PurpleGrey80,
    onSecondary = PurpleGrey30,
    secondaryContainer = PurpleGrey40,
    onSecondaryContainer = PurpleGrey90,
    tertiary = Pink80,
    onTertiary = Pink30,
    tertiaryContainer = Pink40,
    onTertiaryContainer = Pink90,
    error = Red80,
    errorContainer = Red40,
    surface = Neutral6,
    surfaceVariant = PurpleGrey40,
    onSurface = Neutral90,
    onSurfaceVariant = PurpleGrey80,
    outline = PurpleGrey60,
    outlineVariant = PurpleGrey40,
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Neutral12,
    surfaceContainer = Neutral17,
    surfaceContainerHigh = Neutral22,
    surfaceContainerHighest = Color(0xFF3B383E),
)

@Composable
fun CalculatorM3Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
