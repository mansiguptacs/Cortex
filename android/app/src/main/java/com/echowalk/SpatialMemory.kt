package com.echowalk

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistent cross-session knowledge store: remembers places (as sets of observed objects) and
 * the last known bearing of each object in that place.
 *
 * A "place" is identified by its **signature** — the sorted set of YOLO object classes stably
 * observed there. Jaccard similarity is used to match a current observation against stored places:
 * overlap ≥ [MATCH_THRESHOLD] (0.45) → same place.
 *
 * Storage: a single JSON file in the app's private files dir. Up to [MAX_PLACES] entries kept;
 * least-recently-visited are evicted when the limit is reached.
 *
 * Thread-safety: all public methods are synchronized.
 */
class SpatialMemory(context: Context) {

    data class PlaceMemory(
        val id: String,
        /** Canonical signature — the object classes that define this place. */
        val signature: Set<String>,
        /** Inferred room type label — e.g. "kitchen", "bathroom". Stored on first visit. */
        var roomType: String = "area",
        /** Last known azimuth (degrees, camera-relative) for each object class seen here. */
        val objectBearings: MutableMap<String, Float> = mutableMapOf(),
        var lastSeenMs: Long = System.currentTimeMillis(),
        var visitCount: Int = 1,
    )

    private val file = File(context.filesDir, "spatial_memory.json")
    private val places = mutableListOf<PlaceMemory>()

    init {
        load()
    }

    // --- Public API -----------------------------------------------------------------------

    /**
     * Record an observation of [objects] (stable visible object classes) with their current
     * [bearings] (azimuthDeg per class). Updates an existing matching place or creates a new one.
     *
     * @return the matched/created [PlaceMemory], or null if [objects] has fewer than [MIN_SIG_SIZE]
     *         items (not distinctive enough).
     */
    @Synchronized
    fun record(objects: Set<String>, bearings: Map<String, Float>, roomType: String = "area"): PlaceMemory? {
        if (objects.size < MIN_SIG_SIZE) return null
        val existing = bestMatch(objects)
        return if (existing != null) {
            existing.objectBearings.putAll(bearings)
            existing.lastSeenMs = System.currentTimeMillis()
            existing.visitCount++
            save()
            existing
        } else {
            val place = PlaceMemory(
                id = UUID.randomUUID().toString().take(8),
                signature = objects,
                roomType = roomType,
                objectBearings = bearings.toMutableMap(),
                lastSeenMs = System.currentTimeMillis(),
            )
            places.add(place)
            evictIfNeeded()
            save()
            place
        }
    }

    /**
     * Try to match [objects] against stored places.
     * @return the best-matching [PlaceMemory] if Jaccard ≥ [MATCH_THRESHOLD], else null.
     */
    @Synchronized
    fun matchPlace(objects: Set<String>): PlaceMemory? = bestMatch(objects)

    /**
     * Return the last known azimuth for [objectClass] across all stored places.
     * Returns the most recently seen bearing if the class appears in multiple places.
     */
    @Synchronized
    fun lastKnownBearing(objectClass: String): Float? {
        return places
            .filter { it.objectBearings.containsKey(objectClass) }
            .maxByOrNull { it.lastSeenMs }
            ?.objectBearings?.get(objectClass)
    }

    /** Number of stored places. */
    @Synchronized
    fun size(): Int = places.size

    // --- Internal -------------------------------------------------------------------------

    private fun bestMatch(objects: Set<String>): PlaceMemory? {
        var bestScore = 0f
        var best: PlaceMemory? = null
        for (place in places) {
            val score = jaccard(objects, place.signature)
            if (score > bestScore) {
                bestScore = score
                best = place
            }
        }
        return if (bestScore >= MATCH_THRESHOLD) best else null
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        val intersection = a.count { it in b }.toFloat()
        val union = (a + b).size.toFloat()
        return if (union == 0f) 0f else intersection / union
    }

    private fun evictIfNeeded() {
        if (places.size > MAX_PLACES) {
            // Remove least-recently-visited
            places.sortBy { it.lastSeenMs }
            repeat(places.size - MAX_PLACES) { places.removeAt(0) }
        }
    }

    // --- Persistence (JSON) ---------------------------------------------------------------

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sig = o.getJSONArray("sig").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                val bearingsObj = o.optJSONObject("bearings") ?: JSONObject()
                val bearings = mutableMapOf<String, Float>()
                bearingsObj.keys().forEach { k -> bearings[k] = bearingsObj.getDouble(k).toFloat() }
                places.add(
                    PlaceMemory(
                        id = o.optString("id", UUID.randomUUID().toString().take(8)),
                        signature = sig,
                        roomType = o.optString("type", "area"),
                        objectBearings = bearings,
                        lastSeenMs = o.optLong("ts", System.currentTimeMillis()),
                        visitCount = o.optInt("visits", 1),
                    )
                )
            }
            Log.d(TAG, "Loaded ${places.size} places from disk")
        }.onFailure { Log.w(TAG, "Failed to load spatial memory", it) }
    }

    private fun save() {
        runCatching {
            val arr = JSONArray()
            for (p in places) {
                val o = JSONObject()
                o.put("id", p.id)
                val sigArr = JSONArray(); p.signature.forEach { sigArr.put(it) }
                o.put("sig", sigArr)
                o.put("type", p.roomType)
                val bearingsObj = JSONObject(); p.objectBearings.forEach { (k, v) -> bearingsObj.put(k, v.toDouble()) }
                o.put("bearings", bearingsObj)
                o.put("ts", p.lastSeenMs)
                o.put("visits", p.visitCount)
                arr.put(o)
            }
            file.writeText(arr.toString())
        }.onFailure { Log.w(TAG, "Failed to save spatial memory", it) }
    }

    companion object {
        private const val TAG = "SpatialMemory"
        /** Minimum objects in a signature to be considered distinctive. */
        private const val MIN_SIG_SIZE = 2
        /** Jaccard threshold for two scenes to be considered the same place. */
        private const val MATCH_THRESHOLD = 0.45f
        /** Maximum stored places before LRU eviction. */
        private const val MAX_PLACES = 60
    }
}
