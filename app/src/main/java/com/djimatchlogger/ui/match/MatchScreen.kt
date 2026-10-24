package com.djimatchlogger.ui.match

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.djimatchlogger.R
import com.djimatchlogger.match.Match
import com.djimatchlogger.match.MatchEvent
import com.djimatchlogger.match.MatchEventType
import com.djimatchlogger.match.MatchMeta
import com.djimatchlogger.match.MatchTeam
import com.djimatchlogger.ui.theme.MatchPalette
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

/**
 * Everything the match screen can do, as plain lambdas.
 *
 * Every one defaults to doing nothing, which is what lets [MatchContent] be
 * rendered from a `@Preview` with no view model, no coroutines and no device.
 */
class MatchActions(
    val goal: (MatchTeam) -> Unit = {},
    val yellowCard: (MatchTeam) -> Unit = {},
    val redCard: (MatchTeam) -> Unit = {},
    val startNextPeriod: () -> Unit = {},
    val togglePause: () -> Unit = {},
    val endMatch: () -> Unit = {},
    val stepAddedTime: (Int) -> Unit = {},
    val undo: () -> Unit = {},
    val note: (String) -> Unit = {},
    val setMeta: (MatchMeta) -> Unit = {},
    val newMatch: () -> Unit = {},
    val toggleVideo: () -> Unit = {},
)

/** One camera, as the match screen needs to see it. */
data class CameraState(
    val connected: Boolean = false,
    val recording: Boolean = false,
)

/**
 * What the match screen knows about the two cameras.
 *
 * Per camera, not summed: "one of the two is recording" is exactly the
 * situation the operator has to be able to spot, and a single flag hides it.
 */
data class CameraSummary(
    val left: CameraState = CameraState(),
    val right: CameraState = CameraState(),
    /** Counting down to the automatic stop after the final whistle. */
    val stoppingInSeconds: Int? = null,
) {
    val anyReady: Boolean get() = left.connected || right.connected
    val anyRecording: Boolean get() = left.recording || right.recording
}

/**
 * The match screen as the app runs it: wired to the view model, taking its
 * orientation from the device.
 *
 * All the drawing is in [MatchContent], which knows about neither. To see the
 * screen while editing it, open the previews in `MatchPreviews.kt`.
 */
@Composable
fun MatchScreen(
    modifier: Modifier = Modifier,
    viewModel: MatchViewModel = viewModel(),
    cameras: CameraSummary = CameraSummary(),
    onToggleVideo: () -> Unit = {},
    fullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
) {
    val match by viewModel.match.collectAsStateWithLifecycle()
    val teams by viewModel.teams.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Re-read every time the screen comes up: teams.json is meant to be edited
    // on a PC, and a restart should not be the price of a new name.
    LaunchedEffect(Unit) { viewModel.refreshTeams() }

    // Undo takes something out of a log the operator may not be looking at, so
    // say what went. The match is read from the flow rather than captured, so
    // this effect never has to restart.
    LaunchedEffect(Unit) {
        viewModel.undone.collect { event ->
            snackbarHostState.showSnackbar(
                context.getString(
                    R.string.match_undone,
                    describe(context, event, viewModel.match.value.meta),
                ),
            )
        }
    }

    MatchContent(
        match = match,
        actions = remember(viewModel, onToggleVideo) {
            MatchActions(
                goal = viewModel::goal,
                yellowCard = viewModel::yellowCard,
                redCard = viewModel::redCard,
                startNextPeriod = viewModel::startNextPeriod,
                togglePause = viewModel::togglePause,
                endMatch = viewModel::endMatch,
                stepAddedTime = viewModel::stepAddedTime,
                undo = viewModel::undo,
                note = viewModel::note,
                setMeta = viewModel::setMeta,
                newMatch = viewModel::newMatch,
                toggleVideo = onToggleVideo,
            )
        },
        cameras = cameras,
        landscape = LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_LANDSCAPE,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/**
 * The match screen itself, from a [Match] and a set of [MatchActions].
 *
 * The layout: a header carrying the
 * camera state, the score and the clocks, then a grid of titled panels with
 * the log down the right-hand side.
 *
 * [landscape] is passed in rather than read from the configuration, so a
 * preview can ask for either layout on any device.
 */
@Composable
fun MatchContent(
    match: Match,
    actions: MatchActions,
    landscape: Boolean,
    modifier: Modifier = Modifier,
    cameras: CameraSummary = CameraSummary(),
    fullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val size = metrics(landscape)
    var showNote by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmStopVideo by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MatchPalette.Page,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Subtle glow in the top center
            val glowColor = if (MatchPalette.isDark) Color(0xFF0F2D52) else Color(0xFF3B82F6).copy(alpha = 0.2f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(glowColor.copy(alpha = 0.4f), Color.Transparent)
                        )
                    )
            )

            if (landscape) {
                LandscapeBody(
                    match = match,
                    actions = actions,
                    cameras = cameras,
                    size = size,
                    onNote = { showNote = true },
                    onEndMatch = { confirmEnd = true },
                    onNewMatch = { confirmNew = true },
                    onStopVideo = { confirmStopVideo = true }
                )
            } else {
                PortraitBody(
                    match = match,
                    actions = actions,
                    cameras = cameras,
                    size = size,
                    onNote = { showNote = true },
                    onEndMatch = { confirmEnd = true },
                    onNewMatch = { confirmNew = true },
                    onStopVideo = { confirmStopVideo = true }
                )
            }
        }
    }

    if (showNote) {
        NoteDialog(
            onDismiss = { showNote = false },
            onSave = {
                actions.note(it)
                showNote = false
            },
        )
    }

    if (confirmEnd) {
        ConfirmDialog(
            title = stringResource(R.string.match_end),
            text = stringResource(R.string.match_end_confirm),
            confirmLabel = stringResource(R.string.match_end),
            onDismiss = { confirmEnd = false },
            onConfirm = {
                actions.endMatch()
                confirmEnd = false
            },
        )
    }

    if (confirmStopVideo) {
        ConfirmDialog(
            title = stringResource(R.string.video_stop),
            text = stringResource(R.string.video_stop_confirm),
            confirmLabel = stringResource(R.string.video_stop),
            onDismiss = { confirmStopVideo = false },
            onConfirm = {
                actions.toggleVideo()
                confirmStopVideo = false
            },
        )
    }

    if (confirmNew) {
        ConfirmNewMatchDialog(
            onDismiss = { confirmNew = false },
            onConfirm = {
                actions.newMatch()
                confirmNew = false
            },
        )
    }
}// ---------------------------------------------------------------- the header

