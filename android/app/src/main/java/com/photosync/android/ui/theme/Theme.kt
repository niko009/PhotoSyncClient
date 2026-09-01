package com.photosync.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFFA84832), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD0), onPrimaryContainer = Color(0xFF622515),
    secondary = Color(0xFF49634D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7EDDF), onSecondaryContainer = Color(0xFF304934),
    tertiary = Color(0xFF795B38), tertiaryContainer = Color(0xFFF4E4CD),
    background = Color(0xFFFAF6EF), onBackground = Color(0xFF302A25),
    surface = Color(0xFFFFFCF7), onSurface = Color(0xFF302A25),
    surfaceVariant = Color(0xFFF0E8DD), onSurfaceVariant = Color(0xFF6C6056),
    outline = Color(0xFF86776A), outlineVariant = Color(0xFFE7DDD1),
    error = Color(0xFFAD302A), errorContainer = Color(0xFFFFDAD5),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2AE98), onPrimary = Color(0xFF552011),
    primaryContainer = Color(0xFF743723), onPrimaryContainer = Color(0xFFFFDAD0),
    secondary = Color(0xFFB1CBAA), onSecondary = Color(0xFF203822),
    secondaryContainer = Color(0xFF334735), onSecondaryContainer = Color(0xFFD0E6C9),
    background = Color(0xFF211D19), onBackground = Color(0xFFF0E6DA),
    surface = Color(0xFF2B2520), onSurface = Color(0xFFF0E6DA),
    surfaceVariant = Color(0xFF3B332B), onSurfaceVariant = Color(0xFFD1C3B4),
    outline = Color(0xFFA59788), outlineVariant = Color(0xFF50463B),
    error = Color(0xFFFFB4A8), errorContainer = Color(0xFF6A241E),
)
private val AlbumTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
)

@Composable
fun PhotoSyncTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AlbumTypography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
}
