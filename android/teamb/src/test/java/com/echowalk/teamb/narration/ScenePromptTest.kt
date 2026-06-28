package com.echowalk.teamb.narration

import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePromptTest {
    @Test
    fun userMessage_mentionsBlindPersonAndLayout() {
        val msg = ScenePrompt.userMessage()
        assertTrue(msg.contains("blind", ignoreCase = true))
        assertTrue(msg.contains("layout", ignoreCase = true))
    }
}
