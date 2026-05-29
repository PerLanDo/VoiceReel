package com.example

enum class ReelType {
    SWISS_ALPS,
    NEON_CYBER,
    WARM_BAKING,
    WAVE_SURFING,
    CODER_BEATS
}

data class ReelItem(
    val id: Int,
    val creator: String,
    val avatarColor: Long, // Hex color
    val caption: String,
    val hashtags: List<String>,
    val musicTrack: String,
    val likesCount: Int,
    val commentsCount: Int,
    val bookmarksCount: Int,
    val sharesCount: Int,
    val isLiked: Boolean = false,
    val isBookmarked: Boolean = false,
    val isFollowed: Boolean = false,
    val type: ReelType
)
