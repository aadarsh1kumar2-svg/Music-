package com.example.models

data class Song(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val videoId: String = "",
    val bannerUrl: String = "",
    val rank: Int = Int.MAX_VALUE,
    val isTopChart: Boolean = false,
    val timestamp: Long = 0L
) {
    val resolvedBannerUrl: String
        get() = if (bannerUrl.isNotBlank()) {
            bannerUrl
        } else if (cleanVideoId.isNotBlank()) {
            "https://img.youtube.com/vi/$cleanVideoId/hqdefault.jpg"
        } else {
            ""
        }

    val highResBannerUrl: String
        get() = if (bannerUrl.isNotBlank()) {
            bannerUrl
        } else if (cleanVideoId.isNotBlank()) {
            "https://img.youtube.com/vi/$cleanVideoId/maxresdefault.jpg"
        } else {
            ""
        }

    val cleanVideoId: String
        get() = extractYoutubeVideoId(videoId)

    companion object {
        fun extractYoutubeVideoId(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return ""
            if (trimmed.length == 11 && !trimmed.contains("/") && !trimmed.contains("?")) {
                return trimmed
            }
            val patterns = listOf(
                Regex("(?:v=|/v/|youtu\\.be/|/embed/|/shorts/|/live/)([a-zA-Z0-9_-]{11})"),
                Regex("^([a-zA-Z0-9_-]{11})$")
            )
            for (pattern in patterns) {
                val match = pattern.find(trimmed)
                if (match != null && match.groupValues.size > 1) {
                    return match.groupValues[1]
                }
            }
            return trimmed
        }
    }
}
