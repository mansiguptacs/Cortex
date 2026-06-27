package com.echowalk.teamb

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Isolated test bench for Team B (Scene Description). A "Describe" button captures one frame and
 * speaks the VLM result — no dependency on Teams A/C.
 *
 * TEMPLATE: fill in. See docs/team-b.md.
 * Launch: adb shell am start -n com.echowalk/.teamb.TeamBHarnessActivity
 */
class TeamBHarnessActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO(Team B): set up camera + describe button + SmolVlmSceneDescriber + TTS here.
    }
}
