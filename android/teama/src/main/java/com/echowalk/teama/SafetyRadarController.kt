package com.echowalk.teama

import android.util.Log
import com.echowalk.shared.EtModule
import com.echowalk.shared.Frame
import com.echowalk.shared.FrameProvider
import com.echowalk.shared.ImagePreprocessor
import com.echowalk.teama.audio.SpatialAudioEngine
import com.echowalk.teama.fusion.DepthYoloFusion
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Team A's main implementation. Subscribes to frames, runs depth + YOLO on the NPU, fuses them,
 * and emits [RadarState] for the audio engine.
 *
 * Threading model (3 threads):
 *  - [inferExec]    (1 thread, MAX_PRIORITY): owns all NPU calls sequentially — the Hexagon NPU
 *                   is a single hardware unit; two simultaneous submissions just cause contention.
 *  - [preprocessExec] (2 threads, NORM_PRIORITY): runs depth and YOLO image resizes in parallel
 *                   on CPU while the NPU is busy with the previous frame.
 *
 * Optimisations:
 *  1. Parallel CPU preprocessing — depth 518×518 and YOLO 640×640 resize run simultaneously.
 *  2. Pre-allocated ByteBuffers — [depthBuf] / [yoloBuf] are allocated once and reused every
 *     frame, eliminating ~10 MB of direct-memory allocation per cycle.
 *  3. Depth frame stride — depth NPU runs every [DEPTH_STRIDE] frames; the cached depth map is
 *     reused for the alternate frame. Depth changes far more slowly than object detections,
 *     so this halves depth NPU load while YOLO still runs every frame.
 *
 * Back-pressure: [inFlight] gate drops incoming frames while the pipeline is busy. CameraX's
 * STRATEGY_KEEP_ONLY_LATEST ensures we always pick up the freshest frame next.
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

    // Pre-allocated input buffers — reused every frame to avoid per-cycle allocateDirect().
    // Safe because inFlight guarantees only one pipeline cycle runs at a time.
    private val depthBuf = ByteBuffer.allocateDirect(DEPTH_HW * DEPTH_HW * 3 * 4)
        .order(java.nio.ByteOrder.nativeOrder())
    private val yoloBuf  = ByteBuffer.allocateDirect(YOLO_HW  * YOLO_HW  * 3 * 4)
        .order(java.nio.ByteOrder.nativeOrder())

    /** Counts processed frames; depth NPU runs every [DEPTH_STRIDE] frames to halve its load. */
    private var frameCount = 0

    private var inferExec = newInferExecutor()
    // 2-thread pool for parallel CPU preprocessing (depth resize + YOLO resize simultaneously).
    private var preprocessExec = newPreprocessExecutor()

    private fun newInferExecutor() = Executors.newSingleThreadExecutor { r ->
        Thread(r, "radar-infer").apply { priority = Thread.MAX_PRIORITY }
    }

    private fun newPreprocessExecutor() = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "radar-preprocess").apply { priority = Thread.NORM_PRIORITY }
    }

    override fun start() {
        if (running) return
        if (inferExec.isShutdown) inferExec = newInferExecutor()
        if (preprocessExec.isShutdown) preprocessExec = newPreprocessExecutor()
        running = true
        frames.subscribe(frameListener)
    }

    override fun stop() {
        if (!running) return
        running = false
        frames.unsubscribe(frameListener)
    }

    fun destroy() {
        stop()
        inferExec.shutdown()
        preprocessExec.shutdown()
    }

    override fun observe(listener: (RadarState) -> Unit) {
        this.listener = listener
    }

    private fun onFrame(frame: Frame) {
        if (!inFlight.compareAndSet(false, true)) return // drop while pipeline busy

        // Depth stride: run depth NPU every DEPTH_STRIDE frames to cut depth load in half.
        // The cached lastDepth is used for the alternate frames — depth changes slowly.
        val runDepth = (frameCount++ % DEPTH_STRIDE == 0) && depthModule != null

        // Submit both CPU preprocessing tasks in parallel — they resize on CPU while the
        // NPU is still finishing the previous cycle. Pre-allocated buffers avoid allocation.
        val depthInputFuture: Future<ByteBuffer?> = if (runDepth) {
            preprocessExec.submit<ByteBuffer?> {
                ImagePreprocessor.toFp32Nhwc(frame.rgb, DEPTH_HW, DEPTH_HW, depthBuf)
            }
        } else {
            java.util.concurrent.CompletableFuture.completedFuture(null)
        }
        val yoloInputFuture: Future<ByteBuffer?> = preprocessExec.submit<ByteBuffer?> {
            yoloModule?.let { ImagePreprocessor.toFp32Nhwc(frame.rgb, YOLO_HW, YOLO_HW, yoloBuf) }
        }

        inferExec.execute {
            try {
                val t0 = System.nanoTime()

                val depthInput: ByteBuffer? = depthInputFuture.get()
                val yoloInput: ByteBuffer?  = yoloInputFuture.get()

                // NPU inferences — sequential (Hexagon NPU is a single hardware unit).
                // For depth-skipped frames, fall back to the cached lastDepth.
                val depthRaw: FloatArray? = if (depthInput != null) runDepthNpu(depthInput)
                                            else lastDepth
                val detections: List<DepthYoloFusion.Detection> =
                    yoloInput?.let { runYoloNpu(it) } ?: emptyList()

                val state = if (depthRaw != null) {
                    fusion.fuse(depthRaw, lastDepthW, lastDepthH, detections, frame.tsMs)
                } else {
                    RadarState(
                        zoneNearestM = floatArrayOf(
                            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY),
                        hazards = detections.map { d ->
                            Hazard(d.cls,
                                distanceM  = d.score * 10f,
                                azimuthDeg = ((d.x0 + d.x1) * 0.5f - 0.5f) * DepthYoloFusion.HORIZONTAL_FOV_DEG,
                                kind       = HazardKind.OBSTACLE)
                        },
                        tsMs = frame.tsMs,
                    )
                }
                if (depthInput != null) lastDepth = depthRaw
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

    // --- NPU inference (must run on inferExec, never call from preprocessExec) --------

    private fun runDepthNpu(input: ByteBuffer): FloatArray? {
        val mod = depthModule ?: return null
        val out = mod.forward(input, intArrayOf(1, DEPTH_HW, DEPTH_HW, 3))
        if (out.isEmpty()) return null
        lastDepthW = DEPTH_HW
        lastDepthH = DEPTH_HW
        return out[0]
    }

    private fun runYoloNpu(input: ByteBuffer): List<DepthYoloFusion.Detection> {
        val mod = yoloModule ?: return emptyList()
        val out = mod.forward(input, intArrayOf(1, YOLO_HW, YOLO_HW, 3))
        if (out.size < 3) return emptyList()
        val boxes = out[0]; val scores = out[1]; val classes = out[2]
        val n = scores.size
        val dets = ArrayList<DepthYoloFusion.Detection>(8)
        for (i in 0 until n) {
            val s = scores[i]
            if (s < MIN_SCORE) continue
            val bi = i * 4
            val x0 = boxes[bi]     / YOLO_HW
            val y0 = boxes[bi + 1] / YOLO_HW
            val x1 = boxes[bi + 2] / YOLO_HW
            val y1 = boxes[bi + 3] / YOLO_HW
            if (x1 <= x0 || y1 <= y0) continue
            val clsIdx = classes[i].toInt()
            dets.add(DepthYoloFusion.Detection(labels.getOrNull(clsIdx) ?: "cls$clsIdx", x0, y0, x1, y1, s))
        }
        return dets
    }

    companion object {
        private const val TAG = "SafetyRadar"
        private const val DEPTH_HW = 518
        private const val YOLO_HW = 640
        private const val MIN_SCORE = 0.35f
        /** Run depth NPU every N frames; reuse cached depth map on the others. */
        private const val DEPTH_STRIDE = 2
    }
}
