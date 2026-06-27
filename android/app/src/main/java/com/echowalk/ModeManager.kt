package com.echowalk

import com.echowalk.shared.AppMode
import com.echowalk.shared.AudioOutputManager
import com.echowalk.shared.FrameProvider
import com.echowalk.teama.RadarState
import com.echowalk.teama.SafetyRadar
import com.echowalk.teamb.SceneDescriber
import com.echowalk.teamc.PlaceCue
import com.echowalk.teamc.PlaceNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The integration brain. Owns mode transitions and enforces the arbitration rules:
 *  - Safety radar is always on while NAVIGATING; place cues layer in at low rate.
 *  - Describing (VLM) is heavy: pause the radar loop, run it, speak, then resume.
 *  - All speech goes through [AudioOutputManager].
 *
 * Teams just implement [SafetyRadar] / [SceneDescriber] / [PlaceNavigator]; this wires them.
 */
class ModeManager(
    private val frames: FrameProvider,
    private val radar: SafetyRadar,
    private val describer: SceneDescriber,
    private val places: PlaceNavigator,
    private val audio: AudioOutputManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var mode: AppMode = AppMode.NAVIGATING
        private set

    fun start() {
        radar.observe(::onRadarState)
        places.observe(::onPlaceCue)
        enterNavigating()
    }

    private fun enterNavigating() {
        mode = AppMode.NAVIGATING
        radar.start()
    }

    private fun onRadarState(state: RadarState) {
        // Team A's SpatialAudioEngine consumes RadarState directly. ModeManager only needs to
        // suppress alerts while speaking (handled inside the audio engine via audio.isSpeaking()).
    }

    private fun onPlaceCue(cue: PlaceCue) {
        if (mode != AppMode.NAVIGATING) return
        audio.speak(cuePhrase(cue), flush = false)
    }

    private fun cuePhrase(cue: PlaceCue): String = buildString {
        append(
            when (cue.kind) {
                com.echowalk.teamc.CueKind.LOCATED -> "You're near ${cue.label}."
                com.echowalk.teamc.CueKind.APPROACHING_LANDMARK -> "Approaching ${cue.label}."
                com.echowalk.teamc.CueKind.TURN -> "Turn toward ${cue.label}."
                com.echowalk.teamc.CueKind.ARRIVED -> "You've arrived at ${cue.label}."
            }
        )
    }

    /** Hotkey / button entry point: pause radar, run the VLM on one frame, speak, resume. */
    fun describeScene() {
        if (mode == AppMode.DESCRIBING) return
        val frame = frames.latest() ?: return
        scope.launch {
            mode = AppMode.DESCRIBING
            radar.stop() // free the NPU for the heavy VLM pass
            try {
                val text = withContext(Dispatchers.Default) { describer.describe(frame) }
                audio.speak(text, flush = true)
            } finally {
                enterNavigating()
            }
        }
    }

    fun stop() {
        radar.stop()
    }
}
