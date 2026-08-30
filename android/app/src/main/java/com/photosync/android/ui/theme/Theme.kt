package com.photosync.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF0057D8),
    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFD8E2FF),
    secondary = androidx.compose.ui.graphics.Color(0xFF4B5D92),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE0E7FF),
    tertiary = androidx.compose.ui.graphics.Color(0xFF146C2E),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFC7F0CB),
    background = androidx.compose.ui.graphics.Color(0xFFF7F8FC),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFEFF2FA),
    error = androidx.compose.ui.graphics.Color(0xFFBA1A1A),
    errorContainer = androidx.compose.ui.graphics.Color(0xFFFFDAD6),
)

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFAFC6FF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF002D6B),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF204A9F),
    secondary = androidx.compose.ui.graphics.Color(0xFFBCC7F6),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF344776),
    tertiary = androidx.compose.ui.graphics.Color(0xFFACE0B1),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF00531D),
    background = androidx.compose.ui.graphics.Color(0xFF10131A),
    surface = androidx.compose.ui.graphics.Color(0xFF171B23),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF232833),
    error = androidx.compose.ui.graphics.Color(0xFFFFB4AB),
    errorContainer = androidx.compose.ui.graphics.Color(0xFF93000A),
)

@Composable
fun PhotoSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
