package com.mus.android.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.mus.android.ui.theme.AnimDuration
import com.mus.android.ui.theme.FootnoteStyle
import com.mus.android.ui.theme.LargeTitleStyle
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.MusEasing
import com.mus.android.ui.theme.MusMotion
import com.mus.android.ui.theme.SectionHeaderStyle
import com.mus.android.ui.theme.Title3Style

/**
 * Apple Music–style screen scaffold: a scrollable list whose page title renders as an
 * iOS Large Title, collapsing into a translucent compact bar once the user scrolls.
 * The global ambient gradient shows through — the bar only tints while collapsed.
 */
@Composable
fun AppleLargeTitleList(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    bottomPadding: Dp = 132.dp,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            item(key = "large_title") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Sit below the status bar — the list is edge-to-edge, so without
                        // this inset the large title collides with the clock/battery icons.
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = LargeTitleStyle,
                        color = MusColors.OnBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (trailing != null) {
                        Spacer(Modifier.width(8.dp))
                        trailing()
                    }
                }
            }
            content()
        }

        // Compact collapsing bar
        val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        val barAlpha by animateFloatAsState(
            targetValue = if (collapsed) 1f else 0f,
            animationSpec = tween(AnimDuration.FAST, easing = MusEasing),
            label = "compactBar",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MusColors.Surface.copy(alpha = 0.94f * barAlpha))
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = Title3Style,
                color = MusColors.OnBackground.copy(alpha = barAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Box(Modifier.alpha(barAlpha)) { trailing() }
            }
        }
    }
}

/** iOS-style shelf header: 22pt Bold title with optional trailing text action ("See All"). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = SectionHeaderStyle,
                color = MusColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                Text(
                    text = actionLabel,
                    style = FootnoteStyle.copy(fontWeight = FontWeight.Medium),
                    color = MusColors.AccentDim,
                    modifier = Modifier
                        .clickable(onClick = onAction)
                        .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                )
            }
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = FootnoteStyle,
                color = MusColors.OnBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** iOS-style rounded search field (12dp corners, translucent fill, SF-like glyph). */
@Composable
fun AppleSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "Artists, Songs, Albums, and More",
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    autoFocus: Boolean = false,
    onSearch: (() -> Unit)? = null,
) {
    val latestOnSearch by rememberUpdatedState(onSearch)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MusColors.SurfaceVariant)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MusColors.OnBackgroundTertiary,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = hint,
                    style = FootnoteStyle,
                    color = MusColors.OnBackgroundTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            var fieldModifier = Modifier.fillMaxWidth()
            if (focusRequester != null) fieldModifier = fieldModifier.focusRequester(focusRequester)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = FootnoteStyle.copy(color = MusColors.OnBackground),
                cursorBrush = SolidColor(MusColors.OnBackground),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { latestOnSearch?.invoke() }),
                modifier = fieldModifier,
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Clear search",
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") }
                    .padding(8.dp),
                tint = MusColors.OnBackgroundTertiary,
            )
        }
    }
    LaunchedEffect(autoFocus) {
        if (autoFocus) focusRequester?.requestFocus()
    }
}

/** iOS segmented control — sliding capsule indicator, instant selection. */
@Composable
fun AppleSegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MusColors.SurfaceVariant),
    ) {
        val segmentWidth = maxWidth / options.size
        val indicatorX by animateDpAsState(
            targetValue = segmentWidth * selected,
            animationSpec = MusMotion.springSpec(),
            label = "segment",
        )
        Box(
            Modifier
                .offset(x = indicatorX)
                .width(segmentWidth)
                .fillMaxHeight()
                .padding(2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MusColors.SurfaceElevated),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, option ->
                Box(
                    modifier = Modifier
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                            indication = null,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        style = FootnoteStyle.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp),
                        color = if (index == selected) MusColors.OnBackground else MusColors.OnBackgroundSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Apple Music–style thin slider: 7dp rounded track, solid fill, thumb appears while
 * interacting. Commit happens on release; taps seek immediately.
 */
@Composable
fun AppleSlider(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 7.dp,
    fillColor: androidx.compose.ui.graphics.Color = MusColors.OnBackground,
    trackColor: androidx.compose.ui.graphics.Color = MusColors.SurfaceElevated,
) {
    val latestOnSeek by rememberUpdatedState(onSeek)
    var containerWidthPx by remember { mutableStateOf(1f) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: progress.coerceIn(0f, 1f)
    val fillFraction by animateFloatAsState(
        targetValue = shown.coerceIn(0f, 1f),
        animationSpec = tween(AnimDuration.FAST, easing = MusEasing),
        label = "sliderFill",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .onSizeChanged { containerWidthPx = it.width.coerceAtLeast(1).toFloat() }
            .clip(RoundedCornerShape(50))
            .background(trackColor)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    latestOnSeek((offset.x / containerWidthPx).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragFraction = (offset.x / containerWidthPx).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragFraction?.let { f -> latestOnSeek(f) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.x / containerWidthPx).coerceIn(0f, 1f)
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fillFraction)
                .clip(RoundedCornerShape(50))
                .background(fillColor),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset {
                    IntOffset((fillFraction * containerWidthPx - 7.dp.toPx()).roundToInt(), 0)
                }
                .size(14.dp)
                .clip(CircleShape)
                .background(MusColors.OnBackground),
        )
    }
}

/** Small monochrome "Downloaded" pill used on online search rows. */
@Composable
fun DownloadedBadge(modifier: Modifier = Modifier, label: String = "Downloaded") {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = label,
            modifier = Modifier.size(13.dp),
            tint = MusColors.AccentDim,
        )
        Text(
            text = label,
            style = FootnoteStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = MusColors.AccentDim,
            maxLines = 1,
        )
    }
}

/** Plain circular icon button, Apple-style (no ripple blob, subtle press). */
@Composable
fun AppleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MusColors.OnBackground,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize), tint = tint)
    }
}

/** Horizontally scrolling shelf wrapper: header + LazyRow with 20dp page margins. */
@Composable
fun Shelf(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    itemContent: LazyListScope.() -> Unit,
) {
    Column(modifier = modifier) {
        SectionHeader(title = title, subtitle = subtitle, actionLabel = actionLabel, onAction = onAction)
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            itemContent()
        }
    }
}
