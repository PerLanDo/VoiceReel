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
 *
 * Matching is **word-based and position-aware**: the utterance is tokenized and scanned
 * left-to-right, so the command that was actually spoken *first* wins and accidental
 * substrings (e.g. "up" inside "stupid", "back" inside "background") can never fire. A small
 * set of negation words ("not", "don't", …) directly preceding a keyword suppresses that match,
 * so "I don't like this" does nothing instead of liking the video.
 */
object VoiceCommandParser {

    private data class Group(val command: VoiceCommand, val keywords: List<String>)

    // Note: with word-based scanning there is no risk of "unpause" substring-matching "pause",
    // so group order no longer affects correctness; it only breaks ties when two different
    // command keywords appear in the *same* token position (which cannot happen).
    private val groups = listOf(
        Group(VoiceCommand.NEXT, listOf("next", "down", "skip", "forward", "downward", "advance")),
        Group(
            VoiceCommand.PREVIOUS,
            listOf("previous", "prev", "back", "up", "last", "rewind", "upward", "before")
        ),
        Group(VoiceCommand.LIKE, listOf("like", "love", "heart", "favorite", "favourite", "fav")),
        Group(VoiceCommand.PLAY, listOf("play", "resume", "start", "continue", "unpause")),
        Group(VoiceCommand.PAUSE, listOf("pause", "stop", "wait", "hold", "freeze", "halt"))
    )

    /** Flattened keyword → command lookup for O(1) exact token matches. */
    private val keywordToCommand: Map<String, VoiceCommand> = buildMap {
        for (group in groups) {
            for (keyword in group.keywords) put(keyword, group.command)
        }
    }

    private val homophones = mapOf(
        "necks" to VoiceCommand.NEXT,
        "neck" to VoiceCommand.NEXT,
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

    /**
     * Words that, when directly preceding a matched keyword, cancel the match — e.g.
     * "don't like", "no next". Apostrophes are stripped before tokenizing, so "don't"
     * arrives here as "dont".
     */
    private val negations = setOf(
        "no", "not", "dont", "doesnt", "didnt", "wont", "cant", "cannot", "never"
    )

    private const val FUZZY_MIN_KEYWORD_LEN = 4
    private const val FUZZY_MIN_WORD_LEN = 3
    private const val FUZZY_MAX_DISTANCE = 1

    fun parse(rawText: String?): VoiceCommand = parse(listOf(rawText))

    /**
     * Parse against several recognition candidates (e.g. the top-N hypotheses from the
     * speech engine). The first candidate that yields a command wins. Exact/homophone
     * matches across all candidates are preferred over fuzzy matches.
     */
    fun parse(candidates: List<String?>): VoiceCommand {
        val tokenized = candidates
            .filterNotNull()
            .map { tokens(it) }
            .filter { it.isNotEmpty() }
        if (tokenized.isEmpty()) return VoiceCommand.NONE

        for (words in tokenized) {
            exactMatch(words)?.let { return it }
        }
        for (words in tokenized) {
            fuzzyMatch(words)?.let { return it }
        }
        return VoiceCommand.NONE
    }

    /**
     * Tokenize into an *ordered* list of lowercase words. Apostrophes are removed first so
     * contractions stay intact ("don't" → "dont") for negation detection.
     */
    private fun tokens(text: String): List<String> =
        text.lowercase()
            .replace("'", "")
            .replace("\u2019", "") // typographic apostrophe
            .split(Regex("[^a-z]+"))
            .filter { it.isNotEmpty() }

    private fun isNegated(words: List<String>, index: Int): Boolean =
        index > 0 && words[index - 1] in negations

    private fun exactMatch(words: List<String>): VoiceCommand? {
        for (i in words.indices) {
            val command = keywordToCommand[words[i]] ?: homophones[words[i]] ?: continue
            if (isNegated(words, i)) continue
            return command
        }
        return null
    }

    private fun fuzzyMatch(words: List<String>): VoiceCommand? {
        for (i in words.indices) {
            val word = words[i]
            if (word.length < FUZZY_MIN_WORD_LEN) continue
            if (isNegated(words, i)) continue
            for (group in groups) {
                for (keyword in group.keywords) {
                    if (keyword.length < FUZZY_MIN_KEYWORD_LEN) continue
                    if (abs(keyword.length - word.length) > FUZZY_MAX_DISTANCE) continue
                    // Require the same first letter: dramatically cuts false positives such as
                    // "smart" → start, "atop" → stop, while still catching real mishears.
                    if (word[0] != keyword[0]) continue
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
