package com.mus.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Shared motion tokens for cohesive and premium animations across MUS.
 */
object MusMotion {
    // Shared easing curves
    val EmphasizedEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val SubtleEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    // Standard durations (in ms)
    const val TrackChangeDuration = 400
    const val BackgroundCrossfadeDuration = 1000
    const val ListEntranceDuration = 250
    const val FastFeedbackDuration = 150

    // Animation specs
    fun <T> trackChangeSpec() = tween<T>(
        durationMillis = TrackChangeDuration,
        easing = EmphasizedEasing,
    )

    fun <T> listEntranceSpec(delayMs: Int = 0) = tween<T>(
        durationMillis = ListEntranceDuration,
        delayMillis = delayMs,
        easing = EmphasizedEasing,
    )

    fun <T> springSpec() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
    )
}
