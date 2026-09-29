package ir.mehran.pakyar

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.vazirmatn_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.vazirmatn_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.vazirmatn_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

private val Light = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF155E75), onPrimary = androidx.compose.ui.graphics.Color.White, secondary = androidx.compose.ui.graphics.Color(0xFF3F5F5F), error = androidx.compose.ui.graphics.Color(0xFFB3261E))
private val Dark = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF67D5EE), onPrimary = androidx.compose.ui.graphics.Color(0xFF003544), secondary = androidx.compose.ui.graphics.Color(0xFFB7D7D6), error = androidx.compose.ui.graphics.Color(0xFFFFB4AB))

@Composable fun PakYarTheme(content: @Composable () -> Unit) {
    val typography = Typography(
        displaySmall = androidx.compose.ui.text.TextStyle(fontFamily=Vazirmatn, fontWeight=FontWeight.Bold, fontSize=30.sp, lineHeight = 40.sp),
        headlineSmall = androidx.compose.ui.text.TextStyle(fontFamily=Vazirmatn, fontWeight=FontWeight.Bold, fontSize=28.sp, lineHeight = 38.sp),
        titleLarge = androidx.compose.ui.text.TextStyle(fontFamily=Vazirmatn, fontWeight=FontWeight.SemiBold, fontSize=22.sp, lineHeight = 31.sp),
        titleMedium = androidx.compose.ui.text.TextStyle(fontFamily=Vazirmatn, fontWeight=FontWeight.SemiBold, fontSize=20.sp, lineHeight = 29.sp),
        bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily=Vazirmatn, fontWeight=FontWeight.Normal, fontSize=18.sp, lineHeight = 29.sp),
        bodyMedium = androidx.compose.ui.text.TextStyle(fontFamily=Vazirmatn, fontWeight=FontWeight.Normal, fontSize=16.sp, lineHeight = 25.sp),
        labelLarge = androidx.compose.ui.text.TextStyle(fontFamily=Vazirmatn, fontWeight=FontWeight.SemiBold, fontSize=18.sp, lineHeight = 26.sp)
    )
    val complete = typography.copy(
        displayLarge = typography.displaySmall, displayMedium = typography.displaySmall,
        headlineLarge = typography.headlineSmall, headlineMedium = typography.headlineSmall,
        titleSmall = typography.titleMedium, bodySmall = typography.bodyMedium,
        labelMedium = typography.labelLarge, labelSmall = typography.bodyMedium
    )
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) Dark else Light,
            typography = complete, content = content)
    }
}