/**
 * Camera state, score and clocks, in two thin rows.
 *
 * Everything an operator has to see without pressing anything lives here.
 * A Material top app bar would be 64dp of mostly empty space; this is twice
 * the content in about the same height, and it carries its own status bar
 * inset so it still sits right with the system bars hidden.
 */
@Composable
fun AppHeader(
    match: Match,
    actions: MatchActions,
    cameras: CameraSummary,
    size: Metrics,
    landscape: Boolean,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    selectedTab: String,
    onTabSelect: (String) -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val enabled = match.started && !match.ended

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MatchPalette.PageTop)
            .windowInsetsPadding(
                WindowInsets.systemBars.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            )
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (landscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CameraPill(stringResource(R.string.cam_left), cameras.left)
                CameraPill(stringResource(R.string.cam_right), cameras.right)

                val accent = when {
                    match.ended -> MatchPalette.Muted
                    match.paused -> MatchPalette.Pause
                    match.started -> MatchPalette.Go
                    else -> MatchPalette.Muted
                }
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .background(MatchPalette.Panel, RoundedCornerShape(8.dp))
                        .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            match.ended -> stringResource(R.string.match_full_time)
                            match.paused -> stringResource(R.string.match_paused)
                            match.started -> stringResource(
                                R.string.match_period,
                                match.currentPeriod,
                                match.meta.periods,
                            )
                            else -> stringResource(R.string.match_not_started)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }

                // Small Tab/Edit buttons
                Row(
                    modifier = Modifier.padding(start = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HeaderTabButton(
                        label = stringResource(R.string.tab_match),
                        active = selectedTab == "MATCH",
                        landscape = landscape,
                        onClick = { onTabSelect("MATCH") }
                    )
                    HeaderTabButton(
                        label = stringResource(R.string.action_edit),
                        active = false,
                        landscape = landscape,
                        onClick = onEdit
                    )
                    HeaderTabButton(
                        label = stringResource(R.string.tab_config),
                        active = selectedTab == "CONFIG",
                        landscape = landscape,
                        onClick = { onTabSelect("CONFIG") }
                    )
                }

                Spacer(Modifier.weight(1f))
                HeaderButton(
                    stringResource(
                        if (fullscreen) R.string.action_exit_fullscreen
                        else R.string.action_fullscreen,
                    ),
                    onToggleFullscreen,
                )
                if (fullscreen) BatteryStatus()
                WallClock()
            }
        } else {
            // Portrait: Two rows for the top section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CameraPill(stringResource(R.string.cam_left), cameras.left)
                CameraPill(stringResource(R.string.cam_right), cameras.right)
                Spacer(Modifier.weight(1f))
                HeaderButton(
                    if (fullscreen) "EXIT" else "FULL",
                    onToggleFullscreen,
                )
                if (fullscreen) BatteryStatus()
                WallClock()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val accent = when {
                    match.ended -> MatchPalette.Muted
                    match.paused -> MatchPalette.Pause
                    match.started -> MatchPalette.Go
                    else -> MatchPalette.Muted
                }
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .background(MatchPalette.Panel, RoundedCornerShape(8.dp))
                        .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            match.ended -> stringResource(R.string.match_full_time)
                            match.paused -> stringResource(R.string.match_paused)
                            match.started -> "${match.currentPeriod}/${match.meta.periods}"
                            else -> stringResource(R.string.match_not_started)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                HeaderTabButton(
                    label = stringResource(R.string.tab_match),
                    active = selectedTab == "MATCH",
                    landscape = landscape,
                    onClick = { onTabSelect("MATCH") }
                )
                HeaderTabButton(
                    label = stringResource(R.string.action_edit),
                    active = false,
                    landscape = landscape,
                    onClick = onEdit
                )
                HeaderTabButton(
                    label = "CONFIG",
                    active = selectedTab == "CONFIG",
                    landscape = landscape,
                    onClick = { onTabSelect("CONFIG") }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeamScore(
                name = teamName(context, match.meta, MatchTeam.HOME),
                score = match.goals(MatchTeam.HOME),
                nameColor = MatchPalette.HomeText,
                boxColor = MatchPalette.HomeBox,
                borderColor = MatchPalette.Home,
                size = size,
                alignment = Alignment.Start,
                enabled = enabled,
                onGoal = if (landscape) { { actions.goal(MatchTeam.HOME) } } else null,
            )
            Clocks(match, size, modifier = Modifier.weight(1f))
            TeamScore(
                name = teamName(context, match.meta, MatchTeam.AWAY),
                score = match.goals(MatchTeam.AWAY),
                nameColor = MatchPalette.AwayText,
                boxColor = MatchPalette.AwayBox,
                borderColor = MatchPalette.Away,
                size = size,
                alignment = Alignment.End,
                enabled = enabled,
                onGoal = if (landscape) { { actions.goal(MatchTeam.AWAY) } } else null,
            )
        }
    }
}

