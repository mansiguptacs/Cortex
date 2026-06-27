package com.echowalk.teamb.vlm

import android.content.Context
import android.util.Log
import com.echowalk.shared.AssetModels
import com.echowalk.shared.EtModule
import com.echowalk.shared.ExecuTorchModule
import com.echowalk.shared.Frame
import com.echowalk.teamb.MockSceneDescriber
import com.echowalk.teamb.SceneDescriber
import com.echowalk.teamb.narration.SceneNarration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single-forward [SceneDescriber] for an image classifier `.pte` (e.g. MobileNet/EfficientNet from
 * Qualcomm AI Hub). Runs one forward pass, takes the top-k labels, and narrates them as a sentence
 * like "I see a chair, a table, and a doorway." Falls back to [fallback] on any failure.
 *
 * This fits the frozen [EtModule.forward] contract today — no tokenizer/autoregression needed —
 * so it's the quickest path to real, camera-driven descriptions on the NPU.
 */
class ClassifierSceneDescriber private constructor(
    private val module: EtModule,
    private val labels: List<String>,
    private val fallback: SceneDescriber,
    /** scene classifier (Places365) -> "You appear to be in ..."; else object -> "I see ...". */
    private val sceneMode: Boolean,
) : SceneDescriber, SceneDiagnostics {

    @Volatile
    private var lastTopK: List<LabelScore> = emptyList()

    /** HUD/log tag: scene classifier vs object classifier. */
    val engine: String get() = if (sceneMode) "SCENE" else "TAGS"

    override fun lastTopK(): List<LabelScore> = lastTopK

    override suspend fun describe(frame: Frame): String = withContext(Dispatchers.Default) {
        try {
            val input = VlmPreprocess.toCHW(
                frame.rgb,
                size = INPUT_SIZE,
                mean = VlmPreprocess.IMAGENET_MEAN,
                std = VlmPreprocess.IMAGENET_STD,
            )
            val logits = module.forward(input, intArrayOf(1, 3, INPUT_SIZE, INPUT_SIZE)).firstOrNull()
                ?: return@withContext fallback.describe(frame)
            val scored = Classification.scoredTopK(logits, labels, k = TOP_K)
            lastTopK = scored
            if (sceneMode) {
                // Always speak the best scene; add a hedge only if the runner-up is plausible.
                val tags = buildList {
                    scored.getOrNull(0)?.let { add(it.label) }
                    scored.getOrNull(1)?.takeIf { it.prob >= SCENE_SECOND_MIN }?.let { add(it.label) }
                }
                SceneNarration.fromScene(tags)
            } else {
                SceneNarration.fromTags(Classification.toPhrases(logits, labels, k = TOP_K))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Classifier inference failed; using fallback", t)
            fallback.describe(frame)
        }
    }

    fun close() = module.close()

    companion object {
        private const val TAG = "ClassifierSceneDescriber"
        private const val MODEL_ASSET = "classifier.pte"
        private const val LABELS_ASSET = "labels.txt"
        private const val KIND_ASSET = "classifier_kind.txt"
        private const val INPUT_SIZE = 224
        private const val TOP_K = 3

        /** Min softmax prob for the *second* scene guess before we voice the "possibly ..." hedge. */
        private const val SCENE_SECOND_MIN = 0.15f

        /** Build the classifier describer if `classifier.pte` + `labels.txt` are bundled, else [fallback]. */
        fun create(
            context: Context,
            fallback: SceneDescriber = MockSceneDescriber(),
        ): SceneDescriber {
            val path = AssetModels.ensure(context, MODEL_ASSET) ?: run {
                Log.w(TAG, "No '$MODEL_ASSET' -> using ${fallback::class.simpleName}")
                return fallback
            }
            val labels = readLabels(context)
            if (labels.isEmpty()) {
                Log.w(TAG, "No '$LABELS_ASSET' (or empty) -> using ${fallback::class.simpleName}")
                return fallback
            }
            val sceneMode = readKind(context) == "scene"
            return try {
                Log.i(TAG, "Loading classifier from $path: ${labels.size} labels, sceneMode=$sceneMode")
                ClassifierSceneDescriber(ExecuTorchModule.load(path), labels, fallback, sceneMode)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load '$MODEL_ASSET' -> using ${fallback::class.simpleName}", t)
                fallback
            }
        }

        private fun readLabels(context: Context): List<String> = try {
            context.assets.open(LABELS_ASSET).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
        } catch (e: Exception) {
            emptyList()
        }

        /** Optional `classifier_kind.txt` marker ("scene" | "object"); defaults to object. */
        private fun readKind(context: Context): String = try {
            context.assets.open(KIND_ASSET).bufferedReader().use { it.readText().trim().lowercase() }
        } catch (e: Exception) {
            "object"
        }
    }
}
