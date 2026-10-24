package com.djimatchlogger.ui.match

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Every size on the match screen, in one place.
 *
 * This is the file to open when the layout wants adjusting: change a number
 * here and the previews in `MatchPreviews.kt` redraw in the editor, without
 * building or deploying anything. Colours have their own file,
 * `ui/theme/Color.kt`.
 *
 * The two sets are not decoration. Landscape is how this gets used beside a
 * pitch and the screen is barely 411dp tall there, so everything below is
 * counted against that budget: header, two rows of panels and the tab bar have
 * to add up to less than that, or something falls off the bottom.
 */

/** How often the match clocks redraw. Fine enough that seconds never skip. */
internal const val TICK_MS = 200L

/** The time of day only has to be right to the second. */
internal const val WALL_TICK_MS = 500L

internal val CompactButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)

/** For the card buttons, which carry the longest labels in the least width. */
internal val TightButtonPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)

/** A Material button is 40dp tall by default, most of the header strip. */
internal val HeaderButtonHeight = 22.dp

class Metrics(
    /** The running match total, the biggest number on the screen. */
    val matchClock: TextStyle,
    /** The period clock beside it, deliberately smaller. */
    val periodClock: TextStyle,
    /** The two scores. */
    val score: TextStyle,
    /** A score box. */
    val scoreBoxWidth: Dp,
    val scoreBoxHeight: Dp,
    /** Goal buttons — the ones hit most and looked at least. */
    val goalWidth: Dp,
    val goalHeight: Dp,
    /** Everything else inside a panel. */
    val buttonHeight: Dp,
    /** Between panels. */
    val gap: Dp,
    /** Between a panel's title and its rows, and between those rows. */
    val panelGap: Dp,
    /** Inside a panel, around its content. */
    val panelPadding: Dp,
)

@Composable
fun metrics(landscape: Boolean): Metrics = if (landscape) {
    Metrics(
        matchClock = MaterialTheme.typography.headlineMedium,
        periodClock = MaterialTheme.typography.titleMedium,
        score = MaterialTheme.typography.displaySmall,
        scoreBoxWidth = 84.dp,
        scoreBoxHeight = 44.dp,
        goalWidth = 140.dp,
        goalHeight = 44.dp,
        buttonHeight = 44.dp,
        gap = 10.dp,
        panelGap = 10.dp,
        panelPadding = 12.dp,
    )
} else {
    Metrics(
        matchClock = MaterialTheme.typography.displaySmall,
        periodClock = MaterialTheme.typography.titleLarge,
        score = MaterialTheme.typography.displaySmall,
        scoreBoxWidth = 92.dp,
        scoreBoxHeight = 56.dp,
        goalWidth = 120.dp,
        goalHeight = 48.dp,
        buttonHeight = 40.dp,
        gap = 8.dp,
        panelGap = 8.dp,
        panelPadding = 10.dp,
    )
}
