package com.echowalk.places

/**
 * Turns a chosen destination into a deterministic sequence of guidance cues.
 *
 * The "map" is just the enrollment capture order: landmarks recorded back-to-back are assumed
 * walkable in sequence. A route is therefore the contiguous slice of landmarks between the user's
 * current landmark and the destination (reversed if the destination was enrolled earlier).
 *
 * Cue rules per leg (current -> next waypoint):
 *  - emit a [CueKind.TURN] when the heading change exceeds [turnThresholdDeg] (and headings exist),
 *  - then emit [CueKind.APPROACHING_LANDMARK] for the next waypoint, or [CueKind.ARRIVED] if it is
 *    the destination.
 */
class RouteEngine(
    private val turnThresholdDeg: Float = 30f,
) {

    /** Ordered waypoints from [fromLabel] to [toLabel], inclusive. Empty if either is missing. */
    fun planRoute(landmarks: List<Landmark>, fromLabel: String, toLabel: String): List<Landmark> {
        val ordered = landmarks.sortedBy { it.orderIndex }
        val fromIdx = ordered.indexOfFirst { it.label == fromLabel }
        val toIdx = ordered.indexOfFirst { it.label == toLabel }
        if (fromIdx < 0 || toIdx < 0) return emptyList()
        return when {
            fromIdx <= toIdx -> ordered.subList(fromIdx, toIdx + 1).toList()
            else -> ordered.subList(toIdx, fromIdx + 1).reversed()
        }
    }

    /** Convert an ordered route into the cue sequence spoken to the user. */
    fun toCues(route: List<Landmark>): List<PlaceCue> {
        if (route.isEmpty()) return emptyList()
        if (route.size == 1) {
            return listOf(PlaceCue(CueKind.ARRIVED, route.first().label, confidence = 1f))
        }
        val cues = mutableListOf<PlaceCue>()
        for (i in 1 until route.size) {
            val cur = route[i - 1]
            val next = route[i]
            val turn = Headings.relativeTurn(cur.headingDeg, next.headingDeg)
            if (turn != null && kotlin.math.abs(turn) >= turnThresholdDeg) {
                cues += PlaceCue(
                    kind = CueKind.TURN,
                    label = next.label,
                    confidence = 1f,
                    directionDeg = turn,
                    distanceHint = Headings.describeTurn(turn, turnThresholdDeg),
                )
            }
            val isLast = i == route.size - 1
            cues += PlaceCue(
                kind = if (isLast) CueKind.ARRIVED else CueKind.APPROACHING_LANDMARK,
                label = next.label,
                confidence = 1f,
                directionDeg = turn,
            )
        }
        return cues
    }

    /** Convenience: plan + convert in one call. */
    fun guide(landmarks: List<Landmark>, fromLabel: String, toLabel: String): List<PlaceCue> =
        toCues(planRoute(landmarks, fromLabel, toLabel))
}
