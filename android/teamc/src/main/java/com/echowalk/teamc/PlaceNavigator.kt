package com.echowalk.teamc

enum class CueKind { LOCATED, APPROACHING_LANDMARK, TURN, ARRIVED }

data class PlaceCue(
    val kind: CueKind,
    val label: String,         // e.g. "your desk", "Aisle 7"
    val confidence: Float,
    val directionDeg: Float?,  // optional heading hint
    val distanceHint: String?, // optional, e.g. "a few steps"
)

/**
 * Team C's interface: learn places and guide the user back through them.
 * FROZEN — coordinate before changing the signature.
 */
interface PlaceNavigator {
    // Enrollment (learning walk)
    fun enrollStart(placeId: String)
    fun addLandmark(label: String)
    fun enrollStop()

    // Navigation
    fun listDestinations(): List<String>
    fun navigateTo(label: String)
    fun stopNavigation()

    // Low-rate localization + guidance cues
    fun observe(listener: (PlaceCue) -> Unit)
}
