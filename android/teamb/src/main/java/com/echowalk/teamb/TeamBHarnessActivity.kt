package com.echowalk.teamb

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.echowalk.shared.AudioOutputManager
import com.echowalk.shared.Frame
import com.echowalk.shared.camera.CameraXFrameProvider
import com.echowalk.teamb.vlm.FrameQuality
import com.echowalk.teamb.vlm.SceneDescribers
import com.echowalk.teamb.vlm.SceneDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Team B isolation harness.
 *
 * - Describe flow is a coroutine state machine (READY -> CAPTURING -> THINKING -> SPEAKING ->
 *   READY); inference runs off the UI thread and SPEAKING ends precisely when TTS finishes.
 * - Describer is chosen by [SceneDescribers.create]: VLM (`vlm.pte`) -> classifier
 *   (`classifier.pte`+`labels.txt`) -> Mock. The active engine shows as [VLM]/[TAGS]/[MOCK].
 * - Either volume key is a hands-free hotkey for Describe (mirrors ModeManager in the full app);
 *   long-press the button to replay the last description.
 * - Dark/covered-lens frames are detected and announced instead of describing a black image.
 *
 * Drop a real `vlm.pte` into app/src/main/assets/ to flip from [MOCK] to [VLM] with no code change.
 */
class TeamBHarnessActivity : AppCompatActivity() {

    private enum class UiState(val label: String) {
        READY("Ready - tap Describe"),
        CAPTURING("Capturing frame..."),
        THINKING("Thinking..."),
        SPEAKING("Speaking..."),
    }

    private lateinit var frames: CameraXFrameProvider
    private lateinit var audio: AudioOutputManager
    private lateinit var describer: SceneDescriber
    private var state = UiState.READY
    private var lastDescription: String? = null

