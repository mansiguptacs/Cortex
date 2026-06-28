package com.echowalk

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick Settings tile — the user adds "Cortex" to their quick-settings panel once.
 * After that, a single tap from ANY screen (including lock screen) launches the app.
 *
 * Setup (one-time): pull down the notification shade → tap the pencil/edit icon →
 * find "Cortex" → drag it into the active tiles row → done.
 */
@RequiresApi(Build.VERSION_CODES.N)
class CortexTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Cortex"
            contentDescription = "Launch Cortex navigation app"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // startActivityAndCollapse launches the app and collapses the shade in one step.
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
