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

    /**
     * Element-wise mean of several logit vectors (one per frame) for temporal voting. Averaging
     * logits across a short burst suppresses one-off misfires (a single blurry/odd frame) and makes
     * the resulting confidence far more trustworthy. Ignores empty/mismatched vectors.
     */
    fun meanLogits(perFrame: List<FloatArray>): FloatArray {
        val valid = perFrame.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return FloatArray(0)
        val n = valid.first().size
        val sum = DoubleArray(n)
        var count = 0
        for (v in valid) {
            if (v.size != n) continue
            for (i in 0 until n) sum[i] = sum[i] + v[i].toDouble()
            count++
        }
        if (count == 0) return FloatArray(0)
        return FloatArray(n) { (sum[it] / count).toFloat() }
    }

    /**
     * Merge per-class probabilities into a friendly-term ranking: synonyms that [mapper] sends to the
     * same term have their probabilities summed (mutually-exclusive classes), so "office" beats a
     * field split across "office"/"office cubicles". Returns merged terms, most probable first.
     */
    fun mergedTopTerms(
        scores: FloatArray,
        labels: List<String>,
        mapper: (String) -> String,
        consider: Int = 12,
        out: Int = 3,
    ): List<LabelScore> {
        if (scores.isEmpty()) return emptyList()
        val probs = softmax(scores)
        val byTerm = LinkedHashMap<String, Float>()
        for (idx in topK(scores, consider)) {
            val label = labels.getOrNull(idx) ?: continue
            val term = mapper(cleanLabel(label))
            byTerm[term] = (byTerm[term] ?: 0f) + probs.getOrElse(idx) { 0f }
        }
        return byTerm.entries
            .sortedByDescending { it.value }
            .take(out)
            .map { LabelScore(it.key, it.value) }
    }

    fun softmax(scores: FloatArray): FloatArray {
        if (scores.isEmpty()) return scores
        val max = scores.max()
        val exps = FloatArray(scores.size) { kotlin.math.exp((scores[it] - max).toDouble()).toFloat() }
        val sum = exps.sum().takeIf { it > 0f } ?: return FloatArray(scores.size)
        return FloatArray(scores.size) { exps[it] / sum }
    }
}
