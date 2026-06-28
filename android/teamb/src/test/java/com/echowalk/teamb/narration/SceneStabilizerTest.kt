package com.echowalk.teamb.narration

import com.echowalk.teamb.narration.SceneStabilizer.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneStabilizerTest {

    private fun stab() = SceneStabilizer(
        minConf = 0.18f, announceConf = 0.30f, stableCycles = 2,
        minIntervalMs = 4_000L, emaAlpha = 0.5f,
    )

    @Test fun `announces only after it is stable and confident`() {
        val s = stab()
        // First confident frame: not yet stable (needs 2 cycles).
        assertEquals(Decision.Silent, s.observe("office", 0.6f, 0))
        // Second agreeing frame: stable + confident + new -> announce.
        val d = s.observe("office", 0.6f, 500)
        assertTrue(d is Decision.Announce && d.term == "office")
    }

    @Test fun `does not repeat the same scene`() {
        val s = stab()
        s.observe("office", 0.6f, 0)
        assertTrue(s.observe("office", 0.6f, 500) is Decision.Announce)
        // Keeps seeing office -> stays silent (no nagging).
        assertEquals(Decision.Silent, s.observe("office", 0.7f, 6_000))
        assertEquals(Decision.Silent, s.observe("office", 0.7f, 12_000))
    }

    @Test fun `low confidence is ignored`() {
        val s = stab()
        assertEquals(Decision.Silent, s.observe("closet", 0.10f, 0))
        assertEquals(Decision.Silent, s.observe("closet", 0.10f, 500))
    }

    @Test fun `respects the speech budget on a real change`() {
        val s = stab()
        s.observe("office", 0.6f, 0)
        assertTrue(s.observe("office", 0.6f, 500) is Decision.Announce) // announced at t=500
        // New scene becomes stable quickly, but within the 4s budget -> stay silent.
        s.observe("kitchen", 0.6f, 1_000)
        assertEquals(Decision.Silent, s.observe("kitchen", 0.6f, 1_500))
        // Once the budget elapses, the change is announced.
        assertTrue(s.observe("kitchen", 0.6f, 5_000) is Decision.Announce)
    }

    @Test fun `announces a genuine change after budget`() {
        val s = stab()
        s.observe("office", 0.6f, 0)
        s.observe("office", 0.6f, 500)
        s.observe("corridor", 0.6f, 6_000)
        val d = s.observe("corridor", 0.6f, 6_500)
        assertTrue(d is Decision.Announce && d.term == "corridor")
    }

    @Test fun `noteAnnounced suppresses an immediate ambient echo`() {
        val s = stab()
        s.noteAnnounced("office", 0)
        // Manual describe already said "office"; ambient should not echo it.
        s.observe("office", 0.6f, 100)
        assertEquals(Decision.Silent, s.observe("office", 0.6f, 600))
    }
}
