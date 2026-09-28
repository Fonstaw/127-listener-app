package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = TelebirrBlueDark,
    onPrimary = Color(0xFF00325B),
    primaryContainer = TelebirrBlueContainerDark,
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = EthiopianGoldDark,
    onSecondary = Color(0xFF452B00),
    secondaryContainer = Color(0xFF633F00),
    onSecondaryContainer = Color(0xFFFFDF9E),
    tertiary = EmeraldSuccessDark,
    onTertiary = Color(0xFF003822),
    background = SurfaceDark,
    onBackground = Color(0xFFF1F5F9),
    surface = SurfaceCardDark,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFF94A3B8),
    error = CrimsonErrorDark,
    onError = Color(0xFF601410),
    errorContainer = CrimsonErrorBgDark,
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B)
  )

private val LightColorScheme =
  lightColorScheme(
    primary = TelebirrBlue,
    onPrimary = Color.White,
    primaryContainer = TelebirrBlueContainer,
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFFB45309), // Amber/gold
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = Color(0xFF78350F),
    tertiary = EmeraldSuccess,
    onTertiary = Color.White,
    background = SurfaceLight,
    onBackground = Color(0xFF0F172A),
    surface = SurfaceCardLight,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF64748B),
    error = CrimsonError,
    onError = Color.White,
    errorContainer = CrimsonErrorBgLight,
    onErrorContainer = Color(0xFF991B1B),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0)
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Disabling dynamic colors by default so the premium custom theme is visible
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
