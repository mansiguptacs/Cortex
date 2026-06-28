package com.echowalk.teamb.vlm

/** A single classifier prediction: cleaned label + softmax probability in [0,1]. */
data class LabelScore(val label: String, val prob: Float)

/**
 * Optional capability a [com.echowalk.teamb.SceneDescriber] can implement to expose what it saw on
 * its most recent run, for the demo HUD / debugging. Keeps the core describe() contract unchanged.
 */
interface SceneDiagnostics {
    /** Top predictions from the last describe(), most confident first; empty if not applicable. */
    fun lastTopK(): List<LabelScore>
}
