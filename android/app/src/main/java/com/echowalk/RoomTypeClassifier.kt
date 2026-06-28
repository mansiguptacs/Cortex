package com.echowalk

/**
 * Infers a human-readable room type from a set of YOLO COCO object classes using weighted scoring.
 *
 * Each COCO class contributes points to one or more room type buckets. The bucket with the
 * highest total score wins. If no bucket scores above [MIN_SCORE], returns "area" (generic).
 *
 * This is intentionally simple — the goal is a useful spoken label for a blind user, not perfect
 * classification. Even a single strong indicator (toilet → bathroom, bed → bedroom) is enough.
 */
object RoomTypeClassifier {

    /** Score buckets. */
    private enum class Room { KITCHEN, BATHROOM, BEDROOM, LIVING_ROOM, OFFICE, DINING_ROOM, OUTDOOR }

    private data class Vote(val room: Room, val points: Int)

    // Exclusive indicators: very high confidence for one room type.
    // Shared indicators: split points across multiple rooms.
    private val votes: Map<String, List<Vote>> = mapOf(
        // --- Kitchen ---
        "refrigerator"   to listOf(Vote(Room.KITCHEN, 10)),
        "oven"           to listOf(Vote(Room.KITCHEN, 10)),
        "microwave"      to listOf(Vote(Room.KITCHEN, 9)),
        "knife"          to listOf(Vote(Room.KITCHEN, 5), Vote(Room.DINING_ROOM, 3)),
        "fork"           to listOf(Vote(Room.KITCHEN, 5), Vote(Room.DINING_ROOM, 4)),
        "spoon"          to listOf(Vote(Room.KITCHEN, 5), Vote(Room.DINING_ROOM, 4)),
        "wine glass"     to listOf(Vote(Room.KITCHEN, 4), Vote(Room.DINING_ROOM, 5)),
        "sink"           to listOf(Vote(Room.KITCHEN, 4), Vote(Room.BATHROOM, 4)),
        "bottle"         to listOf(Vote(Room.KITCHEN, 3)),
        "cup"            to listOf(Vote(Room.KITCHEN, 3), Vote(Room.DINING_ROOM, 3)),
        "bowl"           to listOf(Vote(Room.KITCHEN, 3), Vote(Room.DINING_ROOM, 3)),
        // --- Bathroom ---
        "toilet"         to listOf(Vote(Room.BATHROOM, 10)),
        "toothbrush"     to listOf(Vote(Room.BATHROOM, 9)),
        "hair drier"     to listOf(Vote(Room.BATHROOM, 8)),
        // --- Bedroom ---
        "bed"            to listOf(Vote(Room.BEDROOM, 10)),
        "teddy bear"     to listOf(Vote(Room.BEDROOM, 7)),
        "alarm clock"    to listOf(Vote(Room.BEDROOM, 5), Vote(Room.LIVING_ROOM, 2)),
        // --- Living room ---
        "couch"          to listOf(Vote(Room.LIVING_ROOM, 10)),
        "tv"             to listOf(Vote(Room.LIVING_ROOM, 8)),
        "remote"         to listOf(Vote(Room.LIVING_ROOM, 7)),
        "potted plant"   to listOf(Vote(Room.LIVING_ROOM, 4)),
        "vase"           to listOf(Vote(Room.LIVING_ROOM, 4)),
        "clock"          to listOf(Vote(Room.LIVING_ROOM, 3), Vote(Room.BEDROOM, 3)),
        // --- Office ---
        "laptop"         to listOf(Vote(Room.OFFICE, 7), Vote(Room.LIVING_ROOM, 2)),
        "keyboard"       to listOf(Vote(Room.OFFICE, 9)),
        "mouse"          to listOf(Vote(Room.OFFICE, 9)),
        "cell phone"     to listOf(Vote(Room.OFFICE, 3), Vote(Room.LIVING_ROOM, 3)),
        // --- Dining room ---
        "dining table"   to listOf(Vote(Room.DINING_ROOM, 9), Vote(Room.KITCHEN, 3)),
        // --- Shared: chair scores weakly for office + dining ---
        "chair"          to listOf(Vote(Room.OFFICE, 3), Vote(Room.DINING_ROOM, 3)),
        "book"           to listOf(Vote(Room.OFFICE, 4), Vote(Room.LIVING_ROOM, 2)),
        // --- Outdoor ---
        "car"            to listOf(Vote(Room.OUTDOOR, 8)),
        "truck"          to listOf(Vote(Room.OUTDOOR, 8)),
        "bicycle"        to listOf(Vote(Room.OUTDOOR, 7)),
        "traffic light"  to listOf(Vote(Room.OUTDOOR, 10)),
        "fire hydrant"   to listOf(Vote(Room.OUTDOOR, 10)),
        "stop sign"      to listOf(Vote(Room.OUTDOOR, 10)),
        "bench"          to listOf(Vote(Room.OUTDOOR, 5)),
        "dog"            to listOf(Vote(Room.OUTDOOR, 3), Vote(Room.LIVING_ROOM, 2)),
        "cat"            to listOf(Vote(Room.OUTDOOR, 3), Vote(Room.LIVING_ROOM, 2)),
        "bird"           to listOf(Vote(Room.OUTDOOR, 5)),
    )

    private val roomLabels = mapOf(
        Room.KITCHEN      to "kitchen",
        Room.BATHROOM     to "bathroom",
        Room.BEDROOM      to "bedroom",
        Room.LIVING_ROOM  to "living room",
        Room.OFFICE       to "office",
        Room.DINING_ROOM  to "dining room",
        Room.OUTDOOR      to "outdoor area",
    )

    /** Minimum total score for a room type to be named (vs. generic "area"). */
    private const val MIN_SCORE = 7

    /**
     * Returns a spoken room label, e.g. "kitchen", "bathroom", "living room", or "area" if the
     * object set is too ambiguous to classify.
     */
    fun classify(objects: Set<String>): String {
        val scores = mutableMapOf<Room, Int>()
        for (cls in objects) {
            votes[cls]?.forEach { (room, pts) ->
                scores[room] = (scores[room] ?: 0) + pts
            }
        }
        val best = scores.maxByOrNull { it.value } ?: return "area"
        return if (best.value >= MIN_SCORE) roomLabels[best.key] ?: "area" else "area"
    }
}