@Composable
private fun HeaderTabButton(
    label: String,
    active: Boolean,
    landscape: Boolean,
    onClick: () -> Unit
) {
    val color = if (active) MatchPalette.Home else MatchPalette.Panel
    val gradientColor = when (color) {
        MatchPalette.Home -> MatchPalette.HomeEnd
        else -> if (MatchPalette.isDark) Color(0xFF1E293B) else Color(0xFFCBD5E1)
    }

    Box(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                brush = Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.9f), gradientColor)
                )
            )
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = if (active) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = if (landscape) 10.dp else 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Black else FontWeight.Bold,
            color = if (active) Color.White else MatchPalette.Muted,
            maxLines = 1
        )
    }
}

/**
 * A glossy, high-vibrancy button as seen on sport dashboards.
 */
@Composable
private fun BubbleButton(
    label: String,
    color: Color,
    gradientColor: Color,
    enabled: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
    onColor: Color = Color.White,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (enabled) {
                    Modifier
                        .background(
                            brush = Brush.verticalGradient(
                                listOf(
                                    color.copy(alpha = 0.9f),
                                    color,
                                    gradientColor
                                )
                            )
                        )
                        .clickable(onClick = onClick)
                } else {
                    Modifier.background(MatchPalette.Disabled)
                }
            )
            .border(
                width = 1.dp,
                color = if (enabled) Color.White.copy(alpha = 0.3f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        // Soccer ball background for Goal buttons
        if (icon == Icons.Default.SportsSoccer && enabled) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .size(height * 0.8f),
                tint = Color.White.copy(alpha = 0.1f)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null && icon != Icons.Default.SportsSoccer) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(height * 0.45f),
                    tint = onColor.copy(alpha = if (enabled) 1f else 0.4f)
                )
            }
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = onColor.copy(alpha = if (enabled) 1f else 0.4f),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * One camera: a dot, its name, and REC when it is rolling.
 *
 * The dot alone is not enough. A connected camera and a recording camera are
 * completely different situations, and the one thing the operator must never
 * be wrong about is whether both are actually recording — so the pill turns
 * red and says so in words, per camera rather than summed.
 */
