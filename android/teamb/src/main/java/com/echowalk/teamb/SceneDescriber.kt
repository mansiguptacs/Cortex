package com.echowalk.teamb

import com.echowalk.shared.Frame

/**
 * Team B's interface: describe a scene in spoken-ready text.
 * The single-frame [describe] is FROZEN — coordinate before changing its signature.
 */
interface SceneDescriber {
    suspend fun describe(frame: Frame): String

    /**
     * Optional temporal-voting entry point: describe from a short burst of frames for stability.
     * Default just uses the most recent frame, so existing describers need no changes.
     */
    suspend fun describe(frames: List<Frame>): String =
        describe(frames.lastOrNull() ?: error("describe(frames) called with no frames"))

    /** Optional: run one throwaway inference so the first real describe isn't slow. No-op by default. */
    suspend fun warmUp() {}
}
