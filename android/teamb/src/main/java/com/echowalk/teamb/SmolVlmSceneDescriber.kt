package com.echowalk.teamb

import com.echowalk.shared.EtModule
import com.echowalk.shared.Frame

/**
 * Team B implementation: SmolVLM-500M over QNN (fallback: tags + Llama-3.2-1B).
 *
 * STUB ONLY — fill in (milestone M6). See docs/team-b.md.
 */
class SmolVlmSceneDescriber(
    private val vlmModule: EtModule?, // load SmolVLM .pte here
) : SceneDescriber {

    override suspend fun describe(frame: Frame): String {
        // TODO(Team B): preprocess frame -> run VLM -> decode text.
        return "Scene description not implemented yet."
    }
}
