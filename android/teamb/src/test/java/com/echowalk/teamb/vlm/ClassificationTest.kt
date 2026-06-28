package com.echowalk.teamb.vlm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassificationTest {

    private val labels = listOf("tabby, tabby cat", "coffee_mug", "office chair", "banana")

    @Test fun `topK returns highest scoring indices in order`() {
        val scores = floatArrayOf(0.1f, 5.0f, 2.0f, 0.3f)
        assertEquals(listOf(1, 2, 3), Classification.topK(scores, 3))
    }

    @Test fun `cleanLabel keeps first synonym and tidies`() {
        assertEquals("tabby", Classification.cleanLabel("tabby, tabby cat"))
        assertEquals("coffee mug", Classification.cleanLabel("coffee_mug"))
    }

    @Test fun `withArticle picks a or an`() {
        assertEquals("a coffee mug", Classification.withArticle("coffee mug"))
        assertEquals("an office chair".let { "an office chair" }, Classification.withArticle("office chair"))
    }

    @Test fun `toPhrases narrates confident top labels`() {
        // Index 2 ("office chair") dominates -> high prob; others negligible.
        val scores = floatArrayOf(0f, 0f, 10f, 0f)
        val phrases = Classification.toPhrases(scores, labels, k = 3, minProb = 0.10f)
        assertEquals(listOf("an office chair"), phrases)
    }

    @Test fun `toPhrases drops low-confidence guesses`() {
        // Roughly uniform -> each prob ~0.25, below a 0.5 threshold -> nothing narrated.
        val scores = floatArrayOf(1f, 1f, 1f, 1f)
        val phrases = Classification.toPhrases(scores, labels, k = 3, minProb = 0.5f)
        assertTrue(phrases.isEmpty())
    }

    @Test fun `softmax sums to one`() {
        val p = Classification.softmax(floatArrayOf(1f, 2f, 3f))
        assertEquals(1.0f, p.sum(), 1e-5f)
    }

    @Test fun `meanLogits averages element-wise`() {
        val avg = Classification.meanLogits(
            listOf(floatArrayOf(0f, 2f, 4f), floatArrayOf(2f, 2f, 8f)),
        )
        assertEquals(listOf(1f, 2f, 6f), avg.toList())
    }

    @Test fun `meanLogits tolerates empties`() {
        assertTrue(Classification.meanLogits(emptyList()).isEmpty())
        val avg = Classification.meanLogits(listOf(floatArrayOf(), floatArrayOf(4f, 6f)))
        assertEquals(listOf(4f, 6f), avg.toList())
    }

    @Test fun `mergedTopTerms sums synonyms and ranks`() {
        val sceneLabels = listOf("office cubicles", "office building", "corridor")
        // Two office variants each strong; corridor weak. Merged office should dominate.
        val scores = floatArrayOf(3f, 3f, 1f)
        val merged = Classification.mergedTopTerms(
            scores, sceneLabels, mapper = { if (it.startsWith("office")) "office" else it },
            consider = 3, out = 2,
        )
        assertEquals("office", merged.first().label)
        assertTrue("merged office prob should exceed any single class", merged.first().prob > 0.5f)
    }
}
