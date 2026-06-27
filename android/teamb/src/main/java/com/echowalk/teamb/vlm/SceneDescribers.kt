package com.echowalk.teamb.vlm

import android.content.Context
import com.echowalk.teamb.MockSceneDescriber
import com.echowalk.teamb.SceneDescriber

/**
 * One place to build the best available [SceneDescriber], used by both the Team B harness and the
 * full app's ModeManager wiring (U-Step 6). Preference order, each falling back to the next:
 *   1. `vlm.pte`        -> [SmolVlmSceneDescriber]   (engine "VLM")
 *   2. `classifier.pte` -> [ClassifierSceneDescriber] (engine "TAGS")
 *   3. otherwise        -> [MockSceneDescriber]       (engine "MOCK")
 */
object SceneDescribers {

    fun create(context: Context): SceneDescriber =
        SmolVlmSceneDescriber.create(
            context,
            fallback = ClassifierSceneDescriber.create(
                context,
                fallback = MockSceneDescriber(),
            ),
        )

    /** Short tag for the UI/logs describing which engine actually got built. */
    fun engineLabel(describer: SceneDescriber): String = when (describer) {
        is SmolVlmSceneDescriber -> "VLM"
        is ClassifierSceneDescriber -> "TAGS"
        else -> "MOCK"
    }
}
