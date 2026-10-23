package com.example.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object TimeFormatUtils {

    fun formatDurationMs(durationMs: Long): String {
        val safeMs = durationMs.coerceAtLeast(0L)
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(safeMs)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    fun formatTimestampRelative(timestampMs: Long): String {
        if (timestampMs <= 0L) return "Recent"
        val now = System.currentTimeMillis()
        val diff = (now - timestampMs).coerceAtLeast(0L)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        val days = TimeUnit.MILLISECONDS.toDays(diff)

        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(timestampMs))
        }
    }
}

data class CountryOption(
    val code: String,
    val name: String,
    val flagEmoji: String
)

val SUPPORTED_COUNTRIES = listOf(
    CountryOption("IN", "India", "🇮🇳"),
    CountryOption("GLOBAL", "Global", "🌍"),
    CountryOption("US", "United States", "🇺🇸"),
    CountryOption("GB", "United Kingdom", "🇬🇧"),
    CountryOption("CA", "Canada", "🇨🇦"),
    CountryOption("AU", "Australia", "🇦🇺")
)
