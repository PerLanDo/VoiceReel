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
 * Design goals, in order:
 *  1. **Accurate** — match on whole words only. Substring matching used to fire on innocent
 *     words like "background" (→ back), "download" (→ down), "display"/"playful" (→ play),
 *     "stopwatch" (→ stop) and "unlike" (→ like); those are now safe.
 *  2. **Robust to mis-hearings** — a curated homophone table plus a conservative fuzzy
 *     (edit-distance) fallback catch common recognizer slips ("necks" → next, "lik" → like).
 *  3. **Intent-aware** — negations ("don't skip", "not now") suppress a command, and a handful
 *     of multi-word phrases override single-word matches ("thumbs up" → like, not "up" →
 *     previous).
 */
object VoiceCommandParser {

    private data class Group(val command: VoiceCommand, val keywords: List<String>)

    // Order matters: the first group with a hit wins when a phrase contains several keywords.
    // PLAY is listed before PAUSE so "unpause" resolves to PLAY.
    private val groups = listOf(
        Group(VoiceCommand.NEXT, listOf("next", "down", "skip", "forward", "advance", "nether")),
        Group(
            VoiceCommand.PREVIOUS,
            listOf("previous", "prev", "back", "backward", "up", "last", "rewind", "before")
        ),
        Group(VoiceCommand.LIKE, listOf("like", "love", "heart", "favorite", "favourite", "fav")),
        Group(VoiceCommand.PLAY, listOf("play", "resume", "start", "continue", "unpause")),
        Group(VoiceCommand.PAUSE, listOf("pause", "stop", "wait", "hold", "freeze", "halt"))
    )

    // Multi-word phrases that must win over a single-word interpretation of one of their tokens.
    // Checked before per-word matching. Keyed on a normalized (space-joined) token sequence.
    private val phraseOverrides = linkedMapOf(
        "thumbs up" to VoiceCommand.LIKE,
        "thumb up" to VoiceCommand.LIKE,
        "thumbs down" to VoiceCommand.NONE,
        "thumb down" to VoiceCommand.NONE
    )

    private val homophones = mapOf(
        "necks" to VoiceCommand.NEXT,
        "nex" to VoiceCommand.NEXT,
        "nix" to VoiceCommand.NEXT,
        "text" to VoiceCommand.NEXT,
        "nest" to VoiceCommand.NEXT,
        "net" to VoiceCommand.NEXT,
        "nax" to VoiceCommand.NEXT,
        "knicks" to VoiceCommand.NEXT,
        "lake" to VoiceCommand.LIKE,
        "light" to VoiceCommand.LIKE,
        "bike" to VoiceCommand.LIKE,
        "mike" to VoiceCommand.LIKE,
        "liked" to VoiceCommand.LIKE,
        "likes" to VoiceCommand.LIKE,
        "black" to VoiceCommand.PREVIOUS,
        "pack" to VoiceCommand.PREVIOUS,
        "plate" to VoiceCommand.PLAY,
        "played" to VoiceCommand.PLAY,
        "paws" to VoiceCommand.PAUSE,
        "pose" to VoiceCommand.PAUSE,
        "pours" to VoiceCommand.PAUSE,
        "pods" to VoiceCommand.PAUSE
    )

    // Words that cancel a following command within [NEGATION_WINDOW] tokens. Note: "stop" is a
    // PAUSE command, so it is deliberately NOT treated as a negation.
    private val negations = setOf("dont", "not", "never", "no", "cannot", "cant", "wont", "stopped")

    // Only these keywords take part in the fuzzy (edit-distance) fallback. Short, high-traffic
    // command words are excluded because ordinary speech collides with them at distance 1
    // ("want"→wait, "live"→love, "held"→hold, "list"→last, "step"→stop, "bark"→back), which would
    // fire commands the user never gave. Distinctive words stay eligible so genuine mis-hearings
    // ("skipp"→skip, "lik"→like, "pawse"→pause) are still caught.
    private val fuzzyKeywords = setOf(
        "skip", "forward", "advance",
        "previous", "rewind", "backward", "before",
        "like", "favorite", "favourite",
        "resume", "continue", "unpause", "start",
        "pause", "freeze"
    )

    private const val NEGATION_WINDOW = 2
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
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
        if (texts.isEmpty()) return VoiceCommand.NONE

        // Exact / phrase / homophone matching across all candidates first, only then the
        // fuzzier fallback — so a clean match in a later hypothesis beats a fuzzy one earlier.
        for (text in texts) {
            // A non-null result — including an explicit NONE from a negative phrase such as
            // "thumbs down" — is authoritative for this utterance.
            exactMatch(tokenize(text))?.let { return it }
        }
        for (text in texts) {
            fuzzyMatch(tokenize(text))?.let { return it }
        }
        return VoiceCommand.NONE
    }

    private fun normalize(text: String): String =
        text.lowercase().replace("[’'`]".toRegex(), "").trim()

    private fun tokenize(text: String): List<String> =
        text.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }

    private fun isNegated(tokens: List<String>, index: Int): Boolean {
        val from = (index - NEGATION_WINDOW).coerceAtLeast(0)
        for (i in from until index) {
            if (tokens[i] in negations) return true
        }
        return false
    }

    /**
     * Returns the matched command, [VoiceCommand.NONE] when an explicit negative phrase
     * override matched, or null when nothing matched (so the caller can try the next candidate).
     */
    private fun exactMatch(tokens: List<String>): VoiceCommand? {
        if (tokens.isEmpty()) return null

        val joined = tokens.joinToString(" ")
        for ((phrase, command) in phraseOverrides) {
            if (containsPhrase(joined, phrase)) return command
        }

        for (group in groups) {
            for ((index, token) in tokens.withIndex()) {
                if (token in group.keywords && !isNegated(tokens, index)) return group.command
            }
        }

        for ((index, token) in tokens.withIndex()) {
            homophones[token]?.let { if (!isNegated(tokens, index)) return it }
        }
        return null
    }

    private fun containsPhrase(joined: String, phrase: String): Boolean {
        val padded = " $joined "
        return padded.contains(" $phrase ")
    }

    private fun fuzzyMatch(tokens: List<String>): VoiceCommand? {
        for ((index, word) in tokens.withIndex()) {
            if (word.length < FUZZY_MIN_WORD_LEN) continue
            if (isNegated(tokens, index)) continue
            for (group in groups) {
                for (keyword in group.keywords) {
                    if (keyword !in fuzzyKeywords) continue
                    if (keyword.length < FUZZY_MIN_KEYWORD_LEN) continue
                    if (word[0] != keyword[0]) continue // guard: only near-misses that start alike
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
