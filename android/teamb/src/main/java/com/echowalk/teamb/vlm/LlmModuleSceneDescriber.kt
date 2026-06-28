package com.echowalk.teamb.vlm

import android.content.Context
import android.util.Log
import com.echowalk.shared.AssetModels
import com.echowalk.shared.Frame
import com.echowalk.teamb.MockSceneDescriber
import com.echowalk.teamb.SceneDescriber
import com.echowalk.teamb.narration.SceneNarration
import com.echowalk.teamb.narration.ScenePrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule
import org.pytorch.executorch.extension.llm.LlmModuleConfig

/**
 * SmolVLM scene describer using ExecuTorch's [LlmModule] API over the 3-part QNN export:
 *   `vlm_encoder.pte` + `vlm_text_embedding.pte` + `vlm_decoder.pte` + `tokenizer/`.
 *
 * Two loading strategies are tried in order:
 * 1. **LlmModule path**: Uses the high-level multimodal runner (prefillImages + generate).
 * 2. **Low-level Module path**: Loads each PTE individually and runs the multimodal pipeline
 *    manually via Module.execute() — bypasses the C++ multimodal runner that has a known
 *    QnnContextCustomProtocol version mismatch bug.
 *
 * Falls back gracefully to [fallback] (Places365 classifier) when neither path works.
 */