@Composable
private fun CameraPill(label: String, state: CameraState) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val dotColor = when {
        state.recording -> MatchPalette.RedCard.copy(alpha = alpha)
        state.connected -> MatchPalette.Go
        else -> MatchPalette.Neutral
    }
    Row(
        modifier = Modifier
            .background(MatchPalette.Panel, RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = if (state.recording) MatchPalette.RedCard else MatchPalette.PanelBorder,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (state.connected) MatchPalette.Text else MatchPalette.Muted,
        )
        if (state.recording) {
            Text(
                text = stringResource(R.string.cam_rec),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MatchPalette.RedCard,
            )
        }
    }
}

@Composable
private fun HeaderButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = CompactButtonPadding,
        modifier = Modifier.height(HeaderButtonHeight),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MatchPalette.Muted,
        )
    }
}

/**
 * The time of day.
 *
 * Deliberately the only place in the app that reads the wall clock: it is
 * there for the operator to line up against the cameras and their own watch.
 * Nothing in the match log is timed by it — see [MatchViewModel].
 */
@Composable
private fun WallClock() {
    var text by remember { mutableStateOf(timeOfDay()) }
    LaunchedEffect(Unit) {
        while (true) {
            text = timeOfDay()
            delay(WALL_TICK_MS)
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MatchPalette.Muted,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun BatteryStatus() {
    val context = LocalContext.current
    var percentage by remember { mutableStateOf(getBatteryLevel(context)) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                percentage = getBatteryLevel(context)
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    Text(
        text = "$percentage%",
        style = MaterialTheme.typography.labelMedium,
        color = MatchPalette.Muted,
        modifier = Modifier.padding(start = 8.dp),
    )
}

private fun getBatteryLevel(context: Context): Int {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    return if (level >= 0 && scale > 0) (level * 100 / scale) else 0
}

@Composable
private fun TeamScore(
    name: String,
    score: Int,
    nameColor: Color,
    boxColor: Color,
    borderColor: Color,
    size: Metrics,
    alignment: Alignment.Horizontal,
    enabled: Boolean = true,
    onGoal: (() -> Unit)? = null,
) {
    Column(horizontalAlignment = alignment) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = nameColor,
            maxLines = 1,
            fontWeight = FontWeight.Bold
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(size.gap),
        ) {
            val scoreBox = @Composable {
                Box(
                    modifier = Modifier
                        .width(size.scoreBoxWidth)
                        .height(size.scoreBoxHeight)
                        .background(boxColor, RoundedCornerShape(8.dp))
                        .border(2.dp, borderColor, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = score.toString(),
                        style = size.score,
                        fontWeight = FontWeight.Bold,
                        color = MatchPalette.Text,
                    )
                }
            }

            if (alignment == Alignment.Start) {
                scoreBox()
                if (onGoal != null) {
                    BubbleButton(
                        label = stringResource(R.string.event_goal),
                        color = MatchPalette.Home,
                        gradientColor = MatchPalette.HomeEnd,
                        enabled = enabled,
                        height = size.scoreBoxHeight,
                        modifier = Modifier.width(size.goalWidth),
                        icon = Icons.Default.SportsSoccer,
                        onClick = onGoal,
                    )
                }
            } else {
                if (onGoal != null) {
                    BubbleButton(
                        label = stringResource(R.string.event_goal),
                        color = MatchPalette.Away,
                        gradientColor = MatchPalette.AwayEnd,
                        enabled = enabled,
                        height = size.scoreBoxHeight,
                        modifier = Modifier.width(size.goalWidth),
                        icon = Icons.Default.SportsSoccer,
                        onClick = onGoal,
                    )
                }
                scoreBox()
            }
        }
    }
}

/**
 * The period pill and both clocks.
 *
 * The match clock is the running total; the period clock restarts every period
 * and is the one a referee's watch shows. The ticking state lives here so only
 * this subtree redraws five times a second — hoisting it any higher would
 * recompose every panel along with it.
 */
@Composable
private fun Clocks(match: Match, size: Metrics, modifier: Modifier = Modifier) {
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = SystemClock.elapsedRealtime()
            delay(TICK_MS)
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = formatClock(match.liveElapsedMs(nowMs)),
                style = size.matchClock.copy(fontSize = size.matchClock.fontSize * 1.2f),
                fontWeight = FontWeight.Bold,
                color = MatchPalette.Text,
            )
            
            // Vertical separator as seen in the mockup
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(30.dp)
                    .background(MatchPalette.Muted.copy(alpha = 0.5f))
            )
            
            Text(
                text = stringResource(
                    R.string.clock_period_value,
                    formatClock(match.periodElapsedMs(nowMs)),
                ),
                style = size.periodClock,
                color = MatchPalette.Muted,
            )
        }
    }
}

