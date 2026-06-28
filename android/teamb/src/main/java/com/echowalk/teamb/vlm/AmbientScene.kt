package com.echowalk.teamb.vlm

import com.echowalk.shared.Frame

/**
 * Optional capability for a describer that can rank a frame's scene cheaply, *without speaking*.
 * Ambient (auto) mode polls this continuously; describers that are too heavy for that (e.g. a VLM)
 * simply don't implement it, so the auto-mode toggle only appears when it makes sense.
 */
interface AmbientScene {
    /** Ranked friendly scene terms for one frame (most likely first); empty if not applicable. */
    suspend fun rankScenes(frame: Frame): List<LabelScore>
}
