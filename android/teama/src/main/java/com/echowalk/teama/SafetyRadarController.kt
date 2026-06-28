package com.echowalk.teama

import android.util.Log
import com.echowalk.shared.EtModule
import com.echowalk.shared.Frame
import com.echowalk.shared.FrameProvider
import com.echowalk.shared.ImagePreprocessor
import com.echowalk.teama.audio.SpatialAudioEngine
import com.echowalk.teama.fusion.DepthYoloFusion
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Team A's main implementation. Subscribes to frames, runs depth + YOLO on the NPU, fuses them,
 * and emits [RadarState] for the audio engine.
 *
 * Single-threaded inference executor + per-frame `inFlight` gate gives us natural back-pressure:
 * if a frame arrives while we're still running the previous depth pass, we drop it. CameraX's
 * [androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST] keeps the freshest one.
 */
class SafetyRadarController(
    private val frames: FrameProvider,
    private val depthModule: EtModule?,   // load Depth-Anything-V2 .tflite here
    private val yoloModule: EtModule?,    // load YOLOv10-Det .tflite here
    private val audio: SpatialAudioEngine,
    private val labels: List<String> = emptyList(),
    private val fusion: DepthYoloFusion = DepthYoloFusion(),
) : SafetyRadar {

    private var listener: ((RadarState) -> Unit)? = null
    private val frameListener: (Frame) -> Unit = ::onFrame
    private var running = false
    private val inFlight = AtomicBoolean(false)

    /** Latest depth map (row-major, size [DEPTH_HW * DEPTH_HW]) for debug overlays. */
    @Volatile var lastDepth: FloatArray? = null
        private set
    @Volatile var lastDepthW: Int = DEPTH_HW
        private set
    @Volatile var lastDepthH: Int = DEPTH_HW
        private set
    @Volatile var lastInferenceMs: Long = 0L
        private set
    @Volatile var lastDetections: List<DepthYoloFusion.Detection> = emptyList()
        private set

    private var inferExec = newInferExecutor()

    private fun newInferExecutor() = Executors.newSingleThreadExecutor { r ->
        Thread(r, "radar-infer").apply { priority = Thread.MAX_PRIORITY }
    }

    override fun start() {
        if (running) return
        // Recreate executor if it was shut down by a previous destroy() call.
        if (inferExec.isShutdown) inferExec = newInferExecutor()
        running = true
        frames.subscribe(frameListener)
    }

    override fun stop() {
        if (!running) return
        running = false
        frames.unsubscribe(frameListener)
        // Do NOT shutdown inferExec here — stop/start cycles are used during scene describe.
        // Call destroy() from onDestroy() for final cleanup.
    }

    /** Call once from Activity.onDestroy() to release the inference thread permanently. */
    fun destroy() {
        stop()
        inferExec.shutdown()
    }

    override fun observe(listener: (RadarState) -> Unit) {
        this.listener = listener
    }

    private fun onFrame(frame: Frame) {
        if (!inFlight.compareAndSet(false, true)) return // drop while NPU busy
        inferExec.execute {
            try {
                val t0 = System.nanoTime()
                val depthRaw: FloatArray? = depthModule?.let { runDepth(frame, it) }
                val detections: List<DepthYoloFusion.Detection> = yoloModule?.let { runYolo(frame, it) } ?: emptyList()
                val state = if (depthRaw != null) {
                    fusion.fuse(depthRaw, lastDepthW, lastDepthH, detections, frame.tsMs)
                } else {
                    // Depth missing — at least surface detections so the demo isn't dead silent.
                    RadarState(
                        zoneNearestM = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY),
                        hazards = detections.map { d ->
                            Hazard(d.cls, distanceM = (d.score * 10f), azimuthDeg = ((d.x0 + d.x1) * 0.5f - 0.5f) * DepthYoloFusion.HORIZONTAL_FOV_DEG, kind = HazardKind.OBSTACLE)
                        },
                        tsMs = frame.tsMs,
                    )
                }
                lastDepth = depthRaw
                lastDetections = detections
                lastInferenceMs = (System.nanoTime() - t0) / 1_000_000
                audio.render(state)
                listener?.invoke(state)
            } catch (t: Throwable) {
                Log.e(TAG, "inference loop failed", t)
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun runDepth(frame: Frame, mod: EtModule): FloatArray? {
        val input = ImagePreprocessor.toFp32Nhwc(frame.rgb, DEPTH_HW, DEPTH_HW)
        val out = mod.forward(input, intArrayOf(1, DEPTH_HW, DEPTH_HW, 3))
        if (out.isEmpty()) return null
        lastDepthW = DEPTH_HW
        lastDepthH = DEPTH_HW
        return out[0]
    }

    private fun runYolo(frame: Frame, mod: EtModule): List<DepthYoloFusion.Detection> {
        val input = ImagePreprocessor.toFp32Nhwc(frame.rgb, YOLO_HW, YOLO_HW)
        val out = mod.forward(input, intArrayOf(1, YOLO_HW, YOLO_HW, 3))
        if (out.size < 3) return emptyList()
        val boxes = out[0]      // [8400, 4]
        val scores = out[1]     // [8400]
        val classes = out[2]    // [8400] (widened uint8 -> float by TfliteQnnModule)
        val n = scores.size
        val dets = ArrayList<DepthYoloFusion.Detection>(8)
        for (i in 0 until n) {
            val s = scores[i]
            if (s < MIN_SCORE) continue
            val bi = i * 4
            // Boxes come in pixel coords of the 640×640 input. Normalize to [0,1].
            val x0 = boxes[bi] / YOLO_HW
            val y0 = boxes[bi + 1] / YOLO_HW
            val x1 = boxes[bi + 2] / YOLO_HW
            val y1 = boxes[bi + 3] / YOLO_HW
            if (x1 <= x0 || y1 <= y0) continue
            val clsIdx = classes[i].toInt()
            val name = labels.getOrNull(clsIdx) ?: "cls$clsIdx"
            dets.add(DepthYoloFusion.Detection(name, x0, y0, x1, y1, s))
        }
        return dets
    }

    companion object {
        private const val TAG = "SafetyRadar"
        private const val DEPTH_HW = 518
        private const val YOLO_HW = 640
        private const val MIN_SCORE = 0.35f
    }
}
