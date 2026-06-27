package com.echowalk.teama.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.echowalk.shared.AudioOutputManager
import com.echowalk.teama.HazardKind
import com.echowalk.teama.RadarState
import kotlin.math.PI
import kotlin.math.sin

/**
 * Turns a [RadarState] into spatial audio + haptics:
 *   - distance (relative depth) -> pitch / ping cadence (closer = faster, higher)
 *   - azimuth                   -> stereo pan (left/right ear)
 *   - kind                      -> timbre (soft hum for WALL, urgent ping for OBSTACLE,
 *                                  distinct DROPOFF buzz) + haptic on URGENT
 *
 * Ducks itself while speech plays via [AudioOutputManager.isSpeaking].
 *
 * Tone synthesis: pre-rendered short stereo ping buffers per (kind, urgency band), played via
 * [AudioTrack.MODE_STATIC] for low latency. We re-trigger them based on a per-zone cadence.
 */
class SpatialAudioEngine(
    private val audio: AudioOutputManager,
) {
    private val sampleRate = 44_100
    private val toneMs = 80
    private val toneSamples = (sampleRate * toneMs) / 1000

    // [kind][urgency] -> stereo AudioTrack
    private val tones: Array<Array<AudioTrack?>> = Array(3) { arrayOfNulls<AudioTrack>(3) }

    private val zoneNextDueMs = longArrayOf(0L, 0L, 0L)
    private var lastHapticMs = 0L

    init {
        val pingFreq = floatArrayOf(900f, 1300f, 1700f) // SOFT / MID / URGENT
        val humFreq = floatArrayOf(180f, 220f, 260f)
        val dropFreq = floatArrayOf(420f, 380f, 320f)
        for (k in 0..2) for (u in 0..2) {
            val freq = when (k) {
                0 -> humFreq[u]   // WALL
                1 -> pingFreq[u]  // OBSTACLE
                else -> dropFreq[u] // DROPOFF
            }
            val isHum = (k == 0)
            tones[k][u] = makeTone(freq, isHum)
        }
    }

    fun render(state: RadarState) {
        if (audio.isSpeaking()) return // duck under speech
        val now = System.currentTimeMillis()

        // Per-zone cadence from zoneNearest (relative depth; bigger = closer).
        val zones = state.zoneNearestM
        for (z in 0..2) {
            val rel = if (zones[z].isFinite()) zones[z] else Float.NEGATIVE_INFINITY
            val urgency = urgencyBand(rel) ?: continue
            if (now < zoneNextDueMs[z]) continue
            val pan = (z - 1).toFloat() // -1 left, 0 center, +1 right
            playTone(kindIndexForZone(state, z), urgency, pan)
            val cadenceMs = cadenceMsForUrgency(urgency)
            zoneNextDueMs[z] = now + cadenceMs
        }

        // Haptic on any imminent OBSTACLE / DROPOFF in the central zone.
        val imminent = state.hazards.any {
            (it.kind == HazardKind.OBSTACLE || it.kind == HazardKind.DROPOFF) &&
                urgencyBand(it.distanceM) == 2
        }
        if (imminent && now - lastHapticMs > 250) {
            audio.haptic(80)
            lastHapticMs = now
        }
    }

    private fun kindIndexForZone(state: RadarState, zone: Int): Int {
        // Prefer hazard kind if any hazard lies within this zone's azimuth band.
        val zoneAzimuth = (zone - 1) * 26f // each zone covers ~26 degrees of the 78° FOV
        var best = HazardKind.OBSTACLE
        var bestDist = Float.NEGATIVE_INFINITY
        for (h in state.hazards) {
            if (kotlin.math.abs(h.azimuthDeg - zoneAzimuth) < 18f && h.distanceM > bestDist) {
                bestDist = h.distanceM
                best = h.kind
            }
        }
        return when (best) { HazardKind.WALL -> 0; HazardKind.OBSTACLE -> 1; HazardKind.DROPOFF -> 2 }
    }

    private fun urgencyBand(rel: Float): Int? = when {
        rel >= 9.0f -> 2  // URGENT
        rel >= 6.0f -> 1  // MID
        rel >= 3.5f -> 0  // SOFT
        else -> null      // too far / no signal
    }

    private fun cadenceMsForUrgency(u: Int): Long = when (u) {
        2 -> 140L
        1 -> 320L
        else -> 700L
    }

    private fun playTone(kind: Int, urgency: Int, pan: Float) {
        val tone = tones[kind][urgency] ?: return
        val left = ((1f - pan) * 0.5f).coerceIn(0f, 1f)
        val right = ((1f + pan) * 0.5f).coerceIn(0f, 1f)
        try {
            tone.setStereoVolume(left, right)
            tone.stop()
            tone.reloadStaticData()
            tone.play()
        } catch (_: Throwable) { /* swallow — audio is best-effort */ }
    }

    private fun makeTone(freqHz: Float, hum: Boolean): AudioTrack {
        val pcm = ShortArray(toneSamples * 2)
        val attackSamples = toneSamples / 6
        val releaseSamples = toneSamples / 3
        for (i in 0 until toneSamples) {
            val t = i.toDouble() / sampleRate
            val env = when {
                i < attackSamples -> i.toFloat() / attackSamples
                i > toneSamples - releaseSamples -> (toneSamples - i).toFloat() / releaseSamples
                else -> 1f
            }
            val s = if (hum) {
                // soft sine with subtle vibrato — wall hum
                0.35 * sin(2 * PI * freqHz * t) * (0.85 + 0.15 * sin(2 * PI * 7 * t))
            } else {
                // sine + 2nd harmonic — clearer ping
                0.45 * sin(2 * PI * freqHz * t) + 0.15 * sin(2 * PI * 2 * freqHz * t)
            }
            val v = (s * env * 32767.0).toInt().coerceIn(-32768, 32767)
            pcm[2 * i] = v.toShort()
            pcm[2 * i + 1] = v.toShort()
        }
        val sizeBytes = pcm.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(sizeBytes)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    fun release() {
        for (k in 0..2) for (u in 0..2) {
            try { tones[k][u]?.release() } catch (_: Throwable) {}
            tones[k][u] = null
        }
    }
}
