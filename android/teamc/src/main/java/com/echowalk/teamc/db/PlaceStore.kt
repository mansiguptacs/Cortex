package com.echowalk.teamc.db

/**
 * On-device storage of enrolled places and their landmarks. NEVER syncs off-device.
 * Back with Room/SQLite or a simple file — implementation's choice.
 *
 * STUB ONLY — fill in (milestone M-C1). See docs/team-c.md.
 */
class PlaceStore {

    data class Landmark(
        val placeId: String,
        val label: String,
        val embedding: FloatArray,
        val headingDeg: Float?,
    )

    fun saveLandmark(landmark: Landmark) { /* TODO(Team C) */ }
    fun landmarksFor(placeId: String): List<Landmark> = emptyList() // TODO(Team C)
    fun allPlaceIds(): List<String> = emptyList() // TODO(Team C)
}
