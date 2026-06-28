package com.echowalk

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.GestureDetector
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
import com.echowalk.shared.camera.CameraXFrameProvider
import com.echowalk.teamb.vlm.SceneDescribers

/**
 * App entry point ("EchoWalk"). Hosts the shared camera preview and drives the integrated
 * experience through [ModeManager]:
 *  - Tap anywhere on the preview (or tap the button) -> describe the scene aloud.
 *  - Double-tap the preview / long-press the button -> repeat the last description.
 *  - "Auto-describe" toggle -> hands-free ambient mode (announces confident scene changes only).
 *
 * Volume keys are intentionally left to the system so the user can still adjust volume; the
 * full-screen tap target is the eyes-free trigger.
 *
 * Team A (safety radar) and Team C (familiar places) are wired through the same [ModeManager] via
 * no-op stubs ([NoopSafetyRadar] / [NoopPlaceNavigator]) until they integrate — swapping them in is
 * a one-line change (see int1 in the plan).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var frames: CameraXFrameProvider
    private lateinit var audio: AudioOutputManager
    private lateinit var modeManager: ModeManager

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var hudText: TextView
    private lateinit var describeButton: Button
    private lateinit var ambientSwitch: Switch
    private lateinit var gestures: GestureDetector

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else statusText.text = "Camera permission denied"
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        hudText = findViewById(R.id.hudText)
        describeButton = findViewById(R.id.describeButton)
        ambientSwitch = findViewById(R.id.ambientSwitch)

        frames = CameraXFrameProvider(this)
        audio = AudioOutputManager(this).also { it.init() }
        // VLM if `vlm.pte` is bundled, else classifier if `classifier.pte`+`labels.txt`, else Mock.
        val describer = SceneDescribers.create(this)
        modeManager = ModeManager(
            frames = frames,
            radar = NoopSafetyRadar(),
            describer = describer,
            places = NoopPlaceNavigator(),
            audio = audio,
            onStatus = ::onStatus, // may arrive off the main thread; marshalled below
        )

        describeButton.setOnClickListener { modeManager.describeScene() }
        describeButton.setOnLongClickListener { modeManager.repeatLast(); true }

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
        if (::frames.isInitialized) frames.shutdown()
        if (::audio.isInitialized) audio.shutdown()
        super.onDestroy()
    }
}