// ----------------------------------------------------------------- the body

/**
 * Two rows of three panels, with the log down the right-hand side.
 *
 * Deliberately laid out the way the operator expects it: the
 * goal button is where their hands already look for it.
 */
@Composable
private fun LandscapeBody(
    match: Match,
    actions: MatchActions,
    cameras: CameraSummary,
    size: Metrics,
    onNote: () -> Unit,
    onEndMatch: () -> Unit,
    onNewMatch: () -> Unit,
    onStopVideo: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(size.gap),
    ) {
        // Two columns for panels + one for log = 3 columns total
        Row(
            modifier = Modifier
                .weight(2.6f)
                .verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(size.gap)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(size.gap)
            ) {
                StartPeriodsPanel(match, actions, cameras, size, onEndMatch, onStopVideo, Modifier.fillMaxWidth())
                CardsPanel(match, actions, size, Modifier.fillMaxWidth())
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(size.gap)
            ) {
                AddedTimePanel(match, actions, size, Modifier.fillMaxWidth())
                ControlsPanel(match, actions, size, onNote, onNewMatch, Modifier.fillMaxWidth())
            }
        }
        LogPanel(match, context, size, Modifier.weight(1f))
    }
}

/** The same panels, stacked, for a phone held upright. */
@Composable
private fun PortraitBody(
    match: Match,
    actions: MatchActions,
    cameras: CameraSummary,
    size: Metrics,
    onNote: () -> Unit,
    onEndMatch: () -> Unit,
    onNewMatch: () -> Unit,
    onStopVideo: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(size.gap),
    ) {
        GoalPanel(match, actions, size, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(size.gap)) {
            StartPeriodsPanel(match, actions, cameras, size, onEndMatch, onStopVideo, Modifier.weight(1f))
            AddedTimePanel(match, actions, size, Modifier.weight(1f))
        }
        CardsPanel(match, actions, size, Modifier.fillMaxWidth())
        ControlsPanel(match, actions, size, onNote, onNewMatch, Modifier.fillMaxWidth())
        LogPanel(match, context, size, Modifier.fillMaxWidth(), scrolls = false)
    }
}

// ---------------------------------------------------------------- the panels

