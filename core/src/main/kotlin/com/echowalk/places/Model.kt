package com.echowalk.places

/**
 * Domain model for the "familiar places" feature. A [Place] (e.g. "my office") holds many
 * ordered [Landmark]s recorded during the enrollment learning-walk. Each landmark keeps several
 * embeddings (multi-frame enrollment) for viewpoint robustness.
 */

data class Place(
    val id: String,
    val name: String,
)

data class Landmark(
    val id: Long,
    val placeId: String,
    val label: String,
    /** One or more L2-normalized CLIP image embeddings captured for this landmark. */
    val embeddings: List<FloatArray>,
    /** Device heading (degrees, 0..360) at capture time, if available. */
    val headingDeg: Float? = null,
    /** Capture order during the learning walk; used as the default route sequence. */
    val orderIndex: Int,
)

/** Cue emitted to the audio bus. Mirrors the frozen PlaceNavigator contract. */
enum class CueKind { LOCATED, APPROACHING_LANDMARK, TURN, ARRIVED }

data class PlaceCue(
    val kind: CueKind,
    val label: String,
    val confidence: Float,
    val directionDeg: Float? = null,
    val distanceHint: String? = null,
)
