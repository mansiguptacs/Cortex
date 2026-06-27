package com.echowalk.teamb

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.echowalk.shared.AudioOutputManager
import com.echowalk.shared.camera.CameraXFrameProvider
import kotlinx.coroutines.launch

/**
 * Team B isolation harness.
 *
 * STEP 2 (current): tap "Describe" -> run a [SceneDescriber] (currently [MockSceneDescriber]) ->
 * show the text and speak it via the shared [AudioOutputManager] (Android on-device TTS).
 * Still no model — this proves the capture -> describe -> speak pipeline end to end.
 *
 * Next: U-Step 3 richer UI states, then U-Step 4 swap in SmolVlmSceneDescriber + EtModule.
 */
class TeamBHarnessActivity : AppCompatActivity() {

    private lateinit var frames: CameraXFrameProvider
    private lateinit var audio: AudioOutputManager
    private val describer: SceneDescriber = MockSceneDescriber()

    private lateinit var previewView: PreviewView
    private lateinit var capturedThumb: ImageView
    private lateinit var statusText: TextView
    private lateinit var describeButton: Button

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else statusText.text = "Camera permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teamb_harness)

        previewView = findViewById(R.id.previewView)
        capturedThumb = findViewById(R.id.capturedThumb)
        statusText = findViewById(R.id.statusText)
        describeButton = findViewById(R.id.describeButton)
        describeButton.setOnClickListener { onDescribe() }

        frames = CameraXFrameProvider(this)
        audio = AudioOutputManager(this).also { it.init() }

        if (hasCameraPermission()) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        frames.bind(this, previewView)
        statusText.text = "Camera running - tap Describe"
    }

    private fun onDescribe() {
        val frame = frames.latest()
        if (frame == null) {
            statusText.text = "No frame yet - give the camera a moment"
            return
        }
        capturedThumb.setImageBitmap(frame.rgb)
        describeButton.isEnabled = false
        statusText.text = "Thinking..."
        lifecycleScope.launch {
            val text = describer.describe(frame)
            statusText.text = text
            audio.speak(text)
            describeButton.isEnabled = true
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        if (::frames.isInitialized) frames.shutdown()
        if (::audio.isInitialized) audio.shutdown()
        super.onDestroy()
    }
}
