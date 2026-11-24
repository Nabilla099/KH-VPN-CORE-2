package com.khvpn.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.khvpn.app.data.ThemeMode

// Palet KH: hijau tinta (terowongan) + amber (paket data)
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B7A6B), onPrimary = Color.White,
    primaryContainer = Color(0xFFBFF1E6), onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E1), onSecondaryContainer = Color(0xFF05201C),
    tertiary = Color(0xFF8A5A00), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDAF), onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF5FAF8), onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF5FAF8), onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDBE5E1), onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976), outlineVariant = Color(0xFFBFC9C5),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEFF5F2),
    surfaceContainer = Color(0xFFE9F0ED), surfaceContainerHigh = Color(0xFFE3EAE7),
    surfaceContainerHighest = Color(0xFFDDE4E1),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5FD8C0), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046), onPrimaryContainer = Color(0xFFBFF1E6),
    secondary = Color(0xFFB1CCC5), onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF324B47), onSecondaryContainer = Color(0xFFCCE8E1),
    tertiary = Color(0xFFF2A93B), onTertiary = Color(0xFF462B00),
    tertiaryContainer = Color(0xFF663F00), onTertiaryContainer = Color(0xFFFFDDAF),
    background = Color(0xFF0A1514), onBackground = Color(0xFFDCE5E2),
    surface = Color(0xFF0A1514), onSurface = Color(0xFFDCE5E2),
    surfaceVariant = Color(0xFF3F4946), onSurfaceVariant = Color(0xFFBFC9C5),
    outline = Color(0xFF899390), outlineVariant = Color(0xFF3F4946),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Color(0xFF050E0D), surfaceContainerLow = Color(0xFF101C1B),
    surfaceContainer = Color(0xFF142120), surfaceContainerHigh = Color(0xFF1E2B2A),
    surfaceContainerHighest = Color(0xFF293635),
)

private val KhTypography = Typography().let {
    it.copy(
        headlineMedium = it.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = it.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

// Sudut lebih besar = tampilan M3 yang lebih modern; murni bentuk, tidak menambah beban render
private val KhShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun KhTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as? Activity)?.window ?: return@SideEffect
            val c = WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, typography = KhTypography, shapes = KhShapes, content = content)
}

/** Gaya monospace untuk domain, key, dan log. */
val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
