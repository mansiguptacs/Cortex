package com.echowalk.shared

/** High-level interaction modes arbitrated by [ModeManager]. */
enum class AppMode {
    /** Always-on safety radar (Team A) + low-rate place cues (Team C). */
    NAVIGATING,

    /** On-demand scene description (Team B). Heavy inference; pauses the radar loop. */
    DESCRIBING,

    /** Team C learning walk (enrolling landmarks for a place). */
    ENROLLING,
}