@Composable
private fun StartPeriodsPanel(
    match: Match,
    actions: MatchActions,
    cameras: CameraSummary,
    size: Metrics,
    onEndMatch: () -> Unit,
    onStopVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = match.started && !match.ended
    Panel(stringResource(R.string.panel_start_periods), size, modifier) {
        AccentButton(
            label = cameras.stoppingInSeconds?.let {
                stringResource(R.string.video_stopping_in, it)
            } ?: stringResource(
                if (cameras.anyRecording) R.string.video_stop else R.string.video_start,
            ),
            color = when {
                cameras.stoppingInSeconds != null -> MatchPalette.Pause
                cameras.anyRecording -> MatchPalette.EndMatch
                else -> MatchPalette.Go
            },
            enabled = cameras.anyReady,
            height = size.buttonHeight,
            icon = if (cameras.anyRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
        ) { 
            if (cameras.anyRecording) {
                onStopVideo()
            } else {
                actions.toggleVideo()
            }
        }
        AccentButton(
            label = if (match.started) {
                stringResource(
                    R.string.match_next_period_of,
                    match.currentPeriod + 1,
                    match.meta.periods,
                )
            } else {
                stringResource(R.string.match_kick_off)
            },
            color = MatchPalette.NextPeriod,
            enabled = !match.ended && match.currentPeriod < match.meta.periods,
            height = size.buttonHeight,
            icon = if (match.started) Icons.Default.FastForward else Icons.Default.PlayArrow,
        ) { actions.startNextPeriod() }
        AccentButton(
            label = stringResource(
                if (match.paused) R.string.match_resume else R.string.match_pause,
            ),
            color = MatchPalette.Pause,
            enabled = running,
            height = size.buttonHeight,
            icon = if (match.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
        ) { actions.togglePause() }
        AccentButton(
            label = stringResource(R.string.match_end),
            color = MatchPalette.EndMatch,
            enabled = running,
            height = size.buttonHeight,
            icon = Icons.Default.Stop,
        ) { onEndMatch() }
    }
}

@Composable
private fun GoalPanel(
    match: Match,
    actions: MatchActions,
    size: Metrics,
    modifier: Modifier = Modifier,
) {
    val enabled = match.started && !match.ended
    Panel(stringResource(R.string.panel_goal), size, modifier) {
        // "GOAL Home", not "GOAL Ajax". The team names are right above in the
        // header, and a long club name would be clipped off a button that has
        // to stay one line.
        AccentButton(
            label = stringResource(R.string.match_goal_home),
            color = MatchPalette.Home,
            enabled = enabled,
            height = size.goalHeight,
        ) { actions.goal(MatchTeam.HOME) }
        AccentButton(
            label = stringResource(R.string.match_goal_away),
            color = MatchPalette.Away,
            enabled = enabled,
            height = size.goalHeight,
        ) { actions.goal(MatchTeam.AWAY) }
    }
}

@Composable
private fun AddedTimePanel(
    match: Match,
    actions: MatchActions,
    size: Metrics,
    modifier: Modifier = Modifier,
) {
    val running = match.started && !match.ended
    CollapsiblePanel(stringResource(R.string.panel_added_time), size, modifier) {
        Text(
            text = stringResource(R.string.added_time_minutes, match.addedTimeThisPeriod),
            style = size.matchClock,
            fontWeight = FontWeight.Bold,
            color = if (match.addedTimeThisPeriod > 0) {
                MatchPalette.Pause
            } else {
                MatchPalette.Muted
            },
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(size.gap)) {
            AccentButton(
                label = stringResource(R.string.match_added_time_less),
                color = MatchPalette.Neutral,
                enabled = running && match.addedTimeThisPeriod > 0,
                height = size.buttonHeight,
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Add, // Using Add as a placeholder or standard +/-
            ) { actions.stepAddedTime(-1) }
            AccentButton(
                label = stringResource(R.string.match_added_time_more),
                color = MatchPalette.Neutral,
                enabled = running,
                height = size.buttonHeight,
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Add,
            ) { actions.stepAddedTime(1) }
        }
    }
}

@Composable
private fun CardsPanel(
    match: Match,
    actions: MatchActions,
    size: Metrics,
    modifier: Modifier = Modifier,
) {
    if (!match.meta.cardsEnabled) return
    val enabled = match.started && !match.ended

    CollapsiblePanel(stringResource(R.string.panel_cards), size, modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(size.gap)) {
            CardButton(
                label = stringResource(R.string.card_yellow_home),
                count = match.cards(MatchTeam.HOME, MatchEventType.YELLOW_CARD),
                color = MatchPalette.YellowCard,
                onColor = MatchPalette.OnYellowCard,
                enabled = enabled,
                size = size,
                modifier = Modifier.weight(1f),
            ) { actions.yellowCard(MatchTeam.HOME) }
            CardButton(
                label = stringResource(R.string.card_yellow_away),
                count = match.cards(MatchTeam.AWAY, MatchEventType.YELLOW_CARD),
                color = MatchPalette.YellowCard,
                onColor = MatchPalette.OnYellowCard,
                enabled = enabled,
                size = size,
                modifier = Modifier.weight(1f),
            ) { actions.yellowCard(MatchTeam.AWAY) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(size.gap)) {
            CardButton(
                label = stringResource(R.string.card_red_home),
                count = match.cards(MatchTeam.HOME, MatchEventType.RED_CARD),
                color = MatchPalette.RedCard,
                onColor = Color.White,
                enabled = enabled,
                size = size,
                modifier = Modifier.weight(1f),
            ) { actions.redCard(MatchTeam.HOME) }
            CardButton(
                label = stringResource(R.string.card_red_away),
                count = match.cards(MatchTeam.AWAY, MatchEventType.RED_CARD),
                color = MatchPalette.RedCard,
                onColor = Color.White,
                enabled = enabled,
                size = size,
                modifier = Modifier.weight(1f),
            ) { actions.redCard(MatchTeam.AWAY) }
        }
    }
}

/**
 * The cameras, and the one button that starts them.
 *
 * Recording is started here by hand, minutes before kick-off, and never
 * automatically at the whistle: if a camera is going to fail, the operator
 * wants to know while there is still time to do something about it.
 */
@Composable
private fun ControlsPanel(
    match: Match,
    actions: MatchActions,
    size: Metrics,
    onNote: () -> Unit,
    onNewMatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(stringResource(R.string.panel_controls), size, modifier) {
        AccentButton(
            label = stringResource(R.string.controls_add_note),
            color = MatchPalette.Neutral,
            enabled = !match.ended,
            height = size.buttonHeight,
            icon = Icons.Default.Edit,
        ) { onNote() }
        AccentButton(
            label = stringResource(R.string.match_undo),
            color = MatchPalette.Undo,
            enabled = match.events.isNotEmpty(),
            height = size.buttonHeight,
            icon = Icons.AutoMirrored.Filled.Undo,
        ) { actions.undo() }
        AccentButton(
            label = stringResource(R.string.match_new),
            color = Color.White,
            onColor = MatchPalette.Danger,
            enabled = match.events.isNotEmpty(),
            height = size.buttonHeight,
            icon = Icons.Default.SportsSoccer,
        ) { onNewMatch() }
    }
}

/**
 * The log, newest first — the operator is checking what was just entered, not
 * reading it from the top.
 */
@Composable
private fun LogPanel(
    match: Match,
    context: Context,
    size: Metrics,
    modifier: Modifier = Modifier,
    scrolls: Boolean = true,
) {
    val panelModifier = if (scrolls) modifier.fillMaxHeight() else modifier
    Panel(stringResource(R.string.panel_log), size, panelModifier) {
        if (match.events.isEmpty()) {
            Text(
                text = stringResource(R.string.match_log_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MatchPalette.Muted,
            )
            return@Panel
        }
        if (scrolls) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                logEntries(match, context)
            }
        } else {
            // Portrait already scrolls the whole page, and a second scroller
            // on the same axis would not be allowed to measure.
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                match.events.asReversed().forEach { EventRow(match, it, context) }
            }
        }
    }
}

