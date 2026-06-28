package com.echowalk.teamb.vlm

import android.content.Context
import com.echowalk.teamb.MockSceneDescriber
import com.echowalk.teamb.SceneDescriber

/**
 * One place to build the best available [SceneDescriber], used by both the Team B harness and the
 * full app's ModeManager wiring. Preference order, each falling back to the next:
 *   1. SmolVLM 3× `.pte` + tokenizer -> [LlmModuleSceneDescriber]  (engine "VLM")
 *   2. `classifier.pte`              -> [ClassifierSceneDescriber]  (engine "SCENE" / "TAGS")
 *   3. otherwise                     -> [MockSceneDescriber]          (engine "MOCK")
 */
object SceneDescribers {

    fun create(context: Context): SceneDescriber =
        LlmModuleSceneDescriber.create(
            context,
            fallback = ClassifierSceneDescriber.create(
                context,
                fallback = MockSceneDescriber(),
            ),
        )

    /** Short tag for the UI/logs describing which engine actually got built. */
    fun engineLabel(describer: SceneDescriber): String = when (describer) {
        is LlmModuleSceneDescriber -> "VLM"
        is ClassifierSceneDescriber -> describer.engine
        else -> "MOCK"
    }
}
