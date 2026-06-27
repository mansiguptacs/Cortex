package com.echowalk.teama.fusion

import com.echowalk.teama.Hazard
import com.echowalk.teama.RadarState

/**
 * Semantic fusion: mask the depth map by each YOLO box, take a robust (trimmed) average to get a
 * per-object distance, derive azimuth from the box center, and classify each as WALL / OBSTACLE /
 * DROPOFF. Also computes nearest distance per L/C/R zone for the depth-only fallback.
 *
 * STUB: fill in with real math (milestone M4). See docs/team-a.md.
 */
class DepthYoloFusion {

    data class Detection(val cls: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val score: Float)

    /**
     * @param depth relative depth map (row-major, [depthH x depthW])
     * @param detections YOLO outputs in normalized [0,1] coords
     */
    fun fuse(
        depth: FloatArray,
        depthW: Int,
        depthH: Int,
        detections: List<Detection>,
        tsMs: Long,
    ): RadarState {
        // TODO(Team A): mask depth by boxes, robust-average, classify, compute zone minima.
        val hazards = emptyList<Hazard>()
        return RadarState(
            zoneNearestM = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE),
            hazards = hazards,
            tsMs = tsMs,
        )
    }
}
