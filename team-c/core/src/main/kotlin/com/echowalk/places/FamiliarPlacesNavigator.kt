package com.echowalk.places

/**
 * Pure-logic implementation of [PlaceNavigator]: enrollment, localization with hysteresis, and
 * progressive waypoint guidance. No Android/NPU dependencies — embeddings arrive via [onEmbedding].
 *
 * @param store            place/landmark persistence (in-memory for tests, Room in the app).
 * @param matcher          cosine matcher with the conservative stay-silent threshold.
 * @param routeEngine      builds the waypoint cue sequence.
 * @param framesPerLandmark how many recent embeddings to snapshot per enrolled landmark.
 * @param confirmTicks     localization hysteresis: a landmark must win this many consecutive
 *                         ticks before we announce it (prevents flip-flopping / false jumps).
 */
class FamiliarPlacesNavigator(
    private val store: PlaceStore,
    private val matcher: CosineMatcher = CosineMatcher(),
    private val routeEngine: RouteEngine = RouteEngine(),
    private val framesPerLandmark: Int = 5,
    private val confirmTicks: Int = 3,
) : PlaceNavigator {

    init {
        require(framesPerLandmark >= 1)
        require(confirmTicks >= 1)
    }

    private enum class Mode { IDLE, ENROLLING, ACTIVE }

    private var mode = Mode.IDLE
    private var enrollingPlaceId: String? = null
    private var activePlaceId: String? = null

    private val recent = ArrayDeque<FloatArray>()
    private val observers = mutableListOf<(PlaceCue) -> Unit>()

    // Localization hysteresis state.
    private var candidateId: Long? = null
    private var candidateCount = 0
    private var confirmed: Match? = null

    // Active navigation, if any.
    private class NavState(
        val waypoints: List<Pair<String, List<PlaceCue>>>, // ordered: label -> cues to emit there
        var idx: Int,
        val destination: String,
    )
    private var nav: NavState? = null

    /** Last confirmed localization (after hysteresis), or null if currently unsure/lost. */
    val currentLandmark: Match? get() = confirmed

    val isNavigating: Boolean get() = nav != null

    override fun observe(cb: (PlaceCue) -> Unit) { observers += cb }

    private fun dispatch(cue: PlaceCue) = observers.forEach { it(cue) }

    // ---- Enrollment ---------------------------------------------------------

    override fun enrollStart(placeId: String, placeName: String) {
        store.getPlace(placeId) ?: store.createPlace(placeId, placeName)
        enrollingPlaceId = placeId
        mode = Mode.ENROLLING
        recent.clear()
    }

    override fun addLandmark(label: String) {
        val place = enrollingPlaceId ?: error("call enrollStart() before addLandmark()")
        check(recent.isNotEmpty()) { "no frames buffered yet; feed onEmbedding() during the walk" }
        store.addLandmark(place, label, recent.toList())
    }

    override fun enrollStop() {
        enrollingPlaceId = null
        mode = Mode.IDLE
        recent.clear()
    }

    // ---- Localization / guidance -------------------------------------------

    override fun activatePlace(placeId: String) {
        requireNotNull(store.getPlace(placeId)) { "unknown placeId '$placeId'" }
        activePlaceId = placeId
        mode = Mode.ACTIVE
        resetLocalization()
        nav = null
    }

    override fun listDestinations(): List<String> =
        activePlaceId?.let { store.destinations(it) } ?: emptyList()

    override fun navigateTo(label: String) {
        val place = activePlaceId ?: error("activatePlace() before navigateTo()")
        val landmarks = store.landmarks(place)
        val from = confirmed?.label ?: landmarks.firstOrNull()?.label
            ?: error("place '$place' has no landmarks")
        val route = routeEngine.planRoute(landmarks, from, label)
        require(route.isNotEmpty()) { "no route from '$from' to '$label'" }
        nav = NavState(groupCuesByWaypoint(routeEngine.toCues(route)), idx = 0, destination = label)
    }

    override fun stopNavigation() { nav = null }

    override fun onEmbedding(embedding: FloatArray): List<PlaceCue> {
        pushRecent(embedding)
        if (mode != Mode.ACTIVE) return emptyList()
        val place = activePlaceId ?: return emptyList()
        val match = matcher.best(embedding, store.landmarks(place))
        val newlyConfirmed = ingest(match) ?: return emptyList()
        val cues = cuesForConfirmed(newlyConfirmed)
        cues.forEach(::dispatch)
        return cues
    }

    // ---- internals ----------------------------------------------------------

    private fun pushRecent(embedding: FloatArray) {
        recent.addLast(embedding.copyOf())
        while (recent.size > framesPerLandmark) recent.removeFirst()
    }

    private fun resetLocalization() {
        candidateId = null
        candidateCount = 0
        confirmed = null
    }

    /** Returns the match the moment it becomes newly confirmed (non-null label), else null. */
    private fun ingest(match: Match?): Match? {
        val id = match?.landmarkId
        if (id == candidateId) candidateCount++ else { candidateId = id; candidateCount = 1 }
        if (candidateCount >= confirmTicks && id != confirmed?.landmarkId) {
            confirmed = match
            return match
        }
        return null
    }

    private fun cuesForConfirmed(match: Match): List<PlaceCue> {
        val navState = nav
        if (navState != null) {
            val expected = navState.waypoints.getOrNull(navState.idx)
            if (expected != null && expected.first == match.label) {
                navState.idx++
                if (navState.idx >= navState.waypoints.size) nav = null // reached destination
                return expected.second
            }
        }
        // Idle localization, or wandered off the expected next waypoint: just report position.
        return listOf(PlaceCue(CueKind.LOCATED, match.label, match.score))
    }

    /** Group an ordered cue list into (waypointLabel -> cues) preserving order. */
    private fun groupCuesByWaypoint(cues: List<PlaceCue>): List<Pair<String, List<PlaceCue>>> {
        val out = mutableListOf<Pair<String, MutableList<PlaceCue>>>()
        for (cue in cues) {
            val last = out.lastOrNull()
            if (last != null && last.first == cue.label) last.second += cue
            else out += cue.label to mutableListOf(cue)
        }
        return out.map { it.first to it.second.toList() }
    }
}
