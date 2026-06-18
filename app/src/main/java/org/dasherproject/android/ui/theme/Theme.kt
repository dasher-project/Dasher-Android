package org.dasherproject.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = DasherTeal,
    onPrimary = DeepNavy,
    secondary = WarmYellow,
    onSecondary = DeepNavy,
    tertiary = CoralRed,
    onTertiary = SurfaceLight,
    background = BgLight,
    onBackground = DeepNavy,
    surface = SurfaceLight,
    onSurface = DeepNavy,
    outline = BorderLight,
)

private val DarkColors = darkColorScheme(
    primary = DasherTeal,
    onPrimary = DeepNavy,
    secondary = WarmYellow,
    onSecondary = DeepNavy,
    tertiary = CoralRed,
    onTertiary = SurfaceDark,
    background = BgDark,
    onBackground = DasherTeal,
    surface = SurfaceDark,
    onSurface = DasherTeal,
    outline = BorderDark,
)

@Composable
fun DasherAndroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DasherTypography,
        content = content
    )
}
