package com.echowalk.teama

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.echowalk.shared.AudioOutputManager
import com.echowalk.shared.TfliteQnnModule
import com.echowalk.shared.camera.CameraXFrameProvider
import com.echowalk.teama.audio.SpatialAudioEngine

/**
 * Isolated test bench for Team A. Live camera preview + depth heatmap overlay + FPS / latency
 * + audio feedback. No dependency on Teams B/C.
 *
 * Launch:
 *   adb shell am start -n com.echowalk/com.echowalk.teama.TeamAHarnessActivity
 */
class TeamAHarnessActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var heatmap: ImageView
    private lateinit var stats: TextView

    private lateinit var frames: CameraXFrameProvider
    private lateinit var radar: SafetyRadarController
    private lateinit var spatial: SpatialAudioEngine
    private lateinit var audioOut: AudioOutputManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameCount = 0
    private var fpsWindowStartMs = 0L
    private var fps = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        preview = PreviewView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH) }
        heatmap = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            alpha = 0.45f
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        stats = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.TOP or Gravity.START }
            setTextColor(Color.WHITE)
            setBackgroundColor(0x80000000.toInt())
            setPadding(24, 24, 24, 24)
            textSize = 14f
        }
        root.addView(preview)
        root.addView(heatmap)
        root.addView(stats)
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
        } else {
            initStack()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            initStack()
        } else {
            stats.text = "Camera permission required."
        }
    }

    private fun initStack() {
        audioOut = AudioOutputManager(this).also { it.init() }
        spatial = SpatialAudioEngine(audioOut)

        val depthMod = try { TfliteQnnModule.loadAsset(this, "depth_anything_v2.tflite") } catch (t: Throwable) {
            Log.e(TAG, "depth load failed", t); null
        }
        val yoloMod = try { TfliteQnnModule.loadAsset(this, "yolov10_det.tflite") } catch (t: Throwable) {
            Log.e(TAG, "yolo load failed", t); null
        }
        val labels = try {
            assets.open("coco.names").bufferedReader().readLines().filter { it.isNotBlank() }
        } catch (_: Throwable) { emptyList() }

        frames = CameraXFrameProvider(this).also { it.bind(this, preview) }
        radar = SafetyRadarController(frames, depthMod, yoloMod, spatial, labels)
        radar.observe(::onState)
        radar.start()
    }

    private fun onState(state: RadarState) {
        frameCount++
        val now = System.currentTimeMillis()
        if (fpsWindowStartMs == 0L) fpsWindowStartMs = now
        val elapsed = now - fpsWindowStartMs
        if (elapsed >= 1000) {
            fps = frameCount * 1000f / elapsed
            frameCount = 0
            fpsWindowStartMs = now
        }
        val depth = radar.lastDepth
        val depthW = radar.lastDepthW
        val depthH = radar.lastDepthH
        val bitmap = depth?.let { heatmapBitmap(it, depthW, depthH) }
        mainHandler.post {
            if (bitmap != null) heatmap.setImageBitmap(bitmap)
            val hazardLine = state.hazards.joinToString(", ") {
                "${it.cls}@${"%.1f".format(it.distanceM)}"
            }.ifEmpty { "—" }
            stats.text = "fps ${"%.1f".format(fps)}   infer ${radar.lastInferenceMs} ms\n" +
                "zones L/C/R ${formatZones(state.zoneNearestM)}\n" +
                "hazards: $hazardLine"
        }
    }

    private fun formatZones(z: FloatArray): String =
        z.joinToString("/") { if (it.isFinite()) "%.1f".format(it) else "·" }

    private fun heatmapBitmap(depth: FloatArray, w: Int, h: Int): Bitmap {
        var mn = Float.POSITIVE_INFINITY
        var mx = Float.NEGATIVE_INFINITY
        for (v in depth) { if (v < mn) mn = v; if (v > mx) mx = v }
        val range = if (mx - mn < 1e-6f) 1f else (mx - mn)
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            val t = ((depth[i] - mn) / range).coerceIn(0f, 1f)
            val r = (255 * t).toInt()
            val g = (255 * (1f - kotlin.math.abs(t - 0.5f) * 2f).coerceAtLeast(0f)).toInt()
            val b = (255 * (1f - t)).toInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun onDestroy() {
        try { radar.stop() } catch (_: Throwable) {}
        try { spatial.release() } catch (_: Throwable) {}
        try { audioOut.shutdown() } catch (_: Throwable) {}
        try { frames.shutdown() } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TeamAHarness"
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
