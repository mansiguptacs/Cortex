package com.echowalk.shared

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
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

    /**
     * AudioAttributes for TTS output. Using USAGE_ASSISTANCE_ACCESSIBILITY routes through the
     * accessibility audio stream which is independent of media volume — the user can silence music
     * without silencing the navigation voice. This is set on the TTS engine via setAudioAttributes,
     * NOT via KEY_PARAM_STREAM bundle (which crashes the TTS engine with non-standard stream IDs).
     */
    private val ttsAudioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val vibrator: Vibrator? =
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    /** Short non-speech "earcons" so a blind user knows what's happening without looking. */
    private var tones: ToneGenerator? = null

    @Volatile
    private var speaking = false

    @Volatile
    private var lastGreetedMs = 0L

    /** Per-utterance completion callbacks, invoked when that utterance finishes (or errors). */
    private val doneCallbacks = ConcurrentHashMap<String, () -> Unit>()

    /** True while speech is playing; Team A should duck/skip its tones during this. */
    fun isSpeaking(): Boolean = speaking

    fun init() {
        tones = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        } catch (t: Throwable) {
            null // some devices throw if audio is busy; cues degrade to haptics only
        }
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(progressListener)
                tts?.setSpeechRate(1.1f)
                tts?.setAudioAttributes(ttsAudioAttrs)
                ttsReady = true
                greet()
            }
        }
    }

    /**
     * Speak the welcome greeting. Safe to call from [onResume] — rate-limited so it only fires
     * once per [GREET_COOLDOWN_MS] (5 min). Also handles the case where TTS wasn't ready yet on
     * the first [init] call (backgrounded app returning after the engine warmed up).
     */
    fun greet() {
        if (!ttsReady) return
        val now = System.currentTimeMillis()
        if (now - lastGreetedMs < GREET_COOLDOWN_MS) return
        lastGreetedMs = now
        val id = "warmup-${System.nanoTime()}"
        tts?.speak("Hello, welcome to Cortex app", TextToSpeech.QUEUE_FLUSH, null, id)
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

    // --- Earcons: tiny audio cues paired with light haptics, for eyes-free state feedback. ---

    /** Frame captured: crisp shutter-like blip + tick. */
    fun cueCapture() {
        tone(ToneGenerator.TONE_PROP_BEEP, 90)
        haptic(20, lightAmplitude())
    }

    /** Working on it: a soft, unobtrusive blip so silence doesn't feel like a freeze. */
    fun cueThinking() = tone(ToneGenerator.TONE_PROP_BEEP2, 60)

    /** Result ready: a pleasant confirmation just before speech starts. */
    fun cueDone() = tone(ToneGenerator.TONE_PROP_ACK, 120)

    /** Something went wrong / nothing to do: low error tone + a firmer buzz. */
    fun cueError() {
        tone(ToneGenerator.TONE_SUP_ERROR, 180)
        haptic(120)
    }

    private fun tone(type: Int, ms: Int) {
        try {
            tones?.startTone(type, ms)
        } catch (_: Throwable) {
        }
    }

    private fun lightAmplitude(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 80 else VibrationEffect.DEFAULT_AMPLITUDE

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
        tones?.release()
        tones = null
        speaking = false
        doneCallbacks.clear()
    }

    companion object {
        /** Minimum gap between greeting announcements — avoids repeating on every app switch. */
        private const val GREET_COOLDOWN_MS = 5 * 60 * 1000L  // 5 minutes
    }
}
