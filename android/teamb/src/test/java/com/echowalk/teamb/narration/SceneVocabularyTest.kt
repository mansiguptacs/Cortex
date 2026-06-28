package com.echowalk.teamb.narration

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneVocabularyTest {

    @Test fun directMatch() {
        assertEquals("corridor", SceneVocabulary.friendly("corridor"))
        assertEquals("office", SceneVocabulary.friendly("office cubicles"))
        assertEquals("meeting room", SceneVocabulary.friendly("conference room"))
        assertEquals("stairway", SceneVocabulary.friendly("staircase"))
    }

    @Test fun synonymsCollapseToSameTerm() {
        assertEquals(SceneVocabulary.friendly("office"), SceneVocabulary.friendly("office building"))
        assertEquals("store", SceneVocabulary.friendly("shoe shop"))
        assertEquals("store", SceneVocabulary.friendly("clothing store"))
    }

    @Test fun containsRuleCatchesLongTail() {
        assertEquals("lift", SceneVocabulary.friendly("elevator door"))
        assertEquals("stairway", SceneVocabulary.friendly("fire escape staircase"))
    }

    @Test fun unknownLabelIsTidiedNotDropped() {
        assertEquals("art gallery", SceneVocabulary.friendly("art gallery"))
        // indoor/outdoor qualifiers stripped
        assertEquals("museum", SceneVocabulary.friendly("museum indoor"))
    }
}
