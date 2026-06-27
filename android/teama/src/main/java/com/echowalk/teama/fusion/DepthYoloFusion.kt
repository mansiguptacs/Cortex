package com.echowalk.teama.fusion

import com.echowalk.teama.Hazard
import com.echowalk.teama.HazardKind
import com.echowalk.teama.RadarState
import kotlin.math.max
import kotlin.math.min

/**
 * Semantic fusion: mask the depth map by each YOLO box, take a robust (trimmed) average to get a
 * per-object distance, derive azimuth from the box center, and classify each as WALL / OBSTACLE /
 * DROPOFF. Also computes nearest distance per L/C/R zone for the depth-only fallback.
 *
 * **Depth scale convention:** AI Hub's Depth-Anything-V2-Small returns a *relative* depth map
 * where **larger values mean closer**. We expose two thresholds (URGENT, MID) chosen against
 * that scale; calibrate empirically (`p3-thresholds`). We also expose those values as the
 * "distanceM" in [Hazard] — the radar uses them as a monotonic proxy, not as true meters.
 *
 * **Drop-off heuristic:** a sharp negative depth gradient near the bottom-center of the frame
 * (foreground further-than-floor surface) flags a stair / edge / curb.
 */
class DepthYoloFusion(
    private val urgentDepth: Float = URGENT_REL_DEPTH,
    private val midDepth: Float = MID_REL_DEPTH,
    private val minScore: Float = 0.35f,
    private val wallClasses: Set<String> = WALL_LIKE,
) {

    data class Detection(
        val cls: String,
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val score: Float,
    )

    /**
     * @param depth relative depth map (row-major, [depthH x depthW]); larger = closer.
     * @param detections YOLOv10 outputs already in normalized [0,1] coords (relative to the
     *                   letterboxed 640×640 input — which we resized from the upright frame, so
     *                   they map 1:1 onto the depth map after letterboxing the same way).
     */
    fun fuse(
        depth: FloatArray,
        depthW: Int,
        depthH: Int,
        detections: List<Detection>,
        tsMs: Long,
    ): RadarState {
        val hazards = ArrayList<Hazard>(detections.size + 1)
        for (det in detections) {
            if (det.score < minScore) continue
            val px0 = (det.x0 * depthW).toInt().coerceIn(0, depthW - 1)
            val py0 = (det.y0 * depthH).toInt().coerceIn(0, depthH - 1)
            val px1 = (det.x1 * depthW).toInt().coerceIn(px0 + 1, depthW)
            val py1 = (det.y1 * depthH).toInt().coerceIn(py0 + 1, depthH)
            val nearest = trimmedNearest(depth, depthW, px0, py0, px1, py1)
            val azimuth = ((det.x0 + det.x1) * 0.5f - 0.5f) * HORIZONTAL_FOV_DEG
            val kind = when {
                det.cls in wallClasses -> HazardKind.WALL
                else -> HazardKind.OBSTACLE
            }
            hazards.add(Hazard(cls = det.cls, distanceM = nearest, azimuthDeg = azimuth, kind = kind))
        }
        // Drop-off check: large depth-drop near bottom-center vs immediate floor below the user.
        detectDropOff(depth, depthW, depthH)?.let { hazards.add(it) }

        val zoneNearest = zoneNearest(depth, depthW, depthH)
        return RadarState(zoneNearestM = zoneNearest, hazards = hazards, tsMs = tsMs)
    }

    /** Top-decile of depth values inside the box = nearest part of the object. */
    private fun trimmedNearest(depth: FloatArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var sum = 0f
        var count = 0
        var maxSeen = Float.MIN_VALUE
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                val v = depth[row + x]
                if (v > maxSeen) maxSeen = v
                sum += v
                count++
            }
        }
        if (count == 0) return 0f
        // Combine the mean with a max bias so a single shimmering pixel doesn't dominate but
        // sticking-out parts of the object still register.
        return 0.3f * (sum / count) + 0.7f * maxSeen
    }

    /** Min nearest-distance per left/center/right horizontal zone. */
    private fun zoneNearest(depth: FloatArray, w: Int, h: Int): FloatArray {
        val zoneW = w / 3
        val zones = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        // Skip the very top (ceiling) and the very bottom (immediate floor) bands.
        val y0 = h / 5
        val y1 = h - h / 8
        for (y in y0 until y1) {
            val row = y * w
            for (z in 0..2) {
                val xs = z * zoneW
                val xe = if (z == 2) w else xs + zoneW
                var m = zones[z]
                for (x in xs until xe) {
                    val v = depth[row + x]
                    if (v > m) m = v
                }
                zones[z] = m
            }
        }
        return zones
    }

    private fun detectDropOff(depth: FloatArray, w: Int, h: Int): Hazard? {
        // Compare a band just below image center to a band near the bottom; if the bottom band
        // is much *farther* than the upper band, there's an edge (floor drops away).
        val midBandY0 = (h * 0.55f).toInt()
        val midBandY1 = (h * 0.65f).toInt()
        val botBandY0 = (h * 0.85f).toInt()
        val botBandY1 = h
        val xs = w / 3
        val xe = 2 * w / 3
        val midNear = bandNear(depth, w, xs, midBandY0, xe, midBandY1)
        val botNear = bandNear(depth, w, xs, botBandY0, xe, botBandY1)
        return if (midNear - botNear > DROPOFF_REL_DEPTH_DELTA) {
            Hazard(cls = "drop", distanceM = midNear, azimuthDeg = 0f, kind = HazardKind.DROPOFF)
        } else null
    }

    private fun bandNear(d: FloatArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var m = Float.NEGATIVE_INFINITY
        for (y in max(0, y0) until y1) {
            val row = y * w
            for (x in x0 until x1) if (d[row + x] > m) m = d[row + x]
        }
        return if (m == Float.NEGATIVE_INFINITY) 0f else m
    }

    companion object {
        // Empirically-calibrate these in Phase 3.
        const val URGENT_REL_DEPTH = 9.0f
        const val MID_REL_DEPTH = 6.0f
        const val DROPOFF_REL_DEPTH_DELTA = 3.0f

        // Approximate horizontal FOV of the S25 Ultra back camera in degrees (for stereo pan).
        const val HORIZONTAL_FOV_DEG = 78f

        val WALL_LIKE = setOf("wall", "door", "refrigerator", "bookshelf")
    }
}
