package com.echowalk.teamb.narration

/**
 * Centralized prompts for the SmolVLM scene-description path (b5 tuning happens here).
 *
 * The adb runner uses the same default text; keep them aligned with `IO_SPEC.md`.
 */
object ScenePrompt {

    /** Plain user message passed to [org.pytorch.executorch.extension.llm.LlmModule.generate] after image prefill. */
    fun userMessage(): String =
        "Describe this indoor scene clearly for a blind person. " +
            "Mention layout, major objects, and anything in the walking path. " +
            "Keep it to 2-3 sentences."
}
