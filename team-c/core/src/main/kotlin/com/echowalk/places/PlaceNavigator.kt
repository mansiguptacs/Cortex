package com.echowalk.places

/**
 * Frozen-ish contract for the familiar-places mode (mirrors the master plan's `PlaceNavigator`).
 *
 * In the Android app an adapter subscribes to the shared `FrameProvider`, runs the CLIP encoder
 * (Lane 3) on each low-rate frame, and calls [onEmbedding] with the resulting vector. Keeping that
 * vector seam explicit is what lets the whole controller be tested on the JVM (Lane 1) with no
 * camera, NPU, or `.pte`.
 */
interface PlaceNavigator {
    // Enrollment (learning walk)
    fun enrollStart(placeId: String, placeName: String = placeId)
    fun addLandmark(label: String)
    fun enrollStop()

    // Localization / guidance
    fun activatePlace(placeId: String)
    fun listDestinations(): List<String>
    fun navigateTo(label: String)
    fun stopNavigation()

    // Output + input seams
    fun observe(cb: (PlaceCue) -> Unit)

    /** Localization/enrollment tick. Returns cues emitted this tick (also sent to observers). */
    fun onEmbedding(embedding: FloatArray): List<PlaceCue>
}
