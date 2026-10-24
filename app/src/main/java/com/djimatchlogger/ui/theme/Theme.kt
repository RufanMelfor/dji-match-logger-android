package com.djimatchlogger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * One theme, dark by design.
 *
 * The interface is designed around operator muscle memory, and that
 * interface is dark navy with saturated buttons — see [MatchPalette]. A light
 * variant would not be the same design, so there is no system-following here
 * and no dynamic colour: dynamic colour would let the phone's wallpaper decide
 * whether home and away are still told apart, which is the one thing the
 * colours are for.
 */
@Composable
fun DJIMatchLoggerTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MatchPalette.isDark = darkTheme
    MaterialTheme(colorScheme = matchColors(darkTheme), content = content)
}
