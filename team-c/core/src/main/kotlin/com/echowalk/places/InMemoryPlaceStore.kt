package com.echowalk.places

/**
 * In-memory [PlaceStore] for JVM tests and the standalone harness (Lane 1, no Android).
 *
 * Behavior the Room/SQLite implementation must also honor:
 * - landmark ids are unique and monotonically increasing across the whole store,
 * - [Landmark.orderIndex] is the per-place capture order (0-based), assigned automatically,
 * - [landmarks] / [destinations] return results in capture order.
 *
 * Embeddings are defensively copied on the way in so callers can reuse their input buffers.
 */
class InMemoryPlaceStore : PlaceStore {

    private val placesById = LinkedHashMap<String, Place>()
    private val landmarksByPlace = LinkedHashMap<String, MutableList<Landmark>>()
    private var nextLandmarkId = 1L

    override fun createPlace(id: String, name: String): Place {
        require(id.isNotBlank()) { "place id must not be blank" }
        val place = Place(id, name)
        placesById[id] = place
        landmarksByPlace.getOrPut(id) { mutableListOf() }
        return place
    }

    override fun getPlace(id: String): Place? = placesById[id]

    override fun places(): List<Place> = placesById.values.toList()

    override fun addLandmark(
        placeId: String,
        label: String,
        embeddings: List<FloatArray>,
        headingDeg: Float?,
    ): Landmark {
        require(label.isNotBlank()) { "landmark label must not be blank" }
        require(embeddings.isNotEmpty()) { "a landmark needs at least one embedding" }
        val place = placesById[placeId]
            ?: error("unknown placeId '$placeId'; createPlace() first")
        val bucket = landmarksByPlace.getOrPut(place.id) { mutableListOf() }
        val landmark = Landmark(
            id = nextLandmarkId++,
            placeId = place.id,
            label = label,
            embeddings = embeddings.map { it.copyOf() },
            headingDeg = headingDeg,
            orderIndex = bucket.size,
        )
        bucket.add(landmark)
        return landmark
    }

    override fun landmarks(placeId: String): List<Landmark> =
        landmarksByPlace[placeId]?.sortedBy { it.orderIndex } ?: emptyList()

    override fun destinations(placeId: String): List<String> =
        landmarks(placeId).map { it.label }.distinct()

    override fun deletePlace(id: String) {
        placesById.remove(id)
        landmarksByPlace.remove(id)
    }

    override fun clear() {
        placesById.clear()
        landmarksByPlace.clear()
        nextLandmarkId = 1L
    }
}
