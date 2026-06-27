package com.echowalk.shared

import android.graphics.Bitmap

/**
 * One camera frame, upright RGB (rotation already applied).
 *
 * Team A subscribes continuously; Teams B & C grab single frames on demand.
 * This is a FROZEN shared type — coordinate before changing it.
 */
data class Frame(
    val rgb: Bitmap,
    val width: Int,
    val height: Int,
    val tsMs: Long,
)
