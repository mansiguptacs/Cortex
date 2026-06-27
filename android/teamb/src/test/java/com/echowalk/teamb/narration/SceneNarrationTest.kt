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
}
