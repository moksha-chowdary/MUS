package com.mus.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.dp

/**
 * MUS standard easing curve — used for ALL sheet/modal/transition animations.
 * Provides a consistent, considered feel across the entire app.
 */
val MusEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * Standard spacing scale.
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val base = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
    val huge = 64.dp
}

/**
 * Standard animation durations.
 */
object AnimDuration {
    const val FAST = 200
    const val NORMAL = 300
    const val SLOW = 500
    const val TRACK_CHANGE = 400
    const val GRADIENT_CROSSFADE = 1000
}
