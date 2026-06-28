package com.echowalk.teamb.narration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Runs on the JVM in milliseconds (no device, no emulator).
 * Run all: `./gradlew :teamb:testDebugUnitTest` — or click the gutter arrow in Android Studio.
 */
class SceneNarrationTest {

    @Test fun empty() {
        assertEquals("I can't quite tell what's ahead.", SceneNarration.fromTags(emptyList()))
    }

    @Test fun one() {
        assertEquals("I see a chair.", SceneNarration.fromTags(listOf("a chair")))
    }

    @Test fun two() {
        assertEquals("I see a chair and a table.", SceneNarration.fromTags(listOf("a chair", "a table")))
    }

    @Test fun manyDedupesAndJoins() {
        val out = SceneNarration.fromTags(listOf("a chair", "a table", "a chair", "a laptop"))
        assertEquals("I see a chair, a table, and a laptop.", out)
    }

    @Test fun cleanNormalizesWhitespaceAndPunctuation() {
        assertEquals("A kitchen with a table.", SceneNarration.clean("  a kitchen   with a table  "))
    }

    @Test fun sceneEmpty() {
        assertEquals(
            "I can't quite tell what kind of space this is.",
            SceneNarration.fromScene(emptyList()),
        )
    }

    @Test fun sceneOneUsesArticle() {
        assertEquals("You appear to be in an office.", SceneNarration.fromScene(listOf("office")))
        assertEquals("You appear to be in a kitchen.", SceneNarration.fromScene(listOf("kitchen")))
    }

    @Test fun sceneTwoHedges() {
        assertEquals(
            "This looks like a corridor, possibly a lobby.",
            SceneNarration.fromScene(listOf("corridor", "lobby")),
        )
    }

    private fun p(term: String, prob: Float) = SceneNarration.ScenePrediction(term, prob)

    @Test fun rankedHighConfidenceIsAssertive() {
        assertEquals(
            "You're in a kitchen.",
            SceneNarration.fromSceneRanked(listOf(p("kitchen", 0.72f))),
        )
    }

    @Test fun rankedMidConfidenceHedgesWithSecond() {
        assertEquals(
            "This looks like an office, maybe a meeting room.",
            SceneNarration.fromSceneRanked(listOf(p("office", 0.30f), p("meeting room", 0.20f))),
        )
    }

    @Test fun rankedMidConfidenceNoConfidentSecond() {
        assertEquals(
            "This looks like a corridor.",
            SceneNarration.fromSceneRanked(listOf(p("corridor", 0.28f), p("lobby", 0.05f))),
        )
    }

    @Test fun rankedLowConfidenceIsTentative() {
        assertEquals(
            "I'm not sure, but it might be a stairway.",
            SceneNarration.fromSceneRanked(listOf(p("stairway", 0.14f))),
        )
    }

    @Test fun briefIsJustThePlace() {
        assertEquals("Kitchen.", SceneNarration.brief("kitchen"))
        assertEquals("Meeting room.", SceneNarration.brief("meeting room"))
        assertEquals("", SceneNarration.brief("  "))
    }

    @Test fun rankedVeryLowGivesUp() {
        assertEquals(
            "I can't quite tell what kind of space this is.",
            SceneNarration.fromSceneRanked(listOf(p("attic", 0.05f))),
        )
        assertEquals(
            "I can't quite tell what kind of space this is.",
            SceneNarration.fromSceneRanked(emptyList()),
        )
    }
}
