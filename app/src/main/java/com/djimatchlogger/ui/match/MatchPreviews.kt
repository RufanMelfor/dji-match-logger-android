package com.djimatchlogger.ui.match

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.djimatchlogger.match.Match
import com.djimatchlogger.match.MatchMeta
import com.djimatchlogger.match.MatchTeam
import com.djimatchlogger.ui.theme.DJIMatchLoggerTheme

/**
 * The match screen in Android Studio's design pane.
 *
 * Open this file, put the editor in Split view, and the screen draws itself
 * next to the code. Change a number in `MatchMetrics.kt` or a colour in
 * `ui/theme/Color.kt` and the previews redraw — no build, no device, no
 * cameras. That is what these are for: the layout is tuned for a screen about
 * 890 x 410dp, and finding out it does not fit by walking to a pitch is a slow
 * way to work.
 *
 * The previews render [MatchContent], which takes a plain [Match] and a set of
 * do-nothing [MatchActions]. Buttons therefore do nothing unless you turn on
 * interactive mode, and even then they only move state the preview holds
 * itself.
 */

/** Landscape, roughly the Nokia 7.2 the app is tested on. */
private const val LANDSCAPE = "spec:width=891dp,height=411dp,dpi=420"

private const val PORTRAIT = "spec:width=411dp,height=891dp,dpi=420"

/**
 * A match a quarter of an hour in, with something in the log.
 *
 * Anchored at the current [SystemClock.elapsedRealtime] because that is what
 * the clocks read from; timestamps starting at zero would put the match days
 * in the past and the clock would say so.
 */
private fun sampleMatch(
    paused: Boolean = false,
    ended: Boolean = false,
    addedTime: Int = 0,
): Match {
    val start = SystemClock.elapsedRealtime() - 15 * 60_000L
    val minute = 60_000L

    var match = Match(meta = MatchMeta(home = "The Grid", away = "Visitors"))
        .startNextPeriod(atMs = start)
        .logGoal(MatchTeam.HOME, atMs = start + 4 * minute)
        .logYellowCard(MatchTeam.AWAY, atMs = start + 7 * minute)
        .logGoal(MatchTeam.AWAY, atMs = start + 9 * minute)
        .logGoal(MatchTeam.HOME, atMs = start + 12 * minute)
        .logNote("Keeper changed", atMs = start + 13 * minute)

    repeat(addedTime) { match = match.stepAddedTime(1, atMs = start + 14 * minute) }
    if (paused) match = match.togglePause(atMs = start + 14 * minute)
    if (ended) match = match.endMatch(atMs = start + 14 * minute)
    return match
}

@Composable
private fun PreviewFrame(
    match: Match,
    landscape: Boolean,
    cameras: CameraSummary = CameraSummary(
        left = CameraState(connected = true),
        right = CameraState(connected = true),
    ),
) {
    DJIMatchLoggerTheme {
        MatchContent(
            match = match,
            actions = MatchActions(),
            landscape = landscape,
            cameras = cameras,
        )
    }
}

@Preview(name = "Landscape · running", device = LANDSCAPE, showBackground = true)
@Composable
private fun LandscapeRunning() = PreviewFrame(sampleMatch(), landscape = true)

@Preview(name = "Landscape · paused", device = LANDSCAPE, showBackground = true)
@Composable
private fun LandscapePaused() =
    PreviewFrame(sampleMatch(paused = true, addedTime = 2), landscape = true)

/** Recording, which is what it looks like for most of a match. */
@Preview(name = "Landscape · recording", device = LANDSCAPE, showBackground = true)
@Composable
private fun LandscapeRecording() = PreviewFrame(
    sampleMatch(),
    landscape = true,
    cameras = CameraSummary(
        left = CameraState(connected = true, recording = true),
        right = CameraState(connected = true, recording = true),
    ),
)

/** No cameras: every camera control disabled, which is the state on a bench. */
@Preview(name = "Landscape · no cameras", device = LANDSCAPE, showBackground = true)
@Composable
private fun LandscapeNoCameras() =
    PreviewFrame(sampleMatch(), landscape = true, cameras = CameraSummary())

/**
 * One camera rolling and one not — the state that must never be missed, and
 * the reason the pills report per camera rather than summed.
 */
@Preview(name = "Landscape · one camera only", device = LANDSCAPE, showBackground = true)
@Composable
private fun LandscapeHalfRecording() = PreviewFrame(
    sampleMatch(),
    landscape = true,
    cameras = CameraSummary(
        left = CameraState(connected = true, recording = true),
        right = CameraState(connected = true),
    ),
)

/** Counting down to the automatic stop after the final whistle. */
@Preview(name = "Landscape · stopping", device = LANDSCAPE, showBackground = true)
@Composable
private fun LandscapeStopping() = PreviewFrame(
    sampleMatch(ended = true),
    landscape = true,
    cameras = CameraSummary(
        left = CameraState(connected = true, recording = true),
        right = CameraState(connected = true, recording = true),
        stoppingInSeconds = 24,
    ),
)

@Preview(name = "Landscape · full time", device = LANDSCAPE, showBackground = true)
@Composable
private fun LandscapeEnded() = PreviewFrame(sampleMatch(ended = true), landscape = true)

/**
 * Nothing logged yet — the state the screen actually opens in, and the one
 * where half the buttons are disabled.
 */
@Preview(name = "Landscape · not started", device = LANDSCAPE, showBackground = true)
@Composable
private fun LandscapeFresh() = PreviewFrame(Match(), landscape = true)

@Preview(name = "Portrait · running", device = PORTRAIT, showBackground = true)
@Composable
private fun PortraitRunning() = PreviewFrame(sampleMatch(), landscape = false)

/**
 * The system font turned up, which is the setting that breaks a layout tuned
 * on one device. Nothing should fall off an edge.
 */
@Preview(
    name = "Landscape · large font",
    device = LANDSCAPE,
    fontScale = 1.3f,
    showBackground = true,
)
@Composable
private fun LandscapeLargeFont() = PreviewFrame(sampleMatch(), landscape = true)
