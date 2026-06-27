package com.echowalk.teama.audio

import com.echowalk.shared.AudioOutputManager
import com.echowalk.teama.RadarState

/**
 * Turns a [RadarState] into sound + haptics:
 *   - distance  -> pitch / ping cadence (closer = faster, higher)
 *   - azimuth   -> stereo pan (left/right ear)
 *   - kind      -> timbre (soft hum for WALL, urgent ping for OBSTACLE, distinct DROPOFF pattern)
 *
 * Ducks itself while speech plays via [AudioOutputManager.isSpeaking].
 *
 * STUB: wire up AudioTrack/SoundPool tone generation (milestones M2, M3, M5). See docs/team-a.md.
 */
class SpatialAudioEngine(
    private val audio: AudioOutputManager,
) {
    fun render(state: RadarState) {
        if (audio.isSpeaking()) return // don't talk over speech
        // TODO(Team A): generate panned tones by zone + haptic pulses for imminent hazards (<2 m).
    }

    fun release() {
        // TODO(Team A): release AudioTrack/SoundPool resources.
    }
}
