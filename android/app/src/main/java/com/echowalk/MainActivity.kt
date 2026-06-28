package com.echowalk

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.echowalk.shared.AudioOutputManager
import com.echowalk.shared.TfliteQnnModule
import com.echowalk.shared.camera.CameraXFrameProvider
import com.echowalk.teama.SafetyRadarController
import com.echowalk.teama.audio.SpatialAudioEngine
import com.echowalk.teamb.vlm.SceneDescribers

/**
 * App entry point ("EchoWalk"). Hosts the shared camera preview and drives the integrated
 * experience through [ModeManager]:
 *  - Tap anywhere on the preview (or tap the button) -> describe the scene aloud.
 *  - Double-tap the preview / long-press the button -> repeat the last description.
 *  - "Auto-describe" toggle -> hands-free ambient mode (announces confident scene changes only).
 *
 * Volume key shortcuts (eyes-free, works with screen off):
 *  - Long-press Volume Up   → Describe scene
 *  - Long-press Volume Down → Find mode (starts voice query)
 *  - Short press            → normal system volume (not consumed)
 *
 * Team A (safety radar) and Team C (familiar places) are wired through the same [ModeManager] via
 * no-op stubs ([NoopSafetyRadar] / [NoopPlaceNavigator]) until they integrate — swapping them in is
 * a one-line change (see int1 in the plan).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var frames: CameraXFrameProvider
    private lateinit var audio: AudioOutputManager
    private lateinit var modeManager: ModeManager
    private lateinit var speechInput: SpeechInputController
    private lateinit var radar: SafetyRadarController
    private lateinit var overlay: ObjectOverlayView

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var hudText: TextView
    private lateinit var describeButton: Button
    private lateinit var findButton: Button
    private lateinit var ambientSwitch: Switch
    private lateinit var gestures: GestureDetector

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else statusText.text = "Camera permission denied"
        }

    private val requestAudio =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> /* optional */ }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show app on lock screen and turn screen on when launched — essential for visually
        // impaired users who need to access the app without unlocking the phone first.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        hudText = findViewById(R.id.hudText)
        describeButton = findViewById(R.id.describeButton)
        findButton = findViewById(R.id.findButton)
        ambientSwitch = findViewById(R.id.ambientSwitch)
        overlay = findViewById(R.id.overlayContainer)

        frames = CameraXFrameProvider(this)
        audio = AudioOutputManager(this).also { it.init() }

        // Team A: load depth + YOLO models onto Hexagon NPU (graceful null if assets missing).
        val spatial = SpatialAudioEngine(audio)
        val depthMod = try { TfliteQnnModule.loadAsset(this, "depth_anything_v2.tflite") }
                       catch (t: Throwable) { Log.w(TAG, "depth model unavailable", t); null }
        val yoloMod  = try { TfliteQnnModule.loadAsset(this, "yolov10_det.tflite") }
                       catch (t: Throwable) { Log.w(TAG, "yolo model unavailable", t); null }
        val labels   = try { assets.open("coco.names").bufferedReader().readLines().filter { it.isNotBlank() } }
                       catch (_: Throwable) { emptyList() }
        val radar = SafetyRadarController(frames, depthMod, yoloMod, spatial, labels)
        this.radar = radar

        var currentFindTarget: String? = null

        val describer = SceneDescribers.create(this)
        val spatialMemory = SpatialMemory(applicationContext)
        modeManager = ModeManager(
            frames = frames,
            radar = radar,
            spatial = spatial,
            describer = describer,
            places = NoopPlaceNavigator(),
            audio = audio,
            onStatus = ::onStatus,
            spatialMemory = spatialMemory,
            onRadarStateExtra = { state ->
                // Update bounding box overlay on every radar tick.
                val target = currentFindTarget
                val boxes = state.hazards.map { h ->
                    ObjectOverlayView.Box(
                        label = h.cls,
                        x0 = h.boxX0, y0 = h.boxY0,
                        x1 = h.boxX1, y1 = h.boxY1,
                        isTarget = (target != null && h.cls == target),
                    )
                }
                overlay.update(boxes)
            },
        )
        speechInput = SpeechInputController(this, audio) { targetClass ->
            currentFindTarget = targetClass
            modeManager.startFind(targetClass)
        }

        describeButton.setOnClickListener { modeManager.describeScene() }
        describeButton.setOnLongClickListener { modeManager.repeatLast(); true }

        // Find mode: hold to speak your query ("find the chair"), release to search.
        findButton.setOnLongClickListener {
            if (modeManager.isInFindMode) {
                currentFindTarget = null
                overlay.update(emptyList())
                modeManager.stopFind()
                true
            } else { speechInput.startListening(); true }
        }
        findButton.setOnClickListener {
            if (modeManager.isInFindMode) {
                currentFindTarget = null
                overlay.update(emptyList())
                modeManager.stopFind()
            }
        }

        // Eyes-free: tap anywhere on the preview to describe, double-tap to repeat.
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                modeManager.describeScene(); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                modeManager.repeatLast(); return true
            }
        })
        previewView.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev); true }

        // Ambient (auto) mode is only offered when the engine can rank scenes cheaply.
        if (modeManager.ambientSupported) {
            ambientSwitch.setOnCheckedChangeListener { _, on ->
                if (on) modeManager.startAmbient() else modeManager.stopAmbient()
            }
        } else {
            ambientSwitch.isEnabled = false
            ambientSwitch.text = "Auto-describe (needs scene model)"
        }

        if (hasCameraPermission()) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
        if (!hasAudioPermission()) requestAudio.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startCamera() {
        frames.bind(this, previewView)
        modeManager.start()
    }

    /** Render a [ModeManager.Status]. Invoked from a background coroutine -> marshal to the UI. */
    private fun onStatus(status: ModeManager.Status) = runOnUiThread {
        status.message?.let { statusText.text = it }
        status.hud?.let {
            hudText.text = it
            hudText.visibility = View.VISIBLE
        }
        describeButton.isEnabled = status.phase == ModeManager.Phase.READY
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onPause() {
        if (::modeManager.isInitialized) modeManager.stopAmbient()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::ambientSwitch.isInitialized && ambientSwitch.isChecked && modeManager.ambientSupported) {
            modeManager.startAmbient()
        }
    }

    override fun onDestroy() {
        if (::modeManager.isInitialized) modeManager.stop()
        if (::radar.isInitialized) radar.destroy()
        if (::frames.isInitialized) frames.shutdown()
        if (::audio.isInitialized) audio.shutdown()
        if (::speechInput.isInitialized) speechInput.release()
        super.onDestroy()
    }

    // --- Volume key shortcuts (eyes-free hardware triggers) ----------------------------

    // onKeyDown must return true for the volume keys we care about so Android knows we
    // want long-press callbacks; for all other keys fall through to the system.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!::modeManager.isInitialized) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                event?.startTracking() // required for onKeyLongPress to fire
                true                   // consume the down event
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (!::modeManager.isInitialized) return super.onKeyLongPress(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // Long-press Vol+ → Describe scene
                audio.haptic(40)
                modeManager.describeScene()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Long-press Vol- → Find mode (start voice query)
                audio.haptic(40)
                if (modeManager.isInFindMode) {
                    modeManager.stopFind()
                } else {
                    if (::speechInput.isInitialized) speechInput.startListening()
                }
                true
            }
            else -> super.onKeyLongPress(keyCode, event)
        }
    }

    // Short press: let the key up through so system handles volume normally.
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // Only pass to system if no long-press was detected
            if (event?.isLongPress == false) return super.onKeyUp(keyCode, event)
            return true // long-press already handled — swallow the up
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
