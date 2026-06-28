package com.echowalk.teamb.narration

/**
 * Collapses Places365's 365 fine-grained categories into a small, navigation-relevant vocabulary
 * with natural spoken names. Two jobs:
 *   1. Merge synonyms ("office cubicles", "office building" -> "office") so confidence isn't split.
 *   2. Speak like a person ("a corridor", "a stairway") rather than dataset slugs ("elevator/door").
 *
 * Pure Kotlin (no Android) so it's covered by fast JVM unit tests. Unknown labels fall back to a
 * light cleanup of the raw label, so we degrade gracefully instead of dropping anything.
 */
object SceneVocabulary {

    /**
     * Map a cleaned Places365 label (lowercase, spaces, e.g. "office cubicles") to a friendly
     * navigation term WITHOUT an article (e.g. "office"). Falls back to [tidy] for anything unmapped.
     */
    fun friendly(rawLabel: String): String {
        val key = rawLabel.trim().lowercase()
        DIRECT[key]?.let { return it }
        // Substring rules catch the long tail (e.g. "*_store"/"*_shop" -> "shop").
        for ((needle, term) in CONTAINS) if (key.contains(needle)) return term
        return tidy(key)
    }

    /** Last-resort cleanup: drop indoor/outdoor qualifiers and collapse whitespace. */
    private fun tidy(label: String): String =
        label.replace(Regex("\\b(indoor|outdoor)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { label }

    /** Exact-match overrides for the most common indoor spaces a user will navigate. */
    private val DIRECT: Map<String, String> = mapOf(
        "corridor" to "corridor",
        "hallway" to "hallway",
        "elevator lobby" to "lift lobby",
        "lobby" to "lobby",
        "reception" to "reception area",
        "waiting room" to "waiting room",
        "office" to "office",
        "office cubicles" to "office",
        "office building" to "office",
        "conference room" to "meeting room",
        "computer room" to "computer room",
        "classroom" to "classroom",
        "lecture room" to "lecture hall",
        "kitchen" to "kitchen",
        "dining room" to "dining area",
        "dining hall" to "dining hall",
        "restaurant" to "restaurant",
        "coffee shop" to "cafe",
        "cafeteria" to "cafeteria",
        "living room" to "living room",
        "home office" to "home office",
        "bedroom" to "bedroom",
        "bathroom" to "bathroom",
        "staircase" to "stairway",
        "elevator shaft" to "lift",
        "library indoor" to "library",
        "library outdoor" to "library",
        "bookstore" to "bookshop",
        "supermarket" to "supermarket",
        "department store" to "store",
        "clothing store" to "store",
        "shoe shop" to "store",
        "parking garage indoor" to "car park",
        "parking garage outdoor" to "car park",
        "corridor indoor" to "corridor",
    )

    /** Substring rules for broad families; first match wins, so order from specific to general. */
    private val CONTAINS: List<Pair<String, String>> = listOf(
        "elevator" to "lift",
        "stair" to "stairway",
        "corridor" to "corridor",
        "hallway" to "hallway",
        "kitchen" to "kitchen",
        "bedroom" to "bedroom",
        "bathroom" to "bathroom",
        "office" to "office",
        "classroom" to "classroom",
        "restaurant" to "restaurant",
        "store" to "store",
        "shop" to "store",
        "market" to "market",
    )
}
