package com.github.musicyou.ui.styling

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.github.musicyou.ui.apple.MusicRed

private val musicScheme = lightColorScheme(
    primary = MusicRed, onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3E8), onPrimaryContainer = MusicRed,
    secondary = MusicRed, secondaryContainer = Color(0xFFF0F0F2),
    background = Color(0xFFFDFBFC), onBackground = Color(0xFF17171B),
    surface = Color(0xFFFDFBFC), onSurface = Color(0xFF17171B),
    surfaceVariant = Color(0xFFF1F0F2), onSurfaceVariant = Color(0xFF77777D),
    surfaceContainer = Color(0xFFF3F2F4), outline = Color(0xFFD8D7DA), outlineVariant = Color(0xFFE8E7EA),
)

@Composable
fun AppTheme(darkTheme: Boolean = false, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = musicScheme, typography = Typography, content = content)
}
