package com.echowalk.places

/** Result of matching a query embedding against the enrolled landmarks. */
data class Match(
    val landmarkId: Long,
    val label: String,
    val score: Float,
)

/**
 * Stateless nearest-landmark search by cosine similarity.
 *
 * Design choices baked in (from the plan):
 * - A landmark's score is the MAX cosine over its enrolled embeddings (multi-frame robustness).
 * - Conservative gate: [best] returns null when the top score is below [threshold] so the app
 *   stays SILENT rather than risking a false "you're at X".
 *
 * Brute force is intentional: only a few hundred vectors exist, so no ANN index is needed.
 */
class CosineMatcher(
    val threshold: Float = DEFAULT_THRESHOLD,
) {
    init {
        require(threshold in -1f..1f) { "threshold must be in [-1,1], was $threshold" }
    }

    /** Score every landmark (max over its embeddings), sorted best-first. No threshold applied. */
    fun rank(query: FloatArray, landmarks: List<Landmark>): List<Match> =
        landmarks
            .mapNotNull { lm ->
                val best = lm.embeddings.maxOfOrNull { Vectors.cosine(query, it) } ?: return@mapNotNull null
                Match(lm.id, lm.label, best)
            }
            .sortedByDescending { it.score }

    /** Best match strictly above [threshold], or null (stay silent) if none qualifies. */
    fun best(query: FloatArray, landmarks: List<Landmark>): Match? =
        rank(query, landmarks).firstOrNull()?.takeIf { it.score >= threshold }

    companion object {
        /**
         * Placeholder default. The REAL value is set empirically in M-C0 from same-spot vs
         * different-spot cosine distributions. Kept conservative on purpose.
         */
        const val DEFAULT_THRESHOLD = 0.75f
    }
}
