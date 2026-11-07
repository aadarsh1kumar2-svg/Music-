package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

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
    val context = LocalContext.current
    LaunchedEffect(context) {
        AppThemeController.initialize(context)
    }

    val preset = AppThemeController.currentPreset
    val colorScheme = darkColorScheme(
        primary = preset.accentColor,
        onPrimary = PureBlack,
        primaryContainer = preset.pillColor,
        onPrimaryContainer = preset.accentColor,
        secondary = preset.accentColor,
        onSecondary = PureBlack,
        secondaryContainer = SurfaceDarkCard,
        onSecondaryContainer = PrimaryWhite,
        tertiary = preset.accentColor,
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
