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
    val flagEmoji: String,
    val trendingQuery: String
)

val SUPPORTED_COUNTRIES = listOf(
    CountryOption("IN", "India", "🇮🇳", "Top Hindi Bollywood Songs India Hits"),
    CountryOption("GLOBAL", "Global", "🌍", "Global Top 50 Music Hits Songs"),
    CountryOption("US", "United States", "🇺🇸", "Billboard Hot 100 Top Songs USA"),
    CountryOption("GB", "United Kingdom", "🇬🇧", "UK Top 40 Singles Official Charts Songs"),
    CountryOption("CA", "Canada", "🇨🇦", "Canada Top Hits Music Songs"),
    CountryOption("AU", "Australia", "🇦🇺", "Australia ARIA Top Hits Songs"),
    CountryOption("PK", "Pakistan", "🇵🇰", "Top Pakistani Coke Studio Urdu Hits Songs"),
    CountryOption("NP", "Nepal", "🇳🇵", "Top Nepali Trending Songs Hits"),
    CountryOption("BD", "Bangladesh", "🇧🇩", "Top Bangla Trending Songs Hits"),
    CountryOption("KR", "South Korea", "🇰🇷", "Top K-Pop Korean Hits Songs"),
    CountryOption("JP", "Japan", "🇯🇵", "Top J-Pop Japanese Anime Hits Songs"),
    CountryOption("BR", "Brazil", "🇧🇷", "Top Brazil Pop Sertanejo Hits Songs"),
    CountryOption("AE", "UAE", "🇦🇪", "Top Arabic & International Trending Songs UAE")
)