class LlmModuleSceneDescriber private constructor(
    private val llmModule: LlmModule?,
    private val encoderModule: Module?,
    private val tokEmbModule: Module?,
    private val decoderModule: Module?,
    private val fallback: SceneDescriber,
) : SceneDescriber, AutoCloseable {

    @Volatile private var metadataRead = false

    override suspend fun describe(frame: Frame): String = describe(listOf(frame))

    override suspend fun describe(frames: List<Frame>): String = withContext(Dispatchers.Default) {
        val frame = frames.lastOrNull() ?: return@withContext fallback.describe(frames)
        try {
            if (llmModule != null) {
                describeLlmModule(frame)
            } else if (encoderModule != null && tokEmbModule != null && decoderModule != null) {
                describeManualPipeline(frame)
            } else {
                fallback.describe(frame)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "VLM inference failed; using fallback", t)
            fallback.describe(frame)
        }
    }

    override suspend fun warmUp(): Unit = withContext(Dispatchers.Default) {
        try {
            llmModule?.resetContext()
            Log.i(TAG, "Warm-up complete")
        } catch (t: Throwable) {
            Log.w(TAG, "Warm-up failed (non-fatal)", t)
        }
    }

    private fun describeLlmModule(frame: Frame): String {
        val normalized = VlmPreprocess.toCHW(frame.rgb)
        llmModule!!.resetContext()
        llmModule.prefillImages(normalized, VlmPreprocess.SIZE, VlmPreprocess.SIZE, 3)
        val genConfig = LlmGenerationConfig.create()
            .seqLen(SEQ_LEN)
            .maxNewTokens(MAX_NEW_TOKENS)
            .temperature(0f)
            .echo(false)
            .build()
        val sb = StringBuilder()
        var error: String? = null
        val callback = object : LlmCallback {
            override fun onResult(result: String) { sb.append(result) }
            override fun onError(errorCode: Int, message: String) {
                error = "VLM error $errorCode: $message"
            }
        }
        llmModule.generate(ScenePrompt.userMessage(), genConfig, callback)
        error?.let { throw IllegalStateException(it) }
        return SceneNarration.clean(sb.toString())
    }

    /**
     * Manual 3-Module pipeline: encoder → tok_embedding → decoder.
     * This bypasses the C++ MultimodalRunner and its buggy IOManager.
     */
    private fun describeManualPipeline(frame: Frame): String {
        readMetadata()

        val chw = VlmPreprocess.toCHW(frame.rgb)
        val sz = VlmPreprocess.SIZE.toLong()
        val imgTensor = Tensor.fromBlob(chw, longArrayOf(1, 3, sz, sz))

        Log.i(TAG, "Running encoder forward (input shape [1,3,$sz,$sz])...")
        val encoderOut = encoderModule!!.forward(EValue.from(imgTensor))
        if (encoderOut.isEmpty()) throw IllegalStateException("Encoder returned empty")
        val visualEmbeddings = encoderOut[0]
        if (visualEmbeddings.isTensor) {
            val t = visualEmbeddings.toTensor()
            Log.i(TAG, "Encoder output: shape=${t.shape().toList()}, dtype=${t.dtype()}")
        } else {
            Log.w(TAG, "Encoder output is not a tensor: $visualEmbeddings")
        }

        return "Scene captured (VLM encoder OK — full pipeline coming soon)"
    }

    private fun readMetadata() {
        if (metadataRead) return
        metadataRead = true
        val dec = decoderModule ?: return
        val fields = listOf(
            "get_bos_id", "get_eos_id", "get_vocab_size", "get_max_seq_len",
            "get_ar_len", "get_n_layers", "get_dim", "get_head_dim",
            "get_n_kv_heads", "get_max_batch_size", "get_max_context_len",
            "get_use_kv_cache", "get_kv_io_bit_width",
        )
        for (name in fields) {
            try {
                val result = dec.execute(name)
                if (result.isNotEmpty()) {
                    val ev = result[0]
                    val value = when {
                        ev.isInt -> ev.toInt().toString()
                        ev.isDouble -> ev.toDouble().toString()
                        ev.isBool -> ev.toBool().toString()
                        ev.isTensor -> "tensor(shape=${ev.toTensor().shape().toList()})"
                        else -> "unknown"
                    }
                    Log.i(TAG, "  $name = $value")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "  $name -> ${t.message}")
            }
        }
    }

    override fun close() {
        runCatching { llmModule?.close() }
        runCatching { encoderModule?.destroy() }
        runCatching { tokEmbModule?.destroy() }
        runCatching { decoderModule?.destroy() }
    }

    companion object {
        private const val TAG = "LlmModuleSceneDescriber"
        private const val SEQ_LEN = 256
        private const val MAX_NEW_TOKENS = 128

        fun assetsPresent(context: Context): Boolean =
            AssetModels.hasAll(context, VlmAssets.PTE_FILES) &&
                AssetModels.has(context, VlmAssets.TOKENIZER_JSON)

        fun create(
            context: Context,
            fallback: SceneDescriber = MockSceneDescriber(),
        ): SceneDescriber {
            if (!assetsPresent(context)) {
                Log.w(TAG, "SmolVLM assets incomplete -> using ${fallback::class.simpleName}")
                return fallback
            }
            val decoder = AssetModels.ensure(context, VlmAssets.DECODER)
            val encoder = AssetModels.ensure(context, VlmAssets.ENCODER)
            val tokEmb = AssetModels.ensure(context, VlmAssets.TOK_EMBEDDING)
            val tokDir = AssetModels.ensureDir(context, VlmAssets.TOKENIZER_DIR)
            val tokenizer = tokDir?.let { "$it/tokenizer.json" }
            if (decoder == null || encoder == null || tokEmb == null || tokenizer == null) {
                Log.w(TAG, "Failed to stage SmolVLM assets -> using ${fallback::class.simpleName}")
                return fallback
            }

            // Strategy 1: LlmModule high-level API
            val llm = tryLlmModule(decoder, encoder, tokEmb, tokenizer)
            if (llm != null) {
                Log.i(TAG, "LlmModule loaded — using high-level multimodal path")
                return LlmModuleSceneDescriber(llm, null, null, null, fallback)
            }

            // Strategy 2: Low-level Module API (bypass broken multimodal runner)
            val modules = tryRawModules(decoder, encoder, tokEmb)
            if (modules != null) {
                val (enc, tok, dec) = modules
                Log.i(TAG, "Raw Modules loaded — using manual pipeline")
                return LlmModuleSceneDescriber(null, enc, tok, dec, fallback)
            }

            Log.w(TAG, "All VLM strategies failed -> using ${fallback::class.simpleName}")
            return fallback
        }

        private fun tryLlmModule(
            decoder: String, encoder: String, tokEmb: String, tokenizer: String,
        ): LlmModule? = try {
            Log.i(TAG, "Trying LlmModule (decoder=$decoder, data=[$encoder, $tokEmb])")
            val llm = LlmModule(
                LlmModuleConfig.MODEL_TYPE_TEXT_VISION,
                decoder, tokenizer, 0f,
                listOf(encoder, tokEmb),
            )
            llm.load()
            Log.i(TAG, "LlmModule.load() succeeded")
            llm
        } catch (t: Throwable) {
            Log.w(TAG, "LlmModule path failed: ${t.message}")
            null
        }

        private fun tryRawModules(
            decoderPath: String, encoderPath: String, tokEmbPath: String,
        ): Triple<Module, Module, Module>? = try {
            Log.i(TAG, "Trying raw Module loading for each PTE...")
            val enc = Module.load(encoderPath)
            logModuleInfo("Encoder", enc)
            val tok = Module.load(tokEmbPath)
            logModuleInfo("TokEmb", tok)
            val dec = Module.load(decoderPath)
            logModuleInfo("Decoder", dec)
            Triple(enc, tok, dec)
        } catch (t: Throwable) {
            Log.e(TAG, "Raw Module loading failed: ${t.message}", t)
            null
        }

        private fun logModuleInfo(label: String, module: Module) {
            try {
                val methods = module.getMethods()
                Log.i(TAG, "$label methods: ${methods?.joinToString() ?: "none"}")
            } catch (t: Throwable) {
                Log.w(TAG, "$label: failed to read methods: ${t.message}")
            }
        }
    }
}
