package com.example.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

enum class AppThemePreset(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val accentColor: Color,
    val pillColor: Color,
    val borderColor: Color
) {
    DEFAULT_GREEN(
        id = "DEFAULT_GREEN",
        displayName = "Default Green",
        subtitle = "Classic AADIFY Emerald Green",
        accentColor = Color(0xFF1DB954),
        pillColor = Color(0xFF0A3018),
        borderColor = Color(0xFF165B2E)
    ),
    CRIMSON_RED(
        id = "CRIMSON_RED",
        displayName = "Crimson Red",
        subtitle = "Bold Ruby Red Energy",
        accentColor = Color(0xFFFF2A4B),
        pillColor = Color(0xFF3B0A12),
        borderColor = Color(0xFF8A1527)
    ),
    CYBER_NEON(
        id = "CYBER_NEON",
        displayName = "Cyber Neon",
        subtitle = "Electric Neon Cyan Glow",
        accentColor = Color(0xFF00F5D4),
        pillColor = Color(0xFF05352E),
        borderColor = Color(0xFF008B78)
    ),
    ELECTRIC_PURPLE(
        id = "ELECTRIC_PURPLE",
        displayName = "Electric Purple",
        subtitle = "Vibrant Neon Violet",
        accentColor = Color(0xFFB388FF),
        pillColor = Color(0xFF261447),
        borderColor = Color(0xFF5E35B1)
    ),
    ROYAL_GOLD(
        id = "ROYAL_GOLD",
        displayName = "Royal Gold",
        subtitle = "Luxury Golden Edition",
        accentColor = Color(0xFFFFC107),
        pillColor = Color(0xFF382904),
        borderColor = Color(0xFF8C6806)
    ),
    OCEAN_BLUE(
        id = "OCEAN_BLUE",
        displayName = "Ocean Blue",
        subtitle = "Deep Electric Sky Blue",
        accentColor = Color(0xFF29B6F6),
        pillColor = Color(0xFF082B3D),
        borderColor = Color(0xFF0277BD)
    );

    companion object {
        fun fromId(id: String?): AppThemePreset {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT_GREEN
        }
    }
}

object AppThemeController {
    private const val PREFS_NAME = "ak_theme_prefs"
    private const val KEY_THEME_ID = "selected_theme_id"

    var currentPreset by mutableStateOf(AppThemePreset.DEFAULT_GREEN)
        private set

    fun initialize(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedId = prefs.getString(KEY_THEME_ID, AppThemePreset.DEFAULT_GREEN.id)
            currentPreset = AppThemePreset.fromId(savedId)
        } catch (_: Exception) {
        }
    }

    fun selectTheme(context: Context, preset: AppThemePreset) {
        currentPreset = preset
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME_ID, preset.id)
                .apply()
        } catch (_: Exception) {
        }
    }
}

// Base Dark Aesthetic Hex Codes
val PureBlack = Color(0xFF121212)
val DeepCharcoal = Color(0xFF181818)
val SurfaceDarkCard = Color(0xFF242424)

// Dynamic reactive theme accent tokens (automatically updates all screens in real-time)
val SpotifyVibrantGreen: Color
    get() = AppThemeController.currentPreset.accentColor

val SpotifyDarkGreenPill: Color
    get() = AppThemeController.currentPreset.pillColor

val SpotifyGreenBorder: Color
    get() = AppThemeController.currentPreset.borderColor

val PrimaryWhite = Color(0xFFFFFFFF)
val MutedGray = Color(0xFFB3B3B3)
val SubtleGray = Color(0xFF727272)
val DividerDark = Color(0xFF2A2A2A)
val ErrorRed = Color(0xFFE91429)
