package com.echowalk.places

/**
 * Persistence abstraction for enrolled places and their landmarks.
 *
 * This interface is deliberately storage-agnostic so the pure logic (enrollment, localization,
 * guidance) is testable on the JVM today with [InMemoryPlaceStore], and the Android Room/SQLite
 * implementation drops in later behind the same contract with zero changes to callers.
 *
 * [addLandmark] auto-assigns a unique landmark id and the next [Landmark.orderIndex] within the
 * place (capture order during the learning walk == default route sequence).
 */
interface PlaceStore {
    fun createPlace(id: String, name: String): Place
    fun getPlace(id: String): Place?
    fun places(): List<Place>

    fun addLandmark(
        placeId: String,
        label: String,
        embeddings: List<FloatArray>,
        headingDeg: Float? = null,
    ): Landmark

    /** Landmarks for a place, ordered by capture order. */
    fun landmarks(placeId: String): List<Landmark>

    /** Distinct destination labels for a place, in capture order. */
    fun destinations(placeId: String): List<String>

    fun deletePlace(id: String)
    fun clear()
}