private fun LazyListScope.logEntries(match: Match, context: Context) {
    items(match.events.asReversed()) { event -> EventRow(match, event, context) }
}

@Composable
private fun EventRow(match: Match, event: MatchEvent, context: Context) {
    // The stripe carries the team, so "the away goals" can be found without
    // reading a single word.
    val stripe = when {
        event.type == MatchEventType.YELLOW_CARD -> MatchPalette.YellowCard
        event.type == MatchEventType.RED_CARD -> MatchPalette.RedCard
        event.team == MatchTeam.HOME -> MatchPalette.Home
        event.team == MatchTeam.AWAY -> MatchPalette.Away
        else -> MatchPalette.Go
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(15.dp)
                .background(stripe, RoundedCornerShape(2.dp)),
        )
        Text(
            text = formatClock(match.liveElapsedMs(event.atMs)),
            style = MaterialTheme.typography.labelSmall,
            color = MatchPalette.Muted,
        )
        Text(
            text = describe(context, event, match.meta),
            style = MaterialTheme.typography.labelMedium,
            color = MatchPalette.Text,
            maxLines = 2,
        )
    }
}

// ------------------------------------------------------------- the furniture

/** A titled box. Every group of controls on the old logger sat in one. */
@Composable
private fun Panel(
    title: String,
    size: Metrics,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(8.dp))
            .background(MatchPalette.Panel, RoundedCornerShape(8.dp))
            .border(1.dp, MatchPalette.PanelBorder, RoundedCornerShape(8.dp))
            .padding(size.panelPadding),
        verticalArrangement = Arrangement.spacedBy(size.panelGap),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MatchPalette.PanelTitle,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.2.sp,
            maxLines = 1,
        )
        content()
    }
}

@Composable
private fun CollapsiblePanel(
    title: String,
    size: Metrics,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    Column(
        modifier = modifier
            .shadow(if (expanded) 6.dp else 2.dp, RoundedCornerShape(8.dp))
            .background(MatchPalette.Panel, RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = if (expanded) MatchPalette.PanelBorder else MatchPalette.PanelBorder.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(size.panelPadding),
        verticalArrangement = Arrangement.spacedBy(size.panelGap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (expanded) MatchPalette.Text else MatchPalette.PanelTitle,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) "▼" else "◀",
                style = MaterialTheme.typography.labelSmall,
                color = MatchPalette.PanelTitle,
            )
        }
        if (expanded) {
            content()
        }
    }
}

/**
 * A flat button in a given colour.
 *
 * Material's own disabled treatment washes a saturated colour out to something
 * barely distinguishable from the panel, so disabled has its own explicit
 * colour here rather than an alpha over the accent.
 */
