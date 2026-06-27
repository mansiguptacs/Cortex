package com.echowalk.teama

import com.echowalk.shared.EtModule
import com.echowalk.shared.Frame
import com.echowalk.shared.FrameProvider
import com.echowalk.teama.audio.SpatialAudioEngine
import com.echowalk.teama.fusion.DepthYoloFusion

/**
 * Team A's main implementation. Subscribes to frames, runs depth + YOLO on the NPU, fuses them,
 * and emits [RadarState] for the audio engine.
 *
 * STUB: currently emits an empty state so the app builds and runs end-to-end. Replace the body of
 * [onFrame] with real inference + fusion (milestones M1-M5). See docs/team-a.md.
 */
class SafetyRadarController(
    private val frames: FrameProvider,
    private val depthModule: EtModule?,   // load Depth-Anything-V2-Small .pte here
    private val yoloModule: EtModule?,    // load YOLO nano .pte here
    private val audio: SpatialAudioEngine,
    private val fusion: DepthYoloFusion = DepthYoloFusion(),
) : SafetyRadar {

    private var listener: ((RadarState) -> Unit)? = null
    private val frameListener: (Frame) -> Unit = ::onFrame
    private var running = false

    override fun start() {
        if (running) return
        running = true
        frames.subscribe(frameListener)
    }

    override fun stop() {
        if (!running) return
        running = false
        frames.unsubscribe(frameListener)
    }

    override fun observe(listener: (RadarState) -> Unit) {
        this.listener = listener
    }

    private fun onFrame(frame: Frame) {
        // TODO(Team A):
        //   1. preprocess `frame.rgb` -> input tensor
        //   2. val depth = depthModule.forward(...)   // relative depth map
        //   3. val boxes = yoloModule.forward(...)     // detections
        //   4. val state = fusion.fuse(depth, boxes, frame.width, frame.height)
        //   5. audio.render(state); listener?.invoke(state)
        val state = RadarState(
            zoneNearestM = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE),
            hazards = emptyList(),
            tsMs = frame.tsMs,
        )
        audio.render(state)
        listener?.invoke(state)
    }
}
