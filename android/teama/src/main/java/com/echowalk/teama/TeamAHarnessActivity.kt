package com.echowalk.teama

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Isolated test bench for Team A (Safety Radar). Drive SafetyRadarController from the live camera
 * or a saved image/video and listen to the audio output — no dependency on Teams B/C.
 *
 * TEMPLATE: fill in. See docs/team-a.md.
 * Launch: adb shell am start -n com.echowalk/.teama.TeamAHarnessActivity
 */
class TeamAHarnessActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO(Team A): set up camera + SafetyRadarController + SpatialAudioEngine here.
    }
}
