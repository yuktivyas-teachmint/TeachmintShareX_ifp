package com.teachmint.sharex.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = PrimaryAccent,
    onPrimary = TextPrimary,
    primaryContainer = PrimaryAccent,
    onPrimaryContainer = TextPrimary,
    secondary = TextSecondary,
    onSecondary = AppBackground,
    tertiary = SuccessColor,
    onTertiary = TextPrimary,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = AppSurface,
    onSurface = TextPrimary,
    surfaceVariant = AppSurface,
    onSurfaceVariant = TextSecondary,
    outline = AppBorder,
    error = ErrorColor,
    onError = TextPrimary
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryAccent,
    onPrimary = TextPrimary,
    primaryContainer = PrimaryAccent,
    onPrimaryContainer = TextPrimary,
    secondary = TextSecondary,
    onSecondary = AppBackground,
    tertiary = SuccessColor,
    onTertiary = TextPrimary,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = AppSurface,
    onSurface = TextPrimary,
    surfaceVariant = AppSurface,
    onSurfaceVariant = TextSecondary,
    outline = AppBorder,
    error = ErrorColor,
    onError = TextPrimary
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val typography = rememberAppTypography()
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
