package com.f2l.downloader

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Arial-style sans-serif for the whole app.
 * Liberation Sans is metric-compatible with Arial (SIL OFL license) and is bundled, so the app looks the same
 * on every phone and ignores the device's custom / handwriting system font.
 */
val AppFont = FontFamily(
    Font(R.font.liberation_sans_regular, FontWeight.Normal),
    Font(R.font.liberation_sans_regular, FontWeight.Medium),
    Font(R.font.liberation_sans_bold, FontWeight.SemiBold),
    Font(R.font.liberation_sans_bold, FontWeight.Bold)
)

val AppTypography: Typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = AppFont),
        displayMedium = t.displayMedium.copy(fontFamily = AppFont),
        displaySmall = t.displaySmall.copy(fontFamily = AppFont),
        headlineLarge = t.headlineLarge.copy(fontFamily = AppFont),
        headlineMedium = t.headlineMedium.copy(fontFamily = AppFont),
        headlineSmall = t.headlineSmall.copy(fontFamily = AppFont),
        titleLarge = t.titleLarge.copy(fontFamily = AppFont),
        titleMedium = t.titleMedium.copy(fontFamily = AppFont),
        titleSmall = t.titleSmall.copy(fontFamily = AppFont),
        bodyLarge = t.bodyLarge.copy(fontFamily = AppFont),
        bodyMedium = t.bodyMedium.copy(fontFamily = AppFont),
        bodySmall = t.bodySmall.copy(fontFamily = AppFont),
        labelLarge = t.labelLarge.copy(fontFamily = AppFont),
        labelMedium = t.labelMedium.copy(fontFamily = AppFont),
        labelSmall = t.labelSmall.copy(fontFamily = AppFont)
    )
}
