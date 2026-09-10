package com.mus.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.mus.android.ui.theme.MusMotion

/**
 * Wrapper for list items providing a subtle entrance transition.
 * Fires exactly once on appearance without re-triggering on position updates.
 */
@Composable
fun AnimatedListItem(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = MusMotion.ListEntranceDuration,
                delayMillis = (index.coerceAtMost(10) * 20),
                easing = MusMotion.EmphasizedEasing
            )
        ) + slideInVertically(
            initialOffsetY = { 20 },
            animationSpec = tween(
                durationMillis = MusMotion.ListEntranceDuration,
                delayMillis = (index.coerceAtMost(10) * 20),
                easing = MusMotion.EmphasizedEasing
            )
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}
