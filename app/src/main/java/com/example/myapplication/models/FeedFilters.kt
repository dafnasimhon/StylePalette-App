package com.example.myapplication.models

/**
 * Persisted under [AppConfig.FIELD_FEED_FILTERS] on the user document.
 */
data class FeedFilters(
    val selectedVibe: String = AppConfig.FILTER_ALL,
    val matchMyPalette: Boolean = false
) {
    fun toFirestoreMap(): HashMap<String, Any> {
        return hashMapOf(
            AppConfig.FIELD_FEED_FILTER_SELECTED_VIBE to selectedVibe,
            AppConfig.FIELD_FEED_FILTER_MATCH_PALETTE to matchMyPalette
        )
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromFirestore(map: Map<String, Any>?): FeedFilters {
            if (map == null) return FeedFilters()
            val rawVibe = map[AppConfig.FIELD_FEED_FILTER_SELECTED_VIBE]
            val vibeStr = when (rawVibe) {
                is String -> rawVibe.trim()
                null -> ""
                else -> rawVibe.toString().trim()
            }
            val vibe = if (vibeStr.isEmpty()) AppConfig.FILTER_ALL else vibeStr
            val rawMatch = map[AppConfig.FIELD_FEED_FILTER_MATCH_PALETTE]
            val match = when (rawMatch) {
                is Boolean -> rawMatch
                is Number -> rawMatch.toInt() != 0
                else -> false
            }
            return FeedFilters(selectedVibe = vibe, matchMyPalette = match)
        }
    }
}
