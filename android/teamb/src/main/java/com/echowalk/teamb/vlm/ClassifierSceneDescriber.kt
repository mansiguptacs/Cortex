package com.echowalk.teamb.vlm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.echowalk.shared.AssetModels
import com.echowalk.shared.EtModule
import com.echowalk.shared.ExecuTorchModule
import com.echowalk.shared.Frame
import com.echowalk.teamb.MockSceneDescriber
import com.echowalk.teamb.SceneDescriber
import com.echowalk.teamb.narration.SceneNarration
import com.echowalk.teamb.narration.SceneVocabulary
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
) : SceneDescriber, SceneDiagnostics, AmbientScene {

    @Volatile
    private var lastTopK: List<LabelScore> = emptyList()

    /** HUD/log tag: scene classifier vs object classifier. */
    val engine: String get() = if (sceneMode) "SCENE" else "TAGS"

    override fun lastTopK(): List<LabelScore> = lastTopK

    override suspend fun describe(frame: Frame): String = describe(listOf(frame))

    /** Temporal voting: average logits over the burst, then narrate the merged result. */
    override suspend fun describe(frames: List<Frame>): String = withContext(Dispatchers.Default) {
        try {
            val perFrame = frames.mapNotNull { forwardLogits(it.rgb) }
            if (perFrame.isEmpty()) return@withContext fallback.describe(frames.last())
            narrate(Classification.meanLogits(perFrame))
        } catch (t: Throwable) {
            Log.e(TAG, "Classifier inference failed; using fallback", t)
            fallback.describe(frames.last())
        }
    }

    override suspend fun warmUp(): Unit = withContext(Dispatchers.Default) {
        try {
            val bmp = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            forwardLogits(bmp)
            bmp.recycle()
            Log.i(TAG, "Warm-up forward complete (engine=$engine)")
        } catch (t: Throwable) {
            Log.w(TAG, "Warm-up failed (non-fatal)", t)
        }
        Unit
    }

    /** Ambient mode: rank scene terms for one frame without speaking. Scene classifier only. */
    override suspend fun rankScenes(frame: Frame): List<LabelScore> = withContext(Dispatchers.Default) {
        if (!sceneMode) return@withContext emptyList()
        val logits = forwardLogits(frame.rgb) ?: return@withContext emptyList()
        val merged = Classification.mergedTopTerms(
            logits, labels, SceneVocabulary::friendly, consider = CONSIDER, out = TOP_K,
        )
        lastTopK = merged // keep the HUD live during ambient mode too
        merged
    }

    private fun forwardLogits(bitmap: Bitmap): FloatArray? {
        val input = VlmPreprocess.toCHW(
            bitmap,
            size = INPUT_SIZE,
            mean = VlmPreprocess.IMAGENET_MEAN,
            std = VlmPreprocess.IMAGENET_STD,
        )
        return module.forward(input, intArrayOf(1, 3, INPUT_SIZE, INPUT_SIZE)).firstOrNull()
    }

    private fun narrate(logits: FloatArray): String {
        if (logits.isEmpty()) return SceneNarration.fromTags(emptyList())
        return if (sceneMode) {
            val merged = Classification.mergedTopTerms(
                logits, labels, SceneVocabulary::friendly, consider = CONSIDER, out = TOP_K,
            )
            lastTopK = merged
            SceneNarration.fromSceneRanked(
                merged.map { SceneNarration.ScenePrediction(it.label, it.prob) },
            )
        } else {
            lastTopK = Classification.scoredTopK(logits, labels, k = TOP_K)
            SceneNarration.fromTags(Classification.toPhrases(logits, labels, k = TOP_K))
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

        /** How many raw classes to fold into the friendly-term merge before ranking. */
        private const val CONSIDER = 12

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
