package com.echowalk.teamb.vlm

/**
 * Stable asset names for the 3-part SmolVLM QNN export (Jainil handoff).
 *
 * Drop these under `android/app/src/main/assets/` (see `DROP_MODELS_HERE.md` and
 * `tools/stage_vlm_assets.sh`). Large `.pte` files are gitignored; stage from the handoff zip.
 */
object VlmAssets {
    const val ENCODER = "vlm_encoder.pte"
    const val TOK_EMBEDDING = "vlm_text_embedding.pte"
    const val DECODER = "vlm_decoder.pte"
    const val TOKENIZER_DIR = "tokenizer"
    const val TOKENIZER_JSON = "tokenizer/tokenizer.json"

    val PTE_FILES = listOf(ENCODER, TOK_EMBEDDING, DECODER)
}