@Composable
private fun ColumnScope.AccentButton(
    label: String,
    color: Color,
    enabled: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
    onColor: Color = Color.White,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val gradientColor = when (color) {
        MatchPalette.Home -> MatchPalette.HomeEnd
        MatchPalette.Away -> MatchPalette.AwayEnd
        MatchPalette.NextPeriod -> MatchPalette.NextPeriodEnd
        MatchPalette.Pause -> MatchPalette.PauseEnd
        MatchPalette.EndMatch -> MatchPalette.EndMatchEnd
        MatchPalette.Go -> MatchPalette.GoEnd
        MatchPalette.Neutral -> MatchPalette.NeutralEnd
        Color.White -> Color(0xFFE2E8F0)
        else -> color.copy(alpha = 0.8f)
    }

    BubbleButton(
        label = label,
        color = color,
        gradientColor = gradientColor,
        enabled = enabled,
        height = height,
        modifier = modifier.fillMaxWidth(),
        onColor = onColor,
        icon = icon,
        onClick = onClick
    )
}

@Composable
private fun RowScope.AccentButton(
    label: String,
    color: Color,
    enabled: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
    onColor: Color = Color.White,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val gradientColor = when (color) {
        MatchPalette.Home -> MatchPalette.HomeEnd
        MatchPalette.Away -> MatchPalette.AwayEnd
        MatchPalette.NextPeriod -> MatchPalette.NextPeriodEnd
        MatchPalette.Pause -> MatchPalette.PauseEnd
        MatchPalette.EndMatch -> MatchPalette.EndMatchEnd
        MatchPalette.Go -> MatchPalette.GoEnd
        MatchPalette.Neutral -> MatchPalette.NeutralEnd
        Color.White -> Color(0xFFE2E8F0)
        else -> color.copy(alpha = 0.8f)
    }

    BubbleButton(
        label = label,
        color = color,
        gradientColor = gradientColor,
        enabled = enabled,
        height = height,
        modifier = modifier.weight(1f),
        onColor = onColor,
        icon = icon,
        onClick = onClick
    )
}

/** A card button: the team it is against, and how many they have. */
@Composable
private fun RowScope.CardButton(
    label: String,
    count: Int,
    color: Color,
    onColor: Color,
    enabled: Boolean,
    size: Metrics,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(size.buttonHeight)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (enabled) {
                    Modifier
                        .background(color)
                        .clickable(onClick = onClick)
                } else {
                    Modifier.background(MatchPalette.Disabled)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = (if (count == 0) label else "$label $count").uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            color = onColor.copy(alpha = if (enabled) 1f else 0.4f),
            maxLines = 1,
            softWrap = false,
        )
    }
}

// --------------------------------------------------------------- formatting

/** Minutes and seconds, counting on past the hour: 92:15 rather than 1:32:15. */
internal fun formatClock(ms: Long): String {
    val seconds = ms / 1000
    return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}

/** The time of day, to the second, on a 24-hour clock. */
private fun timeOfDay(): String = String.format(Locale.US, "%tT", Date())

internal fun teamName(context: Context, meta: MatchMeta, team: MatchTeam): String = when (team) {
    MatchTeam.HOME -> meta.home.ifBlank { context.getString(R.string.match_home_default) }
    MatchTeam.AWAY -> meta.away.ifBlank { context.getString(R.string.match_away_default) }
    MatchTeam.NONE -> ""
}

/** One line for the event log, and for the snackbar after an undo. */
internal fun describe(context: Context, event: MatchEvent, meta: MatchMeta): String {
    val what = when (event.type) {
        MatchEventType.VIDEO_START -> {
            val text = context.getString(R.string.event_video_start)
            // A recording the cameras never confirmed is not the same thing as
            // a recording, and the log has to be honest about which it was.
            if (event.camerasConfirmedValid && !event.camerasConfirmed) {
                text + " (" + context.getString(R.string.event_unconfirmed) + ")"
            } else {
                text
            }
        }

        MatchEventType.PERIOD_START -> context.getString(R.string.event_period_start, event.period)
        MatchEventType.MATCH_END -> context.getString(R.string.event_match_end)
        MatchEventType.GOAL -> context.getString(R.string.event_goal)
        MatchEventType.YELLOW_CARD -> context.getString(R.string.event_yellow)
        MatchEventType.RED_CARD -> context.getString(R.string.event_red)
        MatchEventType.ADDED_TIME -> context.getString(R.string.event_added_time, event.minutes)
        MatchEventType.PAUSE_START -> context.getString(R.string.event_pause_start)
        MatchEventType.PAUSE_END -> context.getString(R.string.event_pause_end)
        MatchEventType.NOTE -> context.getString(R.string.event_note) + ": " + event.note
    }

    val team = teamName(context, meta, event.team)
    return if (team.isEmpty()) what else context.getString(R.string.event_with_team, what, team)
}
