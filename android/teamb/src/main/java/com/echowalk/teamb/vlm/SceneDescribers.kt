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
        // NOTE: The SmolVLM LlmModule path is intentionally disabled. The bundled SmolVLM `.pte`
        // files were exported against a QNN/ExecuTorch build that mismatches this app's runtime, so
        // the encoder forward fails at inference (ExecuTorch Error 0x10) and we'd silently fall back
        // anyway — after a ~1s stall, and with ambient (auto) mode unavailable (a VLM isn't an
        // AmbientScene). Until the PTEs are re-exported against a matching commit, go straight to the
        // Places365 scene classifier: it's fast, supports ambient mode, and ModeManager pairs its
        // scene label with live YOLO object directions ("couch on your left, tv ahead").
        ClassifierSceneDescriber.create(
            context,
            fallback = MockSceneDescriber(),
        )

    /** Short tag for the UI/logs describing which engine actually got built. */
    fun engineLabel(describer: SceneDescriber): String = when (describer) {
        is LlmModuleSceneDescriber -> "VLM"
        is ClassifierSceneDescriber -> describer.engine
        else -> "MOCK"
    }
}
