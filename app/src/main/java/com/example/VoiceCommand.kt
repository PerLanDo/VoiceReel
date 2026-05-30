package com.example

/**
 * The set of actions Voice Reels can perform on a foreground short-video app.
 *
 * The mapping intentionally favours short, natural words that work across TikTok,
 * Instagram Reels, Facebook Reels and YouTube Shorts.
 */
enum class VoiceCommand(val displayName: String) {
    NEXT("Next"),
    PREVIOUS("Previous"),
    PLAY_PAUSE("Play / Pause"),
    LIKE("Like"),
    NONE("");
}

/**
 * Pure, side-effect-free parser that turns a recognized speech string into a [VoiceCommand].
 *
 * Keeping this separate from the Android components makes the command vocabulary trivially
 * unit-testable on the JVM.
 */
object VoiceCommandParser {

    // Order matters: the first group whose keyword is found wins.
    private val NEXT_WORDS = listOf("next", "down", "skip", "forward")
    private val PREVIOUS_WORDS = listOf("previous", "prev", "back", "up", "last")
    private val LIKE_WORDS = listOf("like", "love", "heart", "favorite", "favourite")
    private val PLAY_PAUSE_WORDS = listOf("pause", "play", "stop", "resume", "wait", "hold")

    fun parse(rawText: String?): VoiceCommand {
        if (rawText.isNullOrBlank()) return VoiceCommand.NONE
        val text = rawText.lowercase().trim()
        val words = text.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.toSet()

        fun matches(keywords: List<String>) = keywords.any { it in words || text.contains(it) }

        return when {
            matches(NEXT_WORDS) -> VoiceCommand.NEXT
            matches(PREVIOUS_WORDS) -> VoiceCommand.PREVIOUS
            matches(LIKE_WORDS) -> VoiceCommand.LIKE
            matches(PLAY_PAUSE_WORDS) -> VoiceCommand.PLAY_PAUSE
            else -> VoiceCommand.NONE
        }
    }
}
