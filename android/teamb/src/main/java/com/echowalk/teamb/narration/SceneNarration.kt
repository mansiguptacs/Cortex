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

    /**
     * Turn top scene-classifier guesses (e.g. ["office", "corridor"]) into a spoken sentence
     * about the *kind of space* the user is in. Expects bare scene names (no article).
     */
    fun fromScene(scenes: List<String>): String {
        val unique = scenes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return when (unique.size) {
            0 -> "I can't quite tell what kind of space this is."
            1 -> "You appear to be in ${withArticle(unique[0])}."
            else -> "This looks like ${withArticle(unique[0])}, possibly ${withArticle(unique[1])}."
        }
    }

    /** A scene guess: a friendly term ("office") + its [0,1] probability. */
    data class ScenePrediction(val term: String, val prob: Float)

    /**
     * Confidence-aware scene sentence. Phrasing softens as the model gets less sure, which is both
     * more honest and calmer for a user relying on it: certain -> "You're in a kitchen.";
     * unsure -> "I'm not sure, but it might be a corridor." A confident runner-up adds a "maybe".
     */
    fun fromSceneRanked(
        preds: List<ScenePrediction>,
        highConf: Float = 0.50f,
        midConf: Float = 0.25f,
        lowConf: Float = 0.12f,
        hedgeConf: Float = 0.15f,
    ): String {
        val top = preds.firstOrNull()?.takeIf { it.term.isNotBlank() }
            ?: return "I can't quite tell what kind of space this is."
        val a = withArticle(top.term)
        val second = preds.getOrNull(1)?.takeIf { it.term.isNotBlank() && it.term != top.term }
        return when {
            top.prob >= highConf -> "You're in $a."
            top.prob >= midConf ->
                if (second != null && second.prob >= hedgeConf)
                    "This looks like $a, maybe ${withArticle(second.term)}."
                else "This looks like $a."
            top.prob >= lowConf -> "I'm not sure, but it might be $a."
            else -> "I can't quite tell what kind of space this is."
        }
    }

    private fun withArticle(label: String): String {
        if (label.isEmpty()) return label
        val article = if (label.first().lowercaseChar() in "aeiou") "an" else "a"
        return "$article $label"
    }

    /**
     * Ultra-brief phrasing for ambient mode: just the place, e.g. "Kitchen." We deliberately keep
     * auto-announcements to a single word/phrase so we inform without overwhelming; the full
     * confidence-aware sentence is reserved for a deliberate tap.
     */
    fun brief(term: String): String {
        val t = term.trim()
        if (t.isEmpty()) return ""
        val capped = t.replaceFirstChar { it.uppercase() }
        return if (capped.last() in ".!?") capped else "$capped."
    }

    /** Normalize raw model text into something clean to speak. */
    fun clean(raw: String): String =
        raw.trim()
            .replace(Regex("\\s+"), " ")
            .replaceFirstChar { it.uppercase() }
            .let { if (it.isNotEmpty() && it.last() !in ".!?") "$it." else it }
}
