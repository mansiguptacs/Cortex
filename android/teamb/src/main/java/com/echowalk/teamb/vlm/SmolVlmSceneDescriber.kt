package com.echowalk.teamb.vlm

import android.content.Context
import android.util.Log
import com.echowalk.shared.AssetModels
import com.echowalk.shared.EtModule
import com.echowalk.shared.ExecuTorchModule
import com.echowalk.shared.Frame
import com.echowalk.teamb.MockSceneDescriber
import com.echowalk.teamb.SceneDescriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real [SceneDescriber] backed by a SmolVLM `.pte` running through the ExecuTorch QNN delegate.
 *
 * U-Step 4 (current): loading, preprocessing and the forward call are wired up, with a graceful
 * [fallback] to [MockSceneDescriber] whenever the model asset is absent or inference throws — so the
 * harness/app always works even before Jainil's `vlm.pte` lands.
 *
 * U-Step 5 (next): [decode] needs Jainil's tokenizer + autoregressive loop. A single [EtModule.forward]
 * can't emit caption tokens on its own; today [decode] just confirms the forward ran and defers to the
 * fallback for the actual words.
 */
class SmolVlmSceneDescriber private constructor(
    private val module: EtModule,
    private val fallback: SceneDescriber,
) : SceneDescriber {

    override suspend fun describe(frame: Frame): String = withContext(Dispatchers.Default) {
        try {
            val input = VlmPreprocess.toCHW(frame.rgb)
            val outputs = module.forward(input, VlmPreprocess.INPUT_SHAPE)
            decode(outputs) ?: fallback.describe(frame)
        } catch (t: Throwable) {
            Log.e(TAG, "VLM inference failed; using fallback", t)
            fallback.describe(frame)
        }
    }

    private fun decode(outputs: Array<FloatArray>): String? {
        Log.i(TAG, "VLM forward ok: ${outputs.size} output(s), first len=${outputs.firstOrNull()?.size}")
        // TODO(U-Step 5): tokenizer + greedy/sampled decode over the decoder's logits.
        return null
    }

    fun close() = module.close()

    companion object {
        private const val TAG = "SmolVlmSceneDescriber"
        private const val ASSET = "vlm.pte"

        /**
         * Build the best available describer: the real VLM if `vlm.pte` is bundled and loads,
         * otherwise [fallback]. Never throws.
         */
        fun create(
            context: Context,
            fallback: SceneDescriber = MockSceneDescriber(),
        ): SceneDescriber {
            val path = AssetModels.ensure(context, ASSET)
            if (path == null) {
                Log.w(TAG, "No '$ASSET' asset -> using ${fallback::class.simpleName}")
                return fallback
            }
            return try {
                Log.i(TAG, "Loading VLM from $path")
                SmolVlmSceneDescriber(ExecuTorchModule.load(path), fallback)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load '$ASSET' -> using ${fallback::class.simpleName}", t)
                fallback
            }
        }
    }
}
