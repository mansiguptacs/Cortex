package com.echowalk.shared

/**
 * The single source of camera frames. ONLY this abstraction touches CameraX.
 * Every team consumes [Frame]s through here — no team opens the camera itself.
 */
interface FrameProvider {
    /** Most recent frame, or null if none yet. Cheap to call (no inference). */
    fun latest(): Frame?

    /** Continuous subscription (Team A). Called on a background thread. */
    fun subscribe(listener: (Frame) -> Unit)

    fun unsubscribe(listener: (Frame) -> Unit)
}
