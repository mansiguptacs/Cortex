package com.echowalk.teamb

import com.echowalk.shared.Frame

/**
 * Team B's interface: describe one frame in spoken-ready text.
 * FROZEN — coordinate before changing the signature.
 */
interface SceneDescriber {
    suspend fun describe(frame: Frame): String
}
