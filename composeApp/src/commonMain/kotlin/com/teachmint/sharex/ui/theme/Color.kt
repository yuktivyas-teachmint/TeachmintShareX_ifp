package com.teachmint.sharex.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

// Shared palette chosen from the most-used shades in the current app.
val PrimaryAccent = Color(0xFF3D92D1)
val AppBackground = Color(0xFF050608)
val AppSurface = Color(0xFF1A1A1A)
val BackButtonBackground = Color(0xFF1F1F1F)
val AppBorder = Color(0xFF313845)
val TextPrimary = Color(0xFFE6E6E6)
val TextSecondary = Color(0xFF9E9E9E)
val SuccessColor = Color(0xFF4B9C22)
val ErrorColor = Color(0xFFF77474)
val ScreenCastingManagementBackground = Color(0xFF1A1A1A)
val ScreenCastingManagementStroke = Color(0xFF262626)
val IconToggleEnabledTint = Color(0x221DA1F2)
val IconToggleDisabledTint = Color(0xFF222222)
val CardButtonBackground = Color(0xFF111F2A)
val ScreenSharingBackground = Color(0xFF141414)
val BlockedCastingToastTextColor = Color(0xFFE8B339)
val BlockedCastingToastCardColor = Color(0xFF2B2111)
val ScreenCastingManagementCardShadow = Color(0x3B1C8CD1)
val ConnectingDialogTitleColor = Color(0xFF8DD8FF)
val RenameDialogBorder = Color(0xFF36343B)
val RenameDialogDivider = Color(0xFF2B2930)
val RenameDialogTextFieldBorder = Color(0xFF434343)


val ColorScheme.success: Color
    get() = tertiary

val ColorScheme.onSuccess: Color
    get() = onTertiary

val ColorScheme.textSecondary: Color
    get() = onSurfaceVariant
