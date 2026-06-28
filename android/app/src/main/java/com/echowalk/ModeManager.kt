package com.echowalk

import android.graphics.Bitmap
import com.echowalk.shared.AppMode
import com.echowalk.shared.AudioOutputManager
import com.echowalk.shared.Frame
import com.echowalk.shared.FrameProvider
import com.echowalk.teama.RadarState
import com.echowalk.teama.SafetyRadar
import com.echowalk.teamb.SceneDescriber
import com.echowalk.teamb.narration.SceneNarration
import com.echowalk.teamb.narration.SceneStabilizer
import com.echowalk.teamb.vlm.AmbientScene
import com.echowalk.teamb.vlm.FrameQuality
import com.echowalk.teamb.vlm.SceneDescribers
import com.echowalk.teamb.vlm.SceneDiagnostics
import com.echowalk.teamc.CueKind
import com.echowalk.teamc.PlaceCue
import com.echowalk.teamc.PlaceNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The integration brain. Owns mode transitions and the full on-demand / ambient scene-description
 * flow, enforcing the arbitration rules:
 *  - Safety radar (Team A) is always on while NAVIGATING; place cues (Team C) layer in at low rate.
 *  - Describing is heavy: pause the radar loop, run the describer, speak, then resume.
 *  - Ambient (auto) mode classifies continuously but only speaks on a confident, stable *change*,
 *    and never talks over a manual describe or any active speech.
 *  - All speech and earcons go through [AudioOutputManager].
 *
 * Teams just implement [SafetyRadar] / [SceneDescriber] / [PlaceNavigator]; this wires them. The
 * proven Team B UX (temporal voting, earcons, dark/blur guards, confidence narration) lives here so
 * the real app shell behaves exactly like the Team B harness.
 *
 * UI is decoupled via [onStatus]: it may be invoked off the main thread, so the host must marshal.
 */
