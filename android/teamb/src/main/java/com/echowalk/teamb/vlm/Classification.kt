package com.echowalk.teamb.vlm

/**
 * Pure-Kotlin decode helpers for a single-forward classifier (no Android deps -> JVM-testable).
 * Turns raw logits + a label list into clean, spoken-ready phrases.
 */
object Classification {

    /** Indices of the [k] highest scores, highest first. */
    fun topK(scores: FloatArray, k: Int): List<Int> =
        scores.indices.sortedByDescending { scores[it] }.take(k.coerceAtMost(scores.size))

    /** Top-k cleaned labels with their softmax probabilities (unfiltered), for HUD/diagnostics. */
    fun scoredTopK(scores: FloatArray, labels: List<String>, k: Int = 3): List<LabelScore> {
        val probs = softmax(scores)
        return topK(scores, k).mapNotNull { idx ->
            labels.getOrNull(idx)?.let { LabelScore(cleanLabel(it), probs.getOrElse(idx) { 0f }) }
        }
    }

    /**
     * Map top-k indices to spoken phrases, dropping anything below [minScore] (after softmax) so we
     * don't narrate low-confidence guesses. Returns articled phrases like "a coffee mug".
     */
    fun toPhrases(
        scores: FloatArray,
        labels: List<String>,
        k: Int = 3,
        minProb: Float = 0.10f,
    ): List<String> {
        val probs = softmax(scores)
        return topK(scores, k)
            .filter { probs.getOrElse(it) { 0f } >= minProb }
            .mapNotNull { idx -> labels.getOrNull(idx)?.let { withArticle(cleanLabel(it)) } }
    }

    /** ImageNet labels often look like "tabby, tabby cat"; keep the first, most common synonym. */
    fun cleanLabel(raw: String): String =
        raw.substringBefore(',').trim().replace('_', ' ').lowercase()

    /** Prefix "a"/"an" unless the label is already plural-ish or empty. */
    fun withArticle(label: String): String {
        if (label.isEmpty()) return label
        val article = if (label.first() in "aeiou") "an" else "a"
        return "$article $label"
    }

    fun softmax(scores: FloatArray): FloatArray {
        if (scores.isEmpty()) return scores
        val max = scores.max()
        val exps = FloatArray(scores.size) { kotlin.math.exp((scores[it] - max).toDouble()).toFloat() }
        val sum = exps.sum().takeIf { it > 0f } ?: return FloatArray(scores.size)
        return FloatArray(scores.size) { exps[it] / sum }
    }
}
