package com.echowalk

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * App entry point. Hosts the shared camera preview and wires the three team modules together
 * through ModeManager.
 *
 * TEMPLATE: wiring is intentionally left to integration. See docs/interfaces.md and the
 * "Integration (INT)" section of the plan. Suggested wiring:
 *   1. CameraXFrameProvider(this).bind(this, previewView)
 *   2. AudioOutputManager(this).init()
 *   3. build SafetyRadarController (Team A), SmolVlmSceneDescriber (Team B), PlaceNavigatorImpl (Team C)
 *   4. ModeManager(frames, radar, describer, places, audio).start()
 *   5. route the describe button + volume key -> ModeManager.describeScene()
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // TODO(Integration): wire FrameProvider + ModeManager + team modules here.
    }
}
