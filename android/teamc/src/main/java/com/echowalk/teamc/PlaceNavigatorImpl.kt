package com.echowalk.teamc

import com.echowalk.shared.FrameProvider
import com.echowalk.teamc.db.PlaceStore
import com.echowalk.teamc.embedding.ImageEncoder

/**
 * Team C implementation: enroll landmarks, localize via embedding similarity, guide to a saved
 * destination. Runs the encoder at a low rate (~1-2 Hz) so it doesn't starve the safety radar.
 *
 * STUB ONLY — fill in (milestones M-C0..M-C3). See docs/team-c.md.
 */
class PlaceNavigatorImpl(
    private val frames: FrameProvider,
    private val encoder: ImageEncoder?,  // CLIP/MobileCLIP image encoder
    private val store: PlaceStore,
) : PlaceNavigator {

    private var listener: ((PlaceCue) -> Unit)? = null

    override fun enrollStart(placeId: String) { /* TODO(Team C) */ }
    override fun addLandmark(label: String) { /* TODO(Team C) */ }
    override fun enrollStop() { /* TODO(Team C) */ }

    override fun listDestinations(): List<String> = emptyList() // TODO(Team C)
    override fun navigateTo(label: String) { /* TODO(Team C) */ }
    override fun stopNavigation() { /* TODO(Team C) */ }

    override fun observe(listener: (PlaceCue) -> Unit) {
        this.listener = listener
    }
}
