package com.teachmint.sharex.ui.theme

import com.teachmint.sharex.R
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font

private val MaterialTypography = Typography()

@Composable
private fun rememberInterFontFamily(): FontFamily {
    val interRegular = Font(R.font.inter_regular, weight = FontWeight.Normal)
    val interBold = Font(R.font.inter_bold, weight = FontWeight.Bold)

    return remember(interRegular, interBold) {
        FontFamily(
            interRegular,
            interBold
        )
    }
}

@Composable
fun rememberAppTypography(): Typography {
    val interFontFamily = rememberInterFontFamily()

    return remember(interFontFamily) {
        Typography(
            displayLarge = MaterialTypography.displayLarge.copy(fontFamily = interFontFamily),
            displayMedium = MaterialTypography.displayMedium.copy(fontFamily = interFontFamily),
            displaySmall = MaterialTypography.displaySmall.copy(fontFamily = interFontFamily),
            headlineLarge = MaterialTypography.headlineLarge.copy(fontFamily = interFontFamily),
            headlineMedium = MaterialTypography.headlineMedium.copy(fontFamily = interFontFamily),
            headlineSmall = MaterialTypography.headlineSmall.copy(fontFamily = interFontFamily),
            titleLarge = MaterialTypography.titleLarge.copy(fontFamily = interFontFamily),
            titleMedium = MaterialTypography.titleMedium.copy(fontFamily = interFontFamily),
            titleSmall = MaterialTypography.titleSmall.copy(fontFamily = interFontFamily),
            bodyLarge = MaterialTypography.bodyLarge.copy(fontFamily = interFontFamily),
            bodyMedium = MaterialTypography.bodyMedium.copy(fontFamily = interFontFamily),
            bodySmall = MaterialTypography.bodySmall.copy(fontFamily = interFontFamily),
            labelLarge = MaterialTypography.labelLarge.copy(fontFamily = interFontFamily),
            labelMedium = MaterialTypography.labelMedium.copy(fontFamily = interFontFamily),
            labelSmall = MaterialTypography.labelSmall.copy(fontFamily = interFontFamily)
        )
    }
}
