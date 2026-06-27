package com.echowalk.teamb.narration

/**
 * Pure-Kotlin text logic for Team B (NO Android dependencies on purpose).
 *
 * This is where the "brains" of the spoken description live, so it can be iterated with instant
 * JVM unit tests (see SceneNarrationTest) instead of build+deploy cycles. The Android side just
 * calls these functions and hands the result to TTS.
 */
object SceneNarration {

    /** Turn a list of detected tags into a natural spoken sentence. */
    fun fromTags(tags: List<String>): String {
        val unique = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return when (unique.size) {
            0 -> "I can't quite tell what's ahead."
            1 -> "I see ${unique[0]}."
            2 -> "I see ${unique[0]} and ${unique[1]}."
            else -> "I see ${unique.dropLast(1).joinToString(", ")}, and ${unique.last()}."
        }
    }

    /** Normalize raw model text into something clean to speak. */
    fun clean(raw: String): String =
        raw.trim()
            .replace(Regex("\\s+"), " ")
            .replaceFirstChar { it.uppercase() }
            .let { if (it.isNotEmpty() && it.last() !in ".!?") "$it." else it }
}