    private lateinit var previewView: PreviewView
    private lateinit var previewFrame: View
    private lateinit var capturedThumb: ImageView
    private lateinit var statusText: TextView
    private lateinit var describeButton: Button
    private lateinit var hudText: TextView
    private lateinit var gestures: GestureDetector

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else statusText.text = "Camera permission denied"
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teamb_harness)

        previewView = findViewById(R.id.previewView)
        previewFrame = findViewById(R.id.previewFrame)
        capturedThumb = findViewById(R.id.capturedThumb)
        statusText = findViewById(R.id.statusText)
        describeButton = findViewById(R.id.describeButton)
        hudText = findViewById(R.id.hudText)
        describeButton.setOnClickListener { onDescribe() }
        describeButton.setOnLongClickListener { onRepeat(); true } // long-press = repeat last

        // Eyes-free: tap anywhere on the preview to describe, double-tap to repeat.
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onDescribe(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { onRepeat(); return true }
        })
        previewFrame.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev); true }

        frames = CameraXFrameProvider(this)
        audio = AudioOutputManager(this).also { it.init() }
        // VLM if `vlm.pte` is bundled, else classifier if `classifier.pte`+`labels.txt`, else Mock.
        describer = SceneDescribers.create(this)
        engineLabel = SceneDescribers.engineLabel(describer)
        // Warm the model so the first real describe isn't slow (no-op for Mock).
        lifecycleScope.launch { describer.warmUp() }

        if (hasCameraPermission()) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private var engineLabel: String = "MOCK"

    private fun startCamera() {
        frames.bind(this, previewView)
        setState(UiState.READY)
    }

    private fun onDescribe() {
        // Ignore taps unless idle — the state machine owns the button, but guard anyway.
        if (state != UiState.READY) return

        val first = frames.latest()
        if (first == null) {
            audio.cueError()
            statusText.text = "No frame yet - give the camera a moment"
            return
        }
        setState(UiState.CAPTURING)
        audio.cueCapture()
        capturedThumb.setImageBitmap(first.rgb)

        lifecycleScope.launch {
            // A blind user can't see a covered lens / dark room — say so instead of describing black.
            if (isFrameTooDark(first.rgb)) {
                audio.cueError()
                val hint = "It's too dark to see clearly. Point the camera at the room or uncover the lens."
                setState(UiState.SPEAKING, detail = hint)
                speakAndAwait(hint)
                setState(UiState.READY)
                return@launch
            }

            setState(UiState.THINKING)
            audio.cueThinking()
            // Temporal voting: sample a short burst so one odd frame can't decide the answer.
            val burst = captureBurst().ifEmpty { listOf(first) }
            // Keep inference off the UI thread; the real VLM/classifier pass is CPU/NPU heavy.
            val startNs = System.nanoTime()
            val text = withContext(Dispatchers.Default) { describer.describe(burst) }
            val latencyMs = (System.nanoTime() - startNs) / 1_000_000

            lastDescription = text
            renderHud(latencyMs)
            audio.cueDone()
            setState(UiState.SPEAKING, detail = text)
            speakAndAwait(text)

            setState(UiState.READY)
        }
    }

    /** Sample a few distinct recent frames over a short window for temporal voting. */
    private suspend fun captureBurst(count: Int = BURST_COUNT, gapMs: Long = BURST_GAP_MS): List<Frame> {
        val out = ArrayList<Frame>(count)
        var lastTs = -1L
        repeat(count) { i ->
            frames.latest()?.let { f ->
                if (f.tsMs != lastTs) { out.add(f); lastTs = f.tsMs }
            }
            if (i < count - 1) delay(gapMs)
        }
        return out
    }

    /** Long-press / double-tap: replay the last description without re-running inference. */
    private fun onRepeat() {
        if (state != UiState.READY) return
        val last = lastDescription
        if (last == null) {
            audio.cueError()
            statusText.text = "Nothing described yet - tap the preview first."
            return
        }
        lifecycleScope.launch {
            setState(UiState.SPEAKING, detail = last)
            speakAndAwait(last)
            setState(UiState.READY)
        }
    }

    /** Downscale + mean-luma check so we don't pay for a full-res scan. */
    private fun isFrameTooDark(bitmap: Bitmap): Boolean {
        val s = 32
        val small = Bitmap.createScaledBitmap(bitmap, s, s, true)
        val px = IntArray(s * s)
        small.getPixels(px, 0, s, 0, 0, s, s)
        if (small !== bitmap) small.recycle()
        return FrameQuality.isTooDark(px)
    }

    /** Demo HUD: engine + last inference latency + top predictions (when the engine exposes them). */
    private fun renderHud(latencyMs: Long) {
        val sb = StringBuilder()
        sb.append("engine $engineLabel   ${latencyMs} ms")
        (describer as? SceneDiagnostics)?.lastTopK()?.takeIf { it.isNotEmpty() }?.forEach { ls ->
            val pct = (ls.prob * 100).toInt()
            sb.append("\n").append(ls.label.padEnd(16).take(16)).append(" ").append("$pct%")
        }
        hudText.text = sb.toString()
        hudText.visibility = android.view.View.VISIBLE
    }

    /** Single place that mutates UI for a state change. Always called on the main thread. */
    private fun setState(next: UiState, detail: String? = null) {
        state = next
        describeButton.isEnabled = next == UiState.READY
        statusText.text = when {
            detail != null -> detail
            // Keep the last description on screen as a caption when idle, not just "Ready".
            next == UiState.READY -> lastDescription ?: "Tap the preview or press volume  [$engineLabel]"
            else -> next.label
        }
    }

    /** Suspend until the spoken utterance actually finishes (or errors). */
    private suspend fun speakAndAwait(text: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            audio.speak(text) { if (cont.isActive) cont.resume(Unit) }
        }

    /**
     * Hands-free hotkey: either volume key triggers a describe so a blind user never has to find
     * the on-screen button. We consume the event so the system volume UI doesn't pop up.
     * In the full app this same gesture routes through ModeManager.describeScene() (U-Step 6).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolumeKey(keyCode)) {
            if (event?.repeatCount == 0) onDescribe() // fire once per press, not on auto-repeat
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Swallow the key-up too so the system volume slider never appears.
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        if (isVolumeKey(keyCode)) true else super.onKeyUp(keyCode, event)

    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        if (::frames.isInitialized) frames.shutdown()
        if (::audio.isInitialized) audio.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val BURST_COUNT = 4      // frames sampled per describe for temporal voting
        const val BURST_GAP_MS = 70L   // spacing between samples (~210ms total window)
    }
}
