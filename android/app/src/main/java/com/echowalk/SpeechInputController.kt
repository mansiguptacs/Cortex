package com.echowalk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.echowalk.shared.AudioOutputManager

/**
 * Tap-to-talk voice input that feeds [onTargetResolved] with a COCO class label.
 *
 * Uses Android's built-in [SpeechRecognizer] — works offline on most devices. Replace with
 * Distil-Whisper TFLite for fully on-device operation when the model is bundled.
 *
 * Usage:
 *  - Call [startListening]: beeps, starts mic capture.
 *  - On recognition: parses "find X" / "where is X" / "look for X" → COCO label lookup.
 *  - Speaks back "Looking for X" and calls [onTargetResolved].
 *  - On error or no match: speaks an error message and does NOT call [onTargetResolved].
 */
class SpeechInputController(
    private val context: Context,
    private val audio: AudioOutputManager,
    private val onTargetResolved: (cocoClass: String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            audio.speak("Voice recognition is not available on this device.", flush = false)
            return
        }
        audio.cueCapture()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull()?.lowercase()?.trim() ?: ""
                Log.d(TAG, "STT result: '$transcript'")
                handleTranscript(transcript)
                release()
            }

            override fun onError(error: Int) {
                Log.w(TAG, "STT error code: $error")
                audio.speak("Didn't catch that. Hold Find and try again.", flush = false)
                release()
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        sr.startListening(intent)
    }

    private fun handleTranscript(transcript: String) {
        if (transcript.isBlank()) {
            audio.speak("I didn't hear anything. Try again.", flush = false)
            return
        }
        val cocoClass = extractTarget(transcript)
        if (cocoClass == null) {
            audio.speak("I don't know that object. Try saying something like: find the chair.", flush = false)
            return
        }
        val friendly = COCO_FRIENDLY[cocoClass] ?: cocoClass
        audio.speak("Looking for $friendly", flush = false)
        onTargetResolved(cocoClass)
    }

    /**
     * Parses a voice command and resolves it to a COCO class label.
     * Handles patterns like "find the chair", "where is the cup", "look for a person".
     */
    private fun extractTarget(transcript: String): String? {
        // Strip common command prefixes.
        val cleaned = transcript
            .replace(Regex("^(find|look for|where is|locate|search for|get me|show me)\\s+(the|a|an)?\\s*"), "")
            .trim()
        if (cleaned.isBlank()) return null
        // Direct COCO match (or alias lookup).
        return ALIASES.entries.firstOrNull { (alias, _) -> cleaned.contains(alias) }?.value
            ?: COCO_CLASSES.firstOrNull { cleaned.contains(it) }
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    companion object {
        private const val TAG = "SpeechInputController"

        // All COCO 80 class names that are useful indoors — used for direct substring match.
        private val COCO_CLASSES = listOf(
            "person", "chair", "couch", "dining table", "tv", "laptop", "cup", "bottle",
            "backpack", "suitcase", "refrigerator", "cell phone", "book", "clock", "bowl",
            "keyboard", "mouse", "remote", "vase", "scissors", "toothbrush", "teddy bear",
            "handbag", "umbrella", "bed", "toilet", "sink", "microwave", "oven", "toaster",
        )

        // Natural-language aliases → COCO class name.
        private val ALIASES = mapOf(
            "fridge" to "refrigerator",
            "sofa" to "couch",
            "table" to "dining table",
            "screen" to "tv",
            "monitor" to "tv",
            "television" to "tv",
            "phone" to "cell phone",
            "mobile" to "cell phone",
            "bag" to "backpack",
            "luggage" to "suitcase",
            "someone" to "person",
            "human" to "person",
            "man" to "person",
            "woman" to "person",
            "kid" to "person",
            "child" to "person",
        )

        // Friendly display names for TTS feedback.
        private val COCO_FRIENDLY = mapOf(
            "dining table" to "table",
            "tv" to "screen",
            "cell phone" to "phone",
            "backpack" to "bag",
            "refrigerator" to "fridge",
        )
    }
}
