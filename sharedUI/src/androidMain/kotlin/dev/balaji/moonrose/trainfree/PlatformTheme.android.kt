package dev.balaji.moonrose.trainfree

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.balaji.moonrose.trainfree.model.AppFont
import dev.balaji.moonrose.trainfree.model.AppFontSize
import dev.balaji.moonrose.trainfree.model.Appearance
import dev.balaji.moonrose.trainfree.sharedUI.R

// Wallpaper-derived dynamic palettes often produce a muted primary that makes
// link-style text hard to spot, so pin primary to a vivid blue in both modes.
private val LightLinkBlue = Color(0xFF007AFF)
private val DarkLinkBlue = Color(0xFF0A84FF)

private val GoogleSansFontFamily = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_medium, FontWeight.Medium),
    Font(R.font.google_sans_semibold, FontWeight.SemiBold),
    Font(R.font.google_sans_bold, FontWeight.Bold),
)

private val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun AppFont.toFontFamily(): FontFamily = when (this) {
    AppFont.SYSTEM_UI -> FontFamily.Default
    AppFont.GOOGLE_SANS -> GoogleSansFontFamily
    AppFont.INTER -> InterFontFamily
}

private fun TextStyle.scaled(factor: Float): TextStyle =
    if (factor == 1f) {
        this
    } else {
        copy(
            fontSize = fontSize * factor,
            lineHeight = lineHeight * factor,
        )
    }

private fun Typography.scaled(factor: Float): Typography =
    if (factor == 1f) {
        this
    } else {
        copy(
            displayLarge = displayLarge.scaled(factor),
            displayMedium = displayMedium.scaled(factor),
            displaySmall = displaySmall.scaled(factor),
            headlineLarge = headlineLarge.scaled(factor),
            headlineMedium = headlineMedium.scaled(factor),
            headlineSmall = headlineSmall.scaled(factor),
            titleLarge = titleLarge.scaled(factor),
            titleMedium = titleMedium.scaled(factor),
            titleSmall = titleSmall.scaled(factor),
            bodyLarge = bodyLarge.scaled(factor),
            bodyMedium = bodyMedium.scaled(factor),
            bodySmall = bodySmall.scaled(factor),
            labelLarge = labelLarge.scaled(factor),
            labelMedium = labelMedium.scaled(factor),
            labelSmall = labelSmall.scaled(factor),
            displayLargeEmphasized = displayLargeEmphasized.scaled(factor),
            displayMediumEmphasized = displayMediumEmphasized.scaled(factor),
            displaySmallEmphasized = displaySmallEmphasized.scaled(factor),
            headlineLargeEmphasized = headlineLargeEmphasized.scaled(factor),
            headlineMediumEmphasized = headlineMediumEmphasized.scaled(factor),
            headlineSmallEmphasized = headlineSmallEmphasized.scaled(factor),
            titleLargeEmphasized = titleLargeEmphasized.scaled(factor),
            titleMediumEmphasized = titleMediumEmphasized.scaled(factor),
            titleSmallEmphasized = titleSmallEmphasized.scaled(factor),
            bodyLargeEmphasized = bodyLargeEmphasized.scaled(factor),
            bodyMediumEmphasized = bodyMediumEmphasized.scaled(factor),
            bodySmallEmphasized = bodySmallEmphasized.scaled(factor),
            labelLargeEmphasized = labelLargeEmphasized.scaled(factor),
            labelMediumEmphasized = labelMediumEmphasized.scaled(factor),
            labelSmallEmphasized = labelSmallEmphasized.scaled(factor),
        )
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
actual fun PlatformTheme(
    appearance: Appearance,
    appFont: AppFont,
    appFontSize: AppFontSize,
    content: @Composable () -> Unit,
) {
    val dark = appearance.isDark(isSystemInDarkTheme())
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }.let {
        it.copy(
            primary = if (dark) DarkLinkBlue else LightLinkBlue,
            onPrimary = Color.White,
        )
    }
    val typography = remember(appFont, appFontSize) {
        Typography(fontFamily = appFont.toFontFamily()).scaled(appFontSize.scaleFactor)
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = typography,
        content = content,
    )
}
