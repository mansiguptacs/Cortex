package com.echowalk.teamb

import com.echowalk.shared.Frame
import kotlinx.coroutines.delay

/**
 * A no-model [SceneDescriber] used to build and test the Android pipeline (capture -> describe ->
 * speak) before Jainil's real `vlm.pte` arrives. Cycles through canned lines and simulates a bit of
 * inference latency so the UI states (Step 3) are visible.
 *
 * Swap this for SmolVlmSceneDescriber at U-Step 4/5.
 */
class MockSceneDescriber : SceneDescriber {

    private val cannedLines = listOf(
        "You are in a small room. There is a table ahead and a chair to your left.",
        "An open doorway is in front of you, with a window on the right wall.",
        "A narrow hallway extends ahead; there is a low cabinet on your right.",
    )
    private var index = 0

    override suspend fun describe(frame: Frame): String {
        delay(800) // simulate model latency
        return cannedLines[index++ % cannedLines.size]
    }
}
