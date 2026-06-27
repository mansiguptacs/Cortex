package com.echowalk.shared

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The single audio bus. Orders the three sound sources so they never overlap:
 *   1. Safety radar tones (Team A) — continuous, low priority, ducked when speech plays.
 *   2. Place guidance cues (Team C) — short spoken phrases.
 *   3. Scene description (Team B) — longer spoken text.
 *
 * Modules MUST route through here instead of calling TextToSpeech / playing tones directly.
 */
class AudioOutputManager(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val vibrator: Vibrator? =
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    @Volatile
    private var speaking = false

    /** Per-utterance completion callbacks, invoked when that utterance finishes (or errors). */
    private val doneCallbacks = ConcurrentHashMap<String, () -> Unit>()

    /** True while speech is playing; Team A should duck/skip its tones during this. */
    fun isSpeaking(): Boolean = speaking

    fun init() {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(progressListener)
                // Slightly slower than default for intelligibility of scene descriptions.
                tts?.setSpeechRate(0.95f)
                ttsReady = true
            }
        }
    }

    /** Adjust speech rate (1.0 = normal). Useful for a future verbosity / clarity setting. */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            speaking = true
        }

        override fun onDone(utteranceId: String?) = finish(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finish(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)

        private fun finish(utteranceId: String?) {
            speaking = false
            utteranceId?.let { id -> doneCallbacks.remove(id)?.invoke() }
        }
    }

    /**
     * Speak a phrase (guidance or scene description). Ducks radar tones for its duration.
     * [onDone] fires (on a TTS thread) when this utterance finishes or errors — use it to advance
     * UI state precisely instead of guessing at a duration.
     */
    fun speak(text: String, flush: Boolean = true, onDone: (() -> Unit)? = null) {
        if (!ttsReady) {
            onDone?.invoke()
            return
        }
        speaking = true
        val id = "echowalk-${System.nanoTime()}"
        if (onDone != null) doneCallbacks[id] = onDone
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, null, id)
    }

    /** Short haptic pulse for hazard alerts (Team A). */
    fun haptic(durationMs: Long = 60, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(durationMs)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        speaking = false
        doneCallbacks.clear()
    }
}
