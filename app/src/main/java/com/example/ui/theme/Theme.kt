package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val AKMusicDarkColorScheme = darkColorScheme(
    primary = SpotifyVibrantGreen,
    onPrimary = PureBlack,
    primaryContainer = SpotifyDarkGreenPill,
    onPrimaryContainer = SpotifyVibrantGreen,
    secondary = SpotifyVibrantGreen,
    onSecondary = PureBlack,
    secondaryContainer = SurfaceDarkCard,
    onSecondaryContainer = PrimaryWhite,
    tertiary = SpotifyVibrantGreen,
    background = PureBlack,
    onBackground = PrimaryWhite,
    surface = DeepCharcoal,
    onSurface = PrimaryWhite,
    surfaceVariant = SurfaceDarkCard,
    onSurfaceVariant = MutedGray,
    outline = DividerDark,
    error = ErrorRed,
    onError = PrimaryWhite
)

val AKMusicShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = AKMusicDarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = PureBlack.toArgb()
                window.navigationBarColor = PureBlack.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AKMusicShapes,
        content = content
    )
}