class ModeManager(
    private val frames: FrameProvider,
    private val radar: SafetyRadar,
    private val describer: SceneDescriber,
    private val places: PlaceNavigator,
    private val audio: AudioOutputManager,
    private val onStatus: (Status) -> Unit = {},
) {
    enum class Phase { READY, CAPTURING, THINKING, SPEAKING }

    /** A UI update. [message] null = leave the status line as-is (e.g. a HUD-only ambient tick). */
    data class Status(val phase: Phase, val message: String?, val hud: String?)

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    // Tuned for the Places365 classifier, whose top-1 confidence on real indoor scenes sits ~0.15-0.30
    // (the default gates are calibrated for sharper models and stay silent here). These thresholds
    // are local to ambient mode; SceneStabilizer's defaults are unchanged so its unit tests still hold.
    private val stabilizer = SceneStabilizer(
        minConf = 0.08f,
        announceConf = 0.14f,
        stableCycles = 2,
        minIntervalMs = 2_500L,
    )
    private var ambientJob: Job? = null
    private val engineLabel = SceneDescribers.engineLabel(describer)

    @Volatile
    var mode: AppMode = AppMode.NAVIGATING
        private set

    @Volatile
    private var lastDescription: String? = null

    /** True when the describer can rank scenes cheaply enough for ambient (auto) mode. */
    val ambientSupported: Boolean get() = describer is AmbientScene

    fun start() {
        radar.observe(::onRadarState)
        places.observe(::onPlaceCue)
        enterNavigating()
        // Warm the model so the first real describe isn't slow (no-op for Mock).
        scope.launch { runCatching { describer.warmUp() } }
        emit(Phase.READY, idleMessage(), null)
    }

    private fun enterNavigating() {
        mode = AppMode.NAVIGATING
        radar.start()
    }

    private fun onRadarState(@Suppress("UNUSED_PARAMETER") state: RadarState) {
        // Team A's SpatialAudioEngine consumes RadarState directly; arbitration (ducking tones while
        // speech plays) is handled inside the audio engine via audio.isSpeaking().
    }

    private fun onPlaceCue(cue: PlaceCue) {
        if (mode != AppMode.NAVIGATING) return
        audio.speak(cuePhrase(cue), flush = false)
    }

    private fun cuePhrase(cue: PlaceCue): String = when (cue.kind) {
        CueKind.LOCATED -> "You're near ${cue.label}."
        CueKind.APPROACHING_LANDMARK -> "Approaching ${cue.label}."
        CueKind.TURN -> "Turn toward ${cue.label}."
        CueKind.ARRIVED -> "You've arrived at ${cue.label}."
    }

    // --- On-demand describe (hotkey / button / tap) ---------------------------------------------

    /** Hotkey/button entry point: pause radar, run the describer on a short burst, speak, resume. */
    fun describeScene() {
        if (mode == AppMode.DESCRIBING) return
        val first = frames.latest()
        if (first == null) {
            audio.cueError()
            emit(Phase.READY, "No frame yet - give the camera a moment.", null)
            return
        }
        scope.launch {
            mode = AppMode.DESCRIBING
            radar.stop() // free the NPU for the heavy pass
            try {
                audio.cueCapture()
                emit(Phase.CAPTURING, "Capturing frame...", null)

                // A blind user can't see a covered lens / dark room — say so, don't describe black.
                if (isFrameTooDark(first.rgb)) {
                    audio.cueError()
                    speakAwait(
                        "It's too dark to see clearly. Point the camera at the room or uncover the lens.",
                        Phase.SPEAKING,
                    )
                    return@launch
                }

                emit(Phase.THINKING, "Thinking...", null)
                audio.cueThinking()
                // Temporal voting: sample a short burst so one odd frame can't decide the answer.
                val burst = captureBurst().ifEmpty { listOf(first) }
                val startNs = System.nanoTime()
                val text = withContext(Dispatchers.Default) { describer.describe(burst) }
                val latencyMs = (System.nanoTime() - startNs) / 1_000_000

                lastDescription = text
                // Tell ambient mode what we just said so it doesn't immediately echo the same scene.
                (describer as? SceneDiagnostics)?.lastTopK()?.firstOrNull()
                    ?.let { stabilizer.noteAnnounced(it.label, System.currentTimeMillis()) }
                audio.cueDone()
                speakAwait(text, Phase.SPEAKING, hud(latencyMs))
            } finally {
                enterNavigating()
                emit(Phase.READY, idleMessage(), null)
            }
        }
    }

    /** Replay the last description (long-press / double-tap) without re-running inference. */
    fun repeatLast() {
        if (mode == AppMode.DESCRIBING) return
        val last = lastDescription
        if (last == null) {
            audio.cueError()
            emit(Phase.READY, "Nothing described yet - tap the preview first.", null)
            return
        }
        scope.launch {
            mode = AppMode.DESCRIBING
            try {
                speakAwait(last, Phase.SPEAKING)
            } finally {
                enterNavigating()
                emit(Phase.READY, idleMessage(), null)
            }
        }
    }

    // --- Ambient (auto) mode: classify continuously, speak only on a confident, stable change. ----

    fun startAmbient() {
        val ambient = describer as? AmbientScene ?: return
        stabilizer.reset()
        ambientJob?.cancel()
        ambientJob = scope.launch {
            while (isActive) {
                // Defer entirely to a manual describe / any active speech — never talk over it.
                if (mode == AppMode.NAVIGATING && !audio.isSpeaking()) {
                    val frame = frames.latest()
                    // Skip dark or motion-blurred frames; classifying them just produces noise.
                    if (frame != null && !isFrameTooDark(frame.rgb) && !isFrameBlurry(frame.rgb)) {
                        val startNs = System.nanoTime()
                        val ranked = ambient.rankScenes(frame)
                        emit(Phase.READY, null, hud((System.nanoTime() - startNs) / 1_000_000))
                        val top = ranked.firstOrNull()
                        val decision = stabilizer.observe(
                            top?.label, top?.prob ?: 0f, System.currentTimeMillis(),
                        )
                        if (decision is SceneStabilizer.Decision.Announce) announceAmbient(decision.term)
                    }
                }
                delay(AMBIENT_PERIOD_MS)
            }
        }
    }

    fun stopAmbient() {
        ambientJob?.cancel()
        ambientJob = null
        stabilizer.reset()
    }

    /** Speak a brief, change-only ambient cue without seizing the full describe state machine. */
    private fun announceAmbient(term: String) {
        val text = SceneNarration.brief(term)
        if (text.isEmpty()) return
        lastDescription = text
        audio.haptic(15) // subtle nudge that something was said
        audio.speak(text) // isSpeaking() keeps the loop from re-entering until this finishes
        emit(Phase.READY, "$text  (auto)", null)
    }

    fun stop() {
        stopAmbient()
        radar.stop()
        job.cancel()
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Sample a few distinct recent frames over a short window for temporal voting. */
    private suspend fun captureBurst(count: Int = BURST_COUNT, gapMs: Long = BURST_GAP_MS): List<Frame> {
        val out = ArrayList<Frame>(count)
        var lastTs = -1L
        repeat(count) { i ->
            frames.latest()?.let { f -> if (f.tsMs != lastTs) { out.add(f); lastTs = f.tsMs } }
            if (i < count - 1) delay(gapMs)
        }
        return out
    }

    /** Emit SPEAKING, speak, and suspend until the utterance actually finishes (or errors). */
    private suspend fun speakAwait(text: String, phase: Phase, hud: String? = null) {
        emit(phase, text, hud)
        suspendCancellableCoroutine<Unit> { cont ->
            audio.speak(text) { if (cont.isActive) cont.resume(Unit) }
        }
    }

    private fun emit(phase: Phase, message: String?, hud: String?) = onStatus(Status(phase, message, hud))

    private fun idleMessage(): String = lastDescription ?: "Tap the preview to describe  [$engineLabel]"

    /** Demo HUD: engine + last inference latency + top predictions (when the engine exposes them). */
    private fun hud(latencyMs: Long): String {
        val sb = StringBuilder("engine $engineLabel   $latencyMs ms")
        (describer as? SceneDiagnostics)?.lastTopK()?.takeIf { it.isNotEmpty() }?.forEach { ls ->
            sb.append("\n").append(ls.label.padEnd(16).take(16)).append(" ").append("${(ls.prob * 100).toInt()}%")
        }
        return sb.toString()
    }

    private fun isFrameTooDark(bitmap: Bitmap): Boolean {
        val s = 32
        val small = Bitmap.createScaledBitmap(bitmap, s, s, true)
        val px = IntArray(s * s)
        small.getPixels(px, 0, s, 0, 0, s, s)
        if (small !== bitmap) small.recycle()
        return FrameQuality.isTooDark(px)
    }

    private fun isFrameBlurry(bitmap: Bitmap): Boolean {
        val s = 64
        val small = Bitmap.createScaledBitmap(bitmap, s, s, true)
        val px = IntArray(s * s)
        small.getPixels(px, 0, s, 0, 0, s, s)
        if (small !== bitmap) small.recycle()
        return FrameQuality.isBlurry(px, s, s)
    }

    private companion object {
        const val BURST_COUNT = 4        // frames sampled per describe for temporal voting
        const val BURST_GAP_MS = 70L     // spacing between samples (~210ms total window)
        const val AMBIENT_PERIOD_MS = 700L // ambient classify cadence (~1.4 Hz)
    }
}
