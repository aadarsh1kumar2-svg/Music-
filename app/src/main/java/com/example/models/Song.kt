package com.example.models

import org.json.JSONObject

data class Song(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val videoId: String = "",
    val bannerUrl: String = "",
    val rank: Int = Int.MAX_VALUE,
    val isTopChart: Boolean = false,
    val timestamp: Long = 0L,
    val allowDownload: Boolean = false,
    val localContentUri: String = ""
) {
    val resolvedLocalUri: String
        get() {
            if (localContentUri.isNotBlank()) return localContentUri
            if (id.startsWith("local_media_")) {
                val mediaId = id.removePrefix("local_media_").trim()
                if (mediaId.isNotEmpty() && mediaId.all { it.isDigit() }) {
                    return "content://media/external/audio/media/$mediaId"
                }
            }
            return ""
        }

    val isLocalDeviceSong: Boolean
        get() = resolvedLocalUri.isNotBlank()

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
            "https://img.youtube.com/vi/$cleanVideoId/hqdefault.jpg"
        } else {
            ""
        }

    val cleanVideoId: String
        get() = extractYoutubeVideoId(videoId)

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("artist", artist)
            put("videoId", videoId)
            put("bannerUrl", bannerUrl)
            put("rank", rank)
            put("isTopChart", isTopChart)
            put("timestamp", timestamp)
            put("allowDownload", allowDownload)
            put("localContentUri", resolvedLocalUri)
        }
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): Song {
            return Song(
                id = obj.optString("id", ""),
                title = obj.optString("title", ""),
                artist = obj.optString("artist", ""),
                videoId = obj.optString("videoId", ""),
                bannerUrl = obj.optString("bannerUrl", ""),
                rank = obj.optInt("rank", Int.MAX_VALUE),
                isTopChart = obj.optBoolean("isTopChart", false),
                timestamp = obj.optLong("timestamp", 0L),
                allowDownload = obj.optBoolean("allowDownload", false),
                localContentUri = obj.optString("localContentUri", "")
            )
        }

        fun extractYoutubeVideoId(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return ""
            if (trimmed.startsWith("content://", ignoreCase = true) ||
                trimmed.startsWith("file://", ignoreCase = true)
            ) {
                return ""
            }
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
