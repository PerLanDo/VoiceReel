package com.example

import kotlin.math.abs

/**
 * The set of actions Voice Reels can perform on a foreground short-video app.
 *
 * PLAY and PAUSE are kept separate (rather than a single toggle) so the service can be
 * state-aware: only pause when something is playing, only resume when it is paused.
 */
enum class VoiceCommand(val displayName: String) {
    NEXT("Next"),
    PREVIOUS("Previous"),
    PLAY("Play"),
    PAUSE("Pause"),
    LIKE("Like"),
    NONE("");
}

/**
 * Pure, side-effect-free parser that turns recognized speech into a [VoiceCommand].
 *
 * It is deliberately *sensitive*: in addition to exact keywords it understands common
 * mis-recognitions (homophones such as "necks" → next, "lake" → like) and applies a fuzzy
 * edit-distance fallback so near-misses still trigger.
 */
object VoiceCommandParser {

    private data class Group(val command: VoiceCommand, val keywords: List<String>)

    // Order matters: the first group with a hit wins. PLAY is listed before PAUSE so that
    // "unpause" resolves to PLAY rather than substring-matching "pause".
    private val groups = listOf(
        Group(VoiceCommand.NEXT, listOf("next", "down", "skip", "forward")),
        Group(VoiceCommand.PREVIOUS, listOf("previous", "prev", "back", "up", "last", "rewind")),
        Group(VoiceCommand.LIKE, listOf("like", "love", "heart", "favorite", "favourite")),
        Group(VoiceCommand.PLAY, listOf("play", "resume", "start", "continue", "unpause")),
        Group(VoiceCommand.PAUSE, listOf("pause", "stop", "wait", "hold", "freeze"))
    )

    private val homophones = mapOf(
        "necks" to VoiceCommand.NEXT,
        "text" to VoiceCommand.NEXT,
        "nest" to VoiceCommand.NEXT,
        "net" to VoiceCommand.NEXT,
        "nax" to VoiceCommand.NEXT,
        "lake" to VoiceCommand.LIKE,
        "light" to VoiceCommand.LIKE,
        "bike" to VoiceCommand.LIKE,
        "mike" to VoiceCommand.LIKE,
        "liked" to VoiceCommand.LIKE,
        "likes" to VoiceCommand.LIKE,
        "black" to VoiceCommand.PREVIOUS,
        "pack" to VoiceCommand.PREVIOUS,
        "plate" to VoiceCommand.PLAY,
        "paws" to VoiceCommand.PAUSE,
        "pose" to VoiceCommand.PAUSE,
        "pours" to VoiceCommand.PAUSE
    )

    private const val FUZZY_MIN_KEYWORD_LEN = 4
    private const val FUZZY_MIN_WORD_LEN = 3
    private const val FUZZY_MAX_DISTANCE = 1

    fun parse(rawText: String?): VoiceCommand = parse(listOf(rawText))

    /**
     * Parse against several recognition candidates (e.g. the top-N hypotheses from the
     * speech engine). The first candidate that yields a command wins.
     */
    fun parse(candidates: List<String?>): VoiceCommand {
        val texts = candidates
            .filterNotNull()
            .map { it.lowercase().trim() }
            .filter { it.isNotEmpty() }
        if (texts.isEmpty()) return VoiceCommand.NONE

        for (text in texts) {
            exactMatch(text)?.let { return it }
        }
        for (text in texts) {
            fuzzyMatch(text)?.let { return it }
        }
        return VoiceCommand.NONE
    }

    private fun tokens(text: String): Set<String> =
        text.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.toSet()

    private fun exactMatch(text: String): VoiceCommand? {
        val words = tokens(text)
        for (group in groups) {
            if (group.keywords.any { it in words || text.contains(it) }) return group.command
        }
        for (word in words) {
            homophones[word]?.let { return it }
        }
        return null
    }

    private fun fuzzyMatch(text: String): VoiceCommand? {
        val words = tokens(text)
        for (word in words) {
            if (word.length < FUZZY_MIN_WORD_LEN) continue
            for (group in groups) {
                for (keyword in group.keywords) {
                    if (keyword.length < FUZZY_MIN_KEYWORD_LEN) continue
                    if (abs(keyword.length - word.length) > FUZZY_MAX_DISTANCE) continue
                    if (levenshtein(word, keyword) <= FUZZY_MAX_DISTANCE) return group.command
                }
            }
        }
        return null
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[b.length]
    }
}
