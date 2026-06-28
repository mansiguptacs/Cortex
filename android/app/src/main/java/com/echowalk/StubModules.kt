package com.echowalk

import com.echowalk.teama.RadarState
import com.echowalk.teama.SafetyRadar
import com.echowalk.teamc.PlaceCue
import com.echowalk.teamc.PlaceNavigator

/**
 * Placeholder Team A / Team C modules so the real app shell ([MainActivity] + [ModeManager]) runs
 * the Team B scene-description experience end-to-end before Teams A and C integrate.
 *
 * INTEGRATION (int1): swap these for the real `SafetyRadarController` (Team A) and
 * `PlaceNavigatorImpl` (Team C) — `ModeManager`'s contract is unchanged, so it's a one-line swap.
 */
class NoopSafetyRadar : SafetyRadar {
    override fun start() {}
    override fun stop() {}
    override fun observe(listener: (RadarState) -> Unit) {}
}

class NoopPlaceNavigator : PlaceNavigator {
    override fun enrollStart(placeId: String) {}
    override fun addLandmark(label: String) {}
    override fun enrollStop() {}
    override fun listDestinations(): List<String> = emptyList()
    override fun navigateTo(label: String) {}
    override fun stopNavigation() {}
    override fun observe(listener: (PlaceCue) -> Unit) {}
}
