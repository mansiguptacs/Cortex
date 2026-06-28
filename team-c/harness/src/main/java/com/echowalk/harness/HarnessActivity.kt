package com.echowalk.harness

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import com.echowalk.places.CosineMatcher
import com.echowalk.places.CueKind
import com.echowalk.places.DownsampleEmbedder
import com.echowalk.places.FamiliarPlacesNavigator
import com.echowalk.places.FilePlaceStore
import com.echowalk.places.PlaceCue
import com.echowalk.places.RateGate
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import android.speech.tts.TextToSpeech

/**
 * Standalone on-device test bench for Team C — NO model file, NO ExecuTorch, NO Teams A/B.
 *
 * Pipeline exercised here is exactly the code we built and unit-tested in `:core`:
 *   CameraX YUV frame (Y-plane == grayscale)
 *     -> [DownsampleEmbedder] (CPU fallback embedder, plan §2.4)
 *     -> [FamiliarPlacesNavigator] (enroll / localize-with-hysteresis / guide)
 *     -> [FilePlaceStore] (survives app restart, proves M-C1)
 *     -> [PlaceCue] spoken via TextToSpeech.
 *
 * The encoder runs at ~2 Hz via [RateGate] so it mimics the integrated app's low duty cycle.
 * All navigator access is funnelled through a single worker thread so button taps and the camera
 * analyzer never mutate its state concurrently.
 */
class HarnessActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val placeId = "demo"

    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var labelField: EditText

    private val embedder = DownsampleEmbedder(grid = 16)
    private val gate = RateGate.hz(2.0)
    // rank() ignores its threshold; used only to surface the live best-match score for tuning.
    private val diagMatcher = CosineMatcher(threshold = 0f)
    private lateinit var store: FilePlaceStore
    private lateinit var navigator: FamiliarPlacesNavigator

    @Volatile private var threshold = DEFAULT_THRESHOLD
    @Volatile private var phase = "idle"

    private val work = Executors.newSingleThreadExecutor()
    private val analysisExec = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Live diagnostics surfaced to the log so we can judge separability on real scenes.
    @Volatile private var frameCount = 0

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else log("CAMERA permission denied — cannot capture frames")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = FilePlaceStore(File(filesDir, "places.bin"))
        navigator = buildNavigator()

        setContentView(buildUi())
        tts = TextToSpeech(this, this)

        log("Harness ready. DB has ${store.places().size} place(s).")
        log("Flow: Start Enroll -> (aim, Add Landmark x3) -> Stop -> Activate -> Navigate To.")

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildNavigator(): FamiliarPlacesNavigator =
        FamiliarPlacesNavigator(
            store = store,
            matcher = CosineMatcher(threshold = threshold), // tunable live via Thr -/Thr +
            framesPerLandmark = 5,
            confirmTicks = 3,
        ).also { nav ->
            nav.observe { cue -> runOnUiThread { onCue(cue) } }
        }

    // ---- UI (programmatic to keep the module dependency-free of XML) --------

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        statusView = TextView(this).apply {
            textSize = 13f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            text = "phase=idle  thr=${"%.2f".format(threshold)}  best=—  frames=0  landmarks=0"
        }
        root.addView(statusView)

        labelField = EditText(this).apply {
            hint = "landmark / destination label (e.g. desk)"
        }
        root.addView(labelField)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("Start Enroll") { onEnrollStart() })
        row1.addView(button("Add Landmark") { onAddLandmark() })
        row1.addView(button("Stop Enroll") { onEnrollStop() })
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("Activate") { onActivate() })
        row2.addView(button("Navigate To") { onNavigate() })
        row2.addView(button("Stop Nav") { onStopNav() })
        root.addView(row2)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(button("List Dest") { onList() })
        row3.addView(button("Clear DB") { onClear() })
        row3.addView(button("Thr -") { onThreshold(-THRESHOLD_STEP) })
        row3.addView(button("Thr +") { onThreshold(+THRESHOLD_STEP) })
        root.addView(row3)

        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            gravity = Gravity.BOTTOM
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(scroll)
        return root
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 11f
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    // ---- Button actions (all serialized on the worker thread) ---------------

    private fun onEnrollStart() = work.execute {
        navigator.enrollStart(placeId, "Demo")
        phase = "enrolling"
        log("ENROLL started for '$placeId'. Aim at a landmark, hold ~2s, then Add Landmark.")
    }

    private fun onAddLandmark() {
        val label = labelField.text.toString().trim().ifEmpty { "landmark${System.currentTimeMillis() % 1000}" }
        work.execute {
            try {
                navigator.addLandmark(label)
                log("ADDED landmark '$label'.")
            } catch (e: Exception) {
                log("Add failed: ${e.message}")
            }
        }
    }

    private fun onEnrollStop() = work.execute {
        navigator.enrollStop()
        phase = "idle"
        log("ENROLL stopped. Tap Activate to localize.")
    }

    private fun onActivate() = work.execute {
        try {
            navigator.activatePlace(placeId)
            phase = "active"
            log("ACTIVE. Destinations: ${navigator.listDestinations()}")
        } catch (e: Exception) {
            log("Activate failed: ${e.message}")
        }
    }

    private fun onNavigate() {
        val label = labelField.text.toString().trim()
        if (label.isEmpty()) { toast("Type a destination label first"); return }
        work.execute {
            try {
                navigator.navigateTo(label)
                log("NAVIGATE to '$label' started.")
            } catch (e: Exception) {
                log("Navigate failed: ${e.message}")
            }
        }
    }

    private fun onStopNav() = work.execute {
        navigator.stopNavigation()
        log("Navigation stopped.")
    }

    private fun onList() = work.execute {
        log("Destinations: ${store.destinations(placeId)}")
    }

    private fun onClear() = work.execute {
        store.clear()
        navigator = buildNavigator()
        phase = "idle"
        frameCount = 0
        log("DB cleared. Fresh navigator.")
        refreshStatus(null)
    }

    /**
     * Rebuild the navigator with a new [threshold] (the matcher is immutable). Tuning is a
     * localization concern, so we re-activate afterwards if we were active; an in-progress
     * enrollment is reset (threshold doesn't affect enrollment anyway).
     */
    private fun onThreshold(delta: Float) = work.execute {
        threshold = (threshold + delta).coerceIn(0f, 1f)
        val wasActive = phase == "active"
        navigator = buildNavigator()
        if (wasActive) {
            try {
                navigator.activatePlace(placeId)
                phase = "active"
            } catch (_: Exception) {
                phase = "idle"
            }
        } else {
            phase = "idle"
        }
        log("threshold -> ${"%.2f".format(threshold)} (navigator rebuilt)")
        refreshStatus(null)
    }

    // ---- Camera -> embedding -> navigator -----------------------------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExec, ::analyze) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                log("Camera bound. Embedding at ~2 Hz (dim=${embedder.dim}).")
            } catch (e: Exception) {
                log("Camera bind failed: ${e.message}")
            }
        }, mainExecutor)
    }

    private fun analyze(image: ImageProxy) {
        try {
            val now = image.imageInfo.timestamp / 1_000_000 // ns -> ms
            if (!gate.allow(now)) return
            val gray = grayscaleFromYPlane(image)
            val emb = embedder.embed(gray, image.width, image.height)
            frameCount++
            work.execute {
                navigator.onEmbedding(emb)
                refreshStatus(emb)
            }
        } catch (e: Exception) {
            Log.e("Harness", "analyze error", e)
        } finally {
            image.close()
        }
    }

    /** The Y plane of YUV_420_888 IS luma == grayscale; honor rowStride. */
    private fun grayscaleFromYPlane(image: ImageProxy): FloatArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            var idx = y * rowStride
            val rowOut = y * w
            for (x in 0 until w) {
                out[rowOut + x] = (buffer.get(idx).toInt() and 0xFF) / 255f
                idx += pixelStride
            }
        }
        return out
    }

    // ---- Cue handling -------------------------------------------------------

    private fun onCue(cue: PlaceCue) {
        val text = speechFor(cue)
        log("CUE ${cue.kind} '${cue.label}' (conf=${"%.2f".format(cue.confidence)})")
        speak(text)
    }

    private fun speechFor(cue: PlaceCue): String = when (cue.kind) {
        CueKind.LOCATED -> "You are at ${cue.label}"
        CueKind.APPROACHING_LANDMARK -> "Approaching ${cue.label}"
        CueKind.TURN -> "Turn ${cue.distanceHint ?: "ahead"} toward ${cue.label}"
        CueKind.ARRIVED -> "Arrived at ${cue.label}"
    }

    // ---- TTS ----------------------------------------------------------------

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            log("TTS ready.")
        } else {
            log("TTS init failed (cues will still be logged).")
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "cue-${System.nanoTime()}")
    }

    // ---- helpers ------------------------------------------------------------

    private fun log(msg: String) = runOnUiThread {
        val line = "${timeFmt.format(Date())}  $msg\n"
        logView.append(line)
        Log.i("Harness", msg)
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * Live cosine-score HUD. Ranks the current embedding against all stored landmarks and shows
     * the top match EVEN WHEN it is below threshold (which the cue log never reveals, since those
     * ticks stay silent). This is the empirical signal for picking the threshold (plan M-C0).
     * Runs on the [work] thread so all store access stays serialized.
     */
    private fun refreshStatus(emb: FloatArray?) {
        val landmarks = store.landmarks(placeId)
        val top = if (emb != null && landmarks.isNotEmpty()) {
            diagMatcher.rank(emb, landmarks).firstOrNull()
        } else {
            null
        }
        val bestStr = top?.let { "${it.label} ${"%.2f".format(it.score)}" } ?: "—"
        val gateStr = when {
            top == null -> ""
            top.score >= threshold -> " MATCH"
            else -> " silent"
        }
        val line = "phase=$phase  thr=${"%.2f".format(threshold)}  " +
            "best=$bestStr$gateStr  frames=$frameCount  landmarks=${landmarks.size}"
        runOnUiThread { statusView.text = line }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
        work.shutdown()
        analysisExec.shutdown()
    }

    companion object {
        private const val DEFAULT_THRESHOLD = 0.55f
        private const val THRESHOLD_STEP = 0.05f
    }
}
