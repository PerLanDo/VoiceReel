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
 * It is deliberately *sensitive* — in addition to exact keywords it understands common
 * mis-recognitions (homophones such as "necks" → next, "lake" → like), multi-word phrases
 * ("go to the next one", "thumbs up"), and applies a guarded fuzzy edit-distance fallback so
 * near-misses still trigger.
 *
 * It is also careful about a few false-positive traps:
 *  - Matching is done on whole *words*, never substrings, so "background" no longer looks like
 *    "back", "playful" like "play", or "stopwatch" like "stop".
 *  - Negations are respected: "don't skip", "not next" and "never like this" resolve to
 *    [VoiceCommand.NONE] instead of firing the negated command.
 */
object VoiceCommandParser {

    private data class Group(val command: VoiceCommand, val keywords: List<String>)

    // Order matters: the first group with a hit wins. PLAY is listed before PAUSE so that
    // "unpause" resolves to PLAY rather than looking like "pause".
    private val groups = listOf(
        Group(
            VoiceCommand.NEXT,
            listOf("next", "down", "skip", "forward", "advance")
        ),
        Group(
            VoiceCommand.PREVIOUS,
            listOf("previous", "prev", "back", "up", "last", "rewind", "backward", "backwards")
        ),
        Group(
            VoiceCommand.LIKE,
            listOf("like", "love", "heart", "favorite", "favourite", "thumbs")
        ),
        Group(
            VoiceCommand.PLAY,
            listOf("play", "resume", "start", "continue", "unpause")
        ),
        Group(
            VoiceCommand.PAUSE,
            listOf("pause", "stop", "wait", "hold", "freeze", "halt")
        )
    )

    private val homophones = mapOf(
        // NEXT
        "necks" to VoiceCommand.NEXT,
        "text" to VoiceCommand.NEXT,
        "nest" to VoiceCommand.NEXT,
        "net" to VoiceCommand.NEXT,
        "nax" to VoiceCommand.NEXT,
        "nexus" to VoiceCommand.NEXT,
        "skiff" to VoiceCommand.NEXT,
        // LIKE
        "lake" to VoiceCommand.LIKE,
        "light" to VoiceCommand.LIKE,
        "bike" to VoiceCommand.LIKE,
        "mike" to VoiceCommand.LIKE,
        "liked" to VoiceCommand.LIKE,
        "likes" to VoiceCommand.LIKE,
        "hearted" to VoiceCommand.LIKE,
        // PREVIOUS
        "black" to VoiceCommand.PREVIOUS,
        "pack" to VoiceCommand.PREVIOUS,
        "bak" to VoiceCommand.PREVIOUS,
        // PLAY
        "plate" to VoiceCommand.PLAY,
        "played" to VoiceCommand.PLAY,
        "plays" to VoiceCommand.PLAY,
        // PAUSE
        "paws" to VoiceCommand.PAUSE,
        "pose" to VoiceCommand.PAUSE,
        "pours" to VoiceCommand.PAUSE,
        "pods" to VoiceCommand.PAUSE
    )

    /**
     * Multi-word phrases checked before single-word keywords, so their intent overrides a
     * conflicting single token (e.g. "thumbs up" is a *like*, not "up" → previous).
     */
    private val phrases = listOf(
        "thumbs up" to VoiceCommand.LIKE,
        "double tap" to VoiceCommand.LIKE,
        "next one" to VoiceCommand.NEXT,
        "move on" to VoiceCommand.NEXT,
        "keep going" to VoiceCommand.NEXT,
        "go back" to VoiceCommand.PREVIOUS,
        "last one" to VoiceCommand.PREVIOUS
    )

    // Words that, when they immediately precede a command word, cancel it.
    private val negations = setOf(
        "dont", "not", "never", "cannot", "cant", "wont", "no"
    )

    // How many preceding tokens to inspect for a negation.
    private const val NEGATION_LOOKBACK = 2

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

        for (text in texts) {
            exactMatch(text)?.let { return it }
        }
        for (text in texts) {
            fuzzyMatch(text)?.let { return it }
        }
        return VoiceCommand.NONE
    }

    /** Lowercase, drop apostrophes ("don't" → "dont") and collapse to spaced words. */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("['’`]"), "")
            .replace(Regex("[^a-z]+"), " ")
            .trim()

    private fun tokenList(text: String): List<String> =
        text.split(' ').filter { it.isNotEmpty() }

    private fun exactMatch(text: String): VoiceCommand? {
        // Phrase overrides win first (their multi-word intent beats a single conflicting token).
        for ((phrase, command) in phrases) {
            if (containsPhrase(text, phrase)) return command
        }

        val words = tokenList(text)

        for (group in groups) {
            for ((index, word) in words.withIndex()) {
                if (word in group.keywords && !isNegated(words, index)) return group.command
            }
        }
        for ((index, word) in words.withIndex()) {
            homophones[word]?.let { if (!isNegated(words, index)) return it }
        }
        return null
    }

    private fun fuzzyMatch(text: String): VoiceCommand? {
        val words = tokenList(text)
        for ((index, word) in words.withIndex()) {
            if (word.length < FUZZY_MIN_WORD_LEN) continue
            if (isNegated(words, index)) continue
            for (group in groups) {
                for (keyword in group.keywords) {
                    if (keyword.length < FUZZY_MIN_KEYWORD_LEN) continue
                    // Same first letter guard keeps unrelated words (e.g. "cause" vs "pause")
                    // from fuzzily matching while still catching genuine mis-hearings.
                    if (word[0] != keyword[0]) continue
                    if (abs(keyword.length - word.length) > FUZZY_MAX_DISTANCE) continue
                    if (levenshtein(word, keyword) <= FUZZY_MAX_DISTANCE) return group.command
                }
            }
        }
        return null
    }

    /** True if the space-separated [phrase] appears as consecutive whole words in [text]. */
    private fun containsPhrase(text: String, phrase: String): Boolean {
        val words = tokenList(text)
        val target = phrase.split(' ')
        if (target.isEmpty() || target.size > words.size) return false
        for (start in 0..words.size - target.size) {
            if ((target.indices).all { words[start + it] == target[it] }) return true
        }
        return false
    }

    private fun isNegated(words: List<String>, keywordIndex: Int): Boolean {
        val from = (keywordIndex - NEGATION_LOOKBACK).coerceAtLeast(0)
        for (i in from until keywordIndex) {
            if (words[i] in negations) return true
        }
        return false
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
